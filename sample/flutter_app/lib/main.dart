import 'package:flutter/material.dart';

import 'fixtures/errors.dart';
import 'fixtures/frames.dart';
import 'fixtures/layout.dart';
import 'fixtures/login.dart';
import 'fixtures/logs.dart';
import 'fixtures/network.dart';
import 'fixtures/navigation.dart';
import 'fixtures/permissions.dart';
import 'fixtures/push.dart';
import 'fixtures/routes.dart';
import 'fixtures/storage.dart';

/// Spock ADB's Flutter fixture app. Each hub entry names the Spock feature it exists for;
/// `sample/flutter_app/README.md` lists what to check on each.
void main() => runApp(const SpockFlutterSample());

class Fixture {
  const Fixture(this.route, this.title, this.forFeature, this.builder);

  final String route;
  final String title;
  final String forFeature;
  final WidgetBuilder builder;
}

final fixtures = <Fixture>[
  Fixture('/navigation', 'Navigation', 'Current Flutter route and exact back stack checks', (_) => const NavigationScreen()),
  Fixture('/login', 'Login', 'UI tree + android_* element tools via Semantics(identifier:)', (_) => const LoginScreen()),
  Fixture('/items', 'List → detail routes', 'Deep links, route stack, back navigation', (_) => const ItemListScreen()),
  Fixture('/layout', 'Layout overflow', 'Flutter errors in Diagnose / Timeline', (_) => const LayoutScreen()),
  Fixture('/errors', 'Errors and plugin failures', 'MissingPluginException, PlatformException, Dart and native crashes', (_) => const ErrorsScreen()),
  Fixture('/network', 'Network', 'Failing HTTP in Diagnose; proxy', (_) => const NetworkScreen()),
  Fixture('/permissions', 'Native permission dialogs', 'Native screens above Flutter; grant / revoke', (_) => const PermissionsScreen()),
  Fixture('/push', 'Push messages', 'android_send_push_message / ios_send_push', (_) => const PushScreen()),
  Fixture('/storage', 'Storage', 'SharedPreferences flutter.* keys, Hive, sqflite', (_) => const StorageScreen()),
  Fixture('/logs', 'Logs', 'Flutter logcat preset, Timeline logs', (_) => const LogsScreen()),
  Fixture('/frames', 'Frames and rebuilds', 'Jank (profile builds) and rebuild storms', (_) => const FramesScreen()),
];

class SpockFlutterSample extends StatelessWidget {
  const SpockFlutterSample({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Spock Flutter Sample',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const HubScreen(),
      // Deep links arrive here too: spockflutter://open/item/42 → '/item/42'.
      onGenerateRoute: (settings) => generateRoute(settings),
    );
  }
}

Route<dynamic>? generateRoute(RouteSettings settings) {
  final uri = Uri.parse(settings.name ?? '/');
  final segments = uri.pathSegments;
  if (uri.path == '/nav/a' || uri.path == '/nav/a/b') {
    return MaterialPageRoute(settings: settings, builder: (_) => NavigationStep(name: uri.path));
  }
  if (segments.length == 2 && segments.first == 'item') {
    return MaterialPageRoute(
      settings: settings,
      builder: (_) => ItemDetailScreen(id: segments[1], ref: uri.queryParameters['ref']),
    );
  }
  // A cold-start deep link to /item/42 is expanded to the initial routes /, /item, /item/42,
  // so /item must resolve too: the stack is then Hub → Items → Item 42.
  if (uri.path == '/item' || uri.path == '/items') {
    return MaterialPageRoute(settings: settings, builder: (_) => const ItemListScreen());
  }
  for (final fixture in fixtures) {
    if (uri.path == fixture.route) {
      return MaterialPageRoute(settings: settings, builder: fixture.builder);
    }
  }
  return MaterialPageRoute(settings: settings, builder: (_) => UnknownRouteScreen(name: settings.name));
}

class HubScreen extends StatelessWidget {
  const HubScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Spock Flutter Sample')),
      body: ListView(
        children: [
          for (final fixture in fixtures)
            Semantics(
              identifier: 'hub${fixture.route.replaceAll('/', '_')}',
              child: ListTile(
                title: Text(fixture.title),
                subtitle: Text(fixture.forFeature),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.of(context).pushNamed(fixture.route),
              ),
            ),
        ],
      ),
    );
  }
}

class UnknownRouteScreen extends StatelessWidget {
  const UnknownRouteScreen({super.key, required this.name});

  final String? name;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('No such route')),
      body: Center(child: Text('Nothing matches "$name"')),
    );
  }
}
