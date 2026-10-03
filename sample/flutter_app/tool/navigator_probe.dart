// S25 probe: the live Navigator stack through VM Service object reads only (getVM, getIsolate,
// getObject, getInstances). No evaluate, no invoke. `dart run tool/navigator_probe.dart <flutter run log>`
// reads the VM Service address from a `flutter run` log and never prints it.
import 'dart:convert';
import 'dart:io';

late WebSocket ws;
var _id = 0;
final _pending = <int, List<dynamic>>{};

Future<Map<String, dynamic>> call(String m, [Map<String, dynamic> p = const {}]) async {
  final i = ++_id;
  final box = <dynamic>[];
  _pending[i] = box;
  ws.add(jsonEncode({'jsonrpc': '2.0', 'id': i, 'method': m, 'params': p}));
  final sw = Stopwatch()..start();
  while (box.isEmpty) {
    if (sw.elapsedMilliseconds > 5000) throw 'timeout $m';
    await Future.delayed(const Duration(milliseconds: 5));
  }
  final r = box.first as Map<String, dynamic>;
  if (r['error'] != null) throw 'rpc $m: ${r['error']}';
  return r['result'] as Map<String, dynamic>;
}

late String iso;
Future<Map<String, dynamic>> obj(String id) => call('getObject', {'isolateId': iso, 'objectId': id});

Map<String, dynamic>? field(Map<String, dynamic> inst, String name) {
  for (final f in (inst['fields'] as List? ?? [])) {
    final decl = f['decl'] ?? {};
    if ((decl['name'] ?? f['name']) == name) return f['value'] as Map<String, dynamic>?;
  }
  return null;
}

List<String> fieldNames(Map<String, dynamic> inst) =>
    [for (final f in (inst['fields'] as List? ?? [])) '${(f['decl'] ?? {})['name'] ?? f['name']}'];

String show(Map<String, dynamic>? v) {
  if (v == null) return 'absent';
  if (v['kind'] == 'Null') return 'null';
  if (v['kind'] == 'String') return '"${v['valueAsString']}"';
  if (v['valueAsString'] != null) return '${v['valueAsString']}';
  return '${v['class']?['name'] ?? v['kind']}';
}

Future<void> main(List<String> a) async {
  final log = File(a[0]).readAsStringSync();
  final m = RegExp(r'Dart VM Service on .* is available at: (http://\S+)').allMatches(log).last;
  final u = Uri.parse(m.group(1)!);
  ws = await WebSocket.connect(u.replace(scheme: 'ws', path: '${u.path}ws').toString());
  ws.listen((d) { final j = jsonDecode(d); final b = _pending.remove(j['id']); b?.add(j); });

  final vm = await call('getVM');
  iso = [for (final i in vm['isolates']) if (i['name'] == 'main') i['id']].first;
  final isolate = await call('getIsolate', {'isolateId': iso});
  final lib = (isolate['libraries'] as List).firstWhere((l) => l['uri'] == 'package:flutter/src/widgets/navigator.dart');
  final libObj = await obj(lib['id']);
  final navCls = (libObj['classes'] as List).firstWhere((c) => c['name'] == 'NavigatorState');
  final inst = await call('getInstances', {'isolateId': iso, 'objectId': navCls['id'], 'limit': 20});
  print('NavigatorState instances: ${inst['totalCount']}');
  for (final ref in inst['instances']) {
    final nav = await obj(ref['id']);
    final element = field(nav, '_element');
    print('\nNavigatorState ${ref['id']}  mounted(_element)=${show(element)}');
    if (a.contains('fields')) print('  fields: ${fieldNames(nav)}');
    final history = field(nav, '_history');
    print('  _history: ${show(history)}');
    if (history == null) continue;
    var h = await obj(history['id']);
    if (h['kind'] != 'List') {
      if (a.contains('fields')) print('  _history fields: ${fieldNames(h)}');
      final v = field(h, '_value');
      if (v == null) continue;
      h = await obj(v['id']);
    }
    // a growable list's elements
    final elems = h['elements'] as List? ?? [];
    print('  entries: ${elems.length}');
    for (final e in elems) {
      final entry = await obj(e['id']);
      if (a.contains('fields') && e == elems.first) print('  _RouteEntry fields: ${fieldNames(entry)}');
      final state = field(entry, 'currentState');
      String lifecycle = show(state);
      if (state != null && state['id'] != null) {
        final s = await obj(state['id']);
        lifecycle = show(field(s, '_name')) ;
      }
      final route = field(entry, 'route');
      final r = await obj(route!['id']);
      final settings = field(r, '_settings');
      var name = 'absent';
      if (settings != null && settings['id'] != null) {
        final st = await obj(settings['id']);
        name = show(field(st, 'name'));
      }
      print('    ${r['class']['name']}  name=$name  lifecycle=$lifecycle');
    }
  }
  await ws.close();
}
