// ignore_for_file: avoid_print
// Spike probe for docs/FLUTTER-SPIKE.md: connects to a running app's VM Service and reports
// what Spock's P4/P5 plan relies on. Not part of the app.
//
//   dart run tool/vm_service_probe.dart <ws-uri> [listen-seconds]
//
// It never prints the URI's token, nor the values of the two extensions that carry it.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:vm_service/vm_service.dart';
import 'package:vm_service/vm_service_io.dart';

const _callTimeout = Duration(seconds: 5);

/// flutter_tools sets these to the tokenised VM Service / DevTools URI, and DDS replays the
/// resulting `Flutter.ServiceExtensionStateChanged` to every new client.
const _secretExtensions = {
  'ext.flutter.connectedVmServiceUri',
  'ext.flutter.activeDevToolsServerAddress',
};

/// Pause kinds that leave an isolate unable to answer service extension calls.
const _pausedKinds = {
  EventKind.kPauseStart,
  EventKind.kPauseExit,
  EventKind.kPauseBreakpoint,
  EventKind.kPauseException,
  EventKind.kPauseInterrupted,
  EventKind.kPausePostRequest,
};

Future<T> _timed<T>(String what, Future<T> call) =>
    call.timeout(_callTimeout, onTimeout: () => throw TimeoutException('$what took longer than $_callTimeout'));

Future<void> main(List<String> args) async {
  if (args.isEmpty) {
    print('usage: dart run tool/vm_service_probe.dart <ws-uri> [listen-seconds]');
    exitCode = 64;
    return;
  }
  final uri = args.first;
  final seconds = args.length > 1 ? int.parse(args[1]) : 20;
  final VmService service;
  try {
    service = await _timed('connect', vmServiceConnectUri(uri));
  } catch (e) {
    // The error text can echo the URI; keep the token out of the output.
    print('CONNECT FAILED: ${e.toString().replaceAll(uri, '<uri>')}');
    return;
  }
  // Host clock; device events carry the device clock, so events right at the boundary can land
  // on the wrong side if the two clocks disagree.
  final connectedAtMs = DateTime.now().millisecondsSinceEpoch;
  try {
    await _probe(service, seconds, connectedAtMs);
  } catch (e) {
    print('PROBE FAILED: $e');
  } finally {
    await service.dispose();
  }
}

