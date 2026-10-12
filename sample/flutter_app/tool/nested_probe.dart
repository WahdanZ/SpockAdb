// ignore_for_file: avoid_print
// S26 probe (#180): how a nested Navigator links to the route that holds it, and what hides an
// inactive tab. VM Service object reads only (getVM, getIsolate, getObject, getInstances); no
// evaluate, no invoke. `dart run tool/nested_probe.dart <flutter run log>` reads the VM Service
// address from a `flutter run` log and never prints it.
import 'dart:convert';
import 'dart:io';

late WebSocket ws;
var _id = 0;
var calls = 0;
var dumpKey = true;
final _pending = <int, List<dynamic>>{};

Future<Map<String, dynamic>> call(String m, [Map<String, dynamic> p = const {}]) async {
  final i = ++_id;
  calls++;
  final box = <dynamic>[];
  _pending[i] = box;
  ws.add(jsonEncode({'jsonrpc': '2.0', 'id': i, 'method': m, 'params': p}));
  final sw = Stopwatch()..start();
  while (box.isEmpty) {
    if (sw.elapsedMilliseconds > 5000) throw 'timeout $m';
    await Future.delayed(const Duration(milliseconds: 2));
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

String cls(Map<String, dynamic>? v) => '${v?['class']?['name'] ?? v?['kind']}';

String show(Map<String, dynamic>? v) {
  if (v == null) return 'absent';
  if (v['kind'] == 'Null') return 'null';
  if (v['kind'] == 'String') return '"${v['valueAsString']}"';
  if (v['valueAsString'] != null) return '${v['valueAsString']}';
  return cls(v);
}

/// Widgets whose state can hide a subtree, or that name a route.
const interesting = {
  'Offstage', 'Visibility', 'TickerMode', 'IndexedStack', '_ModalScope', '_ModalScopeStatus',
  'StatefulNavigationShell', '_IndexedStackedRouteBranchContainer', 'HeroControllerScope',
  'Navigator', 'ExcludeFocus', 'FocusScope', 'IgnorePointer', '_RawIndexedStack',
};

Future<String> routeName(Map<String, dynamic> routeRef) async {
  final route = await obj(routeRef['id']);
  final settings = field(route, '_settings');
  if (settings == null || settings['kind'] == 'Null') return '${cls(routeRef)}(no settings)';
  final s = await obj(settings['id']);
  return '${cls(routeRef)} name=${show(field(s, 'name'))}';
}

Future<List<String>> stackOf(Map<String, dynamic> nav) async {
  final history = field(nav, '_history');
  if (history == null) return ['?'];
  var h = await obj(history['id']);
  if (h['kind'] != 'List') h = await obj(field(h, '_value')!['id']);
  return [
    for (final e in (h['elements'] as List? ?? []))
      await routeName(field(await obj(e['id']), 'route')!),
  ];
}


/// Every entry of a `PersistentHashMap<Type, InheritedElement>`, by the key type's name.
Future<Map<String, Map<String, dynamic>>> inherited(Map<String, dynamic> map) async {
  final out = <String, Map<String, dynamic>>{};
  final root = field(map, '_root');
  if (root == null || root['kind'] == 'Null') return out;
  Future<void> node(Map<String, dynamic> ref) async {
    final n = await obj(ref['id']);
    final kind = cls(n);
    if (kind == '_FullNode') {
      final list = await obj(field(n, 'descendants')!['id']);
      for (final d in list['elements'] as List) {
        if (d['kind'] != 'Null') await node(d);
      }
    } else {
      final list = await obj(field(n, 'keyValuePairs')!['id']);
      final items = list['elements'] as List;
      for (var i = 0; i + 1 < items.length; i += 2) {
        final k = items[i] as Map<String, dynamic>;
        final v = items[i + 1] as Map<String, dynamic>;
        if (k['kind'] == 'Null') {
          if (v['kind'] != 'Null') await node(v);
        } else {
          out['${k['name'] ?? k['valueAsString'] ?? cls(k)}'] = v;
          if (dumpKey && (k['name'] == '_ModalScopeStatus' || k['name'] == '_VisibilityScope')) {
            dumpKey = k['name'] == '_VisibilityScope' ? false : dumpKey;
            print('    KEY REF: ${jsonEncode(k)}');
          }
        }
      }
    }
  }
  await node(root);
  return out;
}

Future<void> main(List<String> a) async {
  final log = File(a[0]).readAsStringSync();
  final m = RegExp(r'Dart VM Service on .* is available at: (http://\S+)').allMatches(log).last;
  final u = Uri.parse(m.group(1)!);
  ws = await WebSocket.connect(u.replace(scheme: 'ws', path: '${u.path}ws').toString());
  ws.listen((d) {
    final j = jsonDecode(d);
    _pending.remove(j['id'])?.add(j);
  });

  final vm = await call('getVM');
  iso = [for (final i in vm['isolates']) if (i['name'] == 'main') i['id']].first;
  final isolate = await call('getIsolate', {'isolateId': iso});
  final lib = (isolate['libraries'] as List).firstWhere((l) => l['uri'] == 'package:flutter/src/widgets/navigator.dart');
  final navCls = ((await obj(lib['id']))['classes'] as List).firstWhere((c) => c['name'] == 'NavigatorState');
  final inst = await call('getInstances', {'isolateId': iso, 'objectId': navCls['id'], 'limit': 20});
  print('NavigatorState instances: ${inst['totalCount']}');

  // identityHashCode of each mounted navigator's element → its index here.
  final navElements = <int, int>{};
  final navs = <Map<String, dynamic>>[];
  for (final ref in inst['instances']) {
    final nav = await obj(ref['id']);
    final element = field(nav, '_element');
    if (element == null || element['kind'] == 'Null') continue;
    navElements[element['identityHashCode'] as int] = navs.length;
    navs.add(nav);
  }

  for (var n = 0; n < navs.length; n++) {
    final nav = navs[n];
    final key = field(field(nav, '_element') == null ? nav : (await obj(field(nav, '_element')!['id'])), '_widget');
    print('\n=== navigator #$n  stack: ${await stackOf(nav)}');
    if (key != null) {
      final widget = await obj(key['id']);
      print('    widget key: ${cls(field(widget, 'key'))}');
    }
    {
      final before = calls;
      final sw = Stopwatch()..start();
      final el = await obj(field(nav, '_element')!['id']);
      final map = field(el, '_inheritedElements');
      if (map == null || map['kind'] == 'Null') {
        print('    _inheritedElements: ${show(map)}');
      } else {
        final entries = await inherited(await obj(map['id']));
        final status = entries['_ModalScopeStatus'];
        final ticker = entries['_EffectiveTickerMode'];
        var route = 'none';
        var isCurrent = '?';
        if (status != null) {
          final w = await obj(field(await obj(status['id']), '_widget')!['id']);
          route = await routeName(field(w, 'route')!);
          isCurrent = show(field(w, 'isCurrent'));
        }
        var enabled = '?';
        if (ticker != null) {
          final w = await obj(field(await obj(ticker['id']), '_widget')!['id']);
          enabled = show(field(w, 'enabled'));
        }
        var visible = '?';
        final scope = entries['_VisibilityScope'];
        if (scope != null) {
          final w = await obj(field(await obj(scope['id']), '_widget')!['id']);
          visible = show(field(w, 'isVisible'));
        }
        print('    inherited: ${entries.length} types; enclosing route: $route isCurrent=$isCurrent; '
            'ticker=$enabled visible=$visible; ${calls - before} calls, ${sw.elapsedMilliseconds} ms');
        if (a.contains('types')) print('    types: ${entries.keys.toList()..sort()}');
      }
    }
    final before = calls;
    final sw = Stopwatch()..start();
    var element = await obj(field(nav, '_element')!['id']);
    var hops = 0;
    int? firstScopeHop;
    while (true) {
      final parent = field(element, '_parent');
      if (parent == null || parent['kind'] == 'Null') {
        print('    reached the root element after $hops hops');
        break;
      }
      hops++;
      element = await obj(parent['id']);
      final widgetRef = field(element, '_widget');
      final widgetClass = cls(widgetRef);
      if (navElements.containsKey(element['identityHashCode'])) {
        print('    hop $hops: parent navigator #${navElements[element['identityHashCode']]}');
        break;
      }
      if (interesting.contains(widgetClass)) {
        final w = await obj(widgetRef!['id']);
        final detail = switch (widgetClass) {
          'Offstage' => 'offstage=${show(field(w, 'offstage'))}',
          'Visibility' => 'visible=${show(field(w, 'visible'))} maintainState=${show(field(w, 'maintainState'))}',
          'TickerMode' => 'enabled=${show(field(w, 'enabled'))}',
          'IndexedStack' || '_RawIndexedStack' => 'index=${show(field(w, 'index'))}',
          '_ModalScope' => 'route=${await routeName(field(w, 'route')!)}',
          '_ModalScopeStatus' => 'isCurrent=${show(field(w, 'isCurrent'))} canPop=${show(field(w, 'canPop'))}',
          'IgnorePointer' => 'ignoring=${show(field(w, 'ignoring'))}',
          _ => '',
        };
        print('    hop $hops: $widgetClass $detail  (element ${cls(element)})');
        if (widgetClass == '_ModalScope') firstScopeHop ??= hops;
      }
    }
    print('    first _ModalScope at hop $firstScopeHop; ${calls - before} calls, ${sw.elapsedMilliseconds} ms');
  }
  await ws.close();
}
