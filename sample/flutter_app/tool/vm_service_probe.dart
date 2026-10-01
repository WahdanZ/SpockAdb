// ignore_for_file: avoid_print
// Spike probe for docs/FLUTTER-SPIKE.md: connects to a running app's VM Service and reports
// what Spock's P4/P5 plan relies on. Not part of the app.
//
//   dart run tool/vm_service_probe.dart <ws-uri> [listen-seconds]
import 'dart:async';
import 'dart:convert';

import 'package:vm_service/vm_service.dart';
import 'package:vm_service/vm_service_io.dart';

Future<void> main(List<String> args) async {
  final uri = args.first;
  final seconds = args.length > 1 ? int.parse(args[1]) : 20;
  final VmService service;
  try {
    service = await vmServiceConnectUri(uri);
  } catch (e) {
    print('CONNECT FAILED: $e');
    return;
  }
  final version = await service.getVersion();
  final vm = await service.getVM();
  print('connected: protocol ${version.major}.${version.minor}, ${vm.isolates?.length} isolates');

  IsolateRef? ui;
  for (final ref in vm.isolates ?? <IsolateRef>[]) {
    final isolate = await service.getIsolate(ref.id!);
    final rpcs = isolate.extensionRPCs ?? [];
    final flutter = rpcs.where((r) => r.startsWith('ext.flutter')).length;
    print('isolate ${ref.name} paused=${isolate.pauseEvent?.kind} ext.flutter=$flutter total=${rpcs.length}');
    if (flutter > 0) ui = ref;
    for (final name in [
      'ext.flutter.inspector.getRootWidgetSummaryTree',
      'ext.flutter.inspector.getRootWidgetTree',
      'ext.flutter.inspector.structuredErrors',
      'ext.flutter.inspector.trackRebuildDirtyWidgets',
      'ext.dart.io.getHttpProfile',
      'ext.dart.io.httpEnableTimelineLogging',
      'ext.flutter.debugPaint',
    ]) {
      if (flutter > 0) print('  $name: ${rpcs.contains(name)}');
    }
  }
  if (ui == null) {
    print('no Flutter UI isolate');
    await service.dispose();
    return;
  }
  final id = ui.id!;
  final structured = await service.callServiceExtension('ext.flutter.inspector.structuredErrors', isolateId: id);
  print('structuredErrors: ${structured.json?['enabled']}');
  final http = await service.callServiceExtension('ext.dart.io.httpEnableTimelineLogging', isolateId: id);
  print('httpEnableTimelineLogging (before): ${http.json?['enabled']}');

  final registered = <String>[];
  service.onServiceEvent.listen((e) => registered.add('${e.kind} ${e.service} ${e.method}'));
  final counts = <String, int>{};
  void count(String key) => counts[key] = (counts[key] ?? 0) + 1;
  service.onExtensionEvent.listen((e) {
    count('Extension:${e.extensionKind}');
    if (e.extensionKind == 'Flutter.Error') {
      final data = e.extensionData?.data ?? {};
      print('  Flutter.Error: ${(data['description'] ?? '').toString().split('\n').first}');
    }
    if (e.extensionKind == 'Flutter.Navigation') print('  Flutter.Navigation: ${jsonEncode(e.extensionData?.data)}');
  });
  service.onLoggingEvent.listen((e) => count('Logging'));
  service.onStdoutEvent.listen((e) => count('Stdout'));
  service.onIsolateEvent.listen((e) => count('Isolate:${e.kind}'));
  for (final stream in [EventStreams.kExtension, EventStreams.kLogging, EventStreams.kStdout, EventStreams.kIsolate, EventStreams.kService]) {
    try {
      await service.streamListen(stream);
    } catch (e) {
      print('streamListen $stream failed: $e');
    }
  }
  print('listening ${seconds}s…');
  await Future<void>.delayed(Duration(seconds: seconds));
  print('events: $counts');
  print('registered services: $registered');
  await service.dispose();
}