Future<void> _probe(VmService service, int seconds, int connectedAtMs) async {
  final version = await _timed('getVersion', service.getVersion());
  final vm = await _timed('getVM', service.getVM());
  print('connected: protocol ${version.major}.${version.minor}, ${vm.isolates?.length} isolates');
  try {
    final dds = await _timed('getDartDevelopmentServiceVersion', service.callMethod('getDartDevelopmentServiceVersion'));
    print('DDS: ${dds.json?['major']}.${dds.json?['minor']}');
  } on RPCError {
    print('direct VM, no DDS');
  } catch (e) {
    print('getDartDevelopmentServiceVersion: $e');
  }
  try {
    final views = await _timed('_flutter.listViews', service.callMethod('_flutter.listViews'));
    for (final view in (views.json?['views'] as List?) ?? const []) {
      final isolate = (view as Map)['isolate'] as Map?;
      print('view ${view['id']} isolate=${isolate?['id']} ${isolate?['name']}');
    }
  } catch (e) {
    print('_flutter.listViews: $e');
  }

  final flutterIsolates = <IsolateRef>[];
  for (final ref in vm.isolates ?? <IsolateRef>[]) {
    final Isolate isolate;
    try {
      isolate = await _timed('getIsolate ${ref.name}', service.getIsolate(ref.id!));
    } catch (e) {
      print('isolate ${ref.name} ${ref.id}: $e');
      continue;
    }
    final pause = isolate.pauseEvent?.kind;
    final rpcs = isolate.extensionRPCs ?? [];
    final flutter = rpcs.where((r) => r.startsWith('ext.flutter')).length;
    print('isolate ${ref.name} ${ref.id} pause=$pause ext.flutter=$flutter total=${rpcs.length}');
    if (_pausedKinds.contains(pause)) {
      // Extension calls on a paused isolate wait until it resumes; one paused at start has not
      // registered its extensions yet.
      print('  skipped: paused ($pause)${rpcs.isEmpty ? ', no extensions registered yet' : ''}');
      continue;
    }
    if (flutter == 0) continue;
    flutterIsolates.add(ref);
    for (final name in [
      'ext.flutter.inspector.getRootWidgetSummaryTree',
      'ext.flutter.inspector.getRootWidgetTree',
      'ext.flutter.inspector.structuredErrors',
      'ext.flutter.inspector.trackRebuildDirtyWidgets',
      'ext.dart.io.getHttpProfile',
      'ext.dart.io.httpEnableTimelineLogging',
      'ext.flutter.debugPaint',
    ]) {
      print('  $name: ${rpcs.contains(name)}');
    }
  }
  if (flutterIsolates.isEmpty) {
    print('no running Flutter UI isolate');
    return;
  }
  print('Flutter isolates: ${flutterIsolates.length}');
  for (final ref in flutterIsolates) {
    for (final name in ['ext.flutter.inspector.structuredErrors', 'ext.dart.io.httpEnableTimelineLogging']) {
      try {
        final response = await _timed(name, service.callServiceExtension(name, isolateId: ref.id));
        final enabled = response.json?['enabled'];
        // Flutter's bool extensions answer the string "true"/"false"; dart:io answers a bool.
        print('${ref.id} $name: $enabled (${enabled.runtimeType})');
      } catch (e) {
        print('${ref.id} $name: $e');
      }
    }
  }

  final registered = <String>[];
  final before = <String, int>{};
  final after = <String, int>{};
  void count(String key, int? timestamp) {
    final counts = (timestamp ?? connectedAtMs) < connectedAtMs ? before : after;
    counts[key] = (counts[key] ?? 0) + 1;
  }

  void onError(Object e) => print('stream error: $e');
  service.onServiceEvent.listen(
    (e) => registered.add('${e.kind} ${e.service} ${e.method}'),
    onError: onError,
  );
  service.onExtensionEvent.listen((e) {
    count('Extension:${e.extensionKind}', e.timestamp);
    final data = e.extensionData?.data ?? {};
    final history = (e.timestamp ?? connectedAtMs) < connectedAtMs ? ' (history)' : '';
    switch (e.extensionKind) {
      case 'Flutter.Error':
        print('  Flutter.Error$history: ${(data['description'] ?? '').toString().split('\n').first}');
      case 'Flutter.Navigation':
        print('  Flutter.Navigation$history: ${jsonEncode(data)}');
      case 'Flutter.ServiceExtensionStateChanged':
        final extension = data['extension'];
        final value = _secretExtensions.contains(extension) ? '<redacted>' : data['value'];
        print('  ServiceExtensionStateChanged$history: $extension = $value');
    }
  }, onError: onError);
  service.onLoggingEvent.listen((e) => count('Logging', e.timestamp), onError: onError);
  service.onStdoutEvent.listen((e) => count('Stdout', e.timestamp), onError: onError);
  service.onStderrEvent.listen((e) => count('Stderr', e.timestamp), onError: onError);
  service.onIsolateEvent.listen((e) => count('Isolate:${e.kind}', e.timestamp), onError: onError);
  for (final stream in [
    EventStreams.kExtension,
    EventStreams.kLogging,
    EventStreams.kStdout,
    EventStreams.kStderr,
    EventStreams.kIsolate,
    EventStreams.kService,
  ]) {
    try {
      await _timed('streamListen $stream', service.streamListen(stream));
    } catch (e) {
      print('streamListen $stream failed: $e');
    }
  }
  print('listening ${seconds}s…');
  await Future<void>.delayed(Duration(seconds: seconds));
  print('events before connect (replayed history): $before');
  print('events after connect: $after');
  print('registered services: $registered');
}
