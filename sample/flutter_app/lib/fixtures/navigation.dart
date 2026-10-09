import 'package:flutter/material.dart';

class NavigationScreen extends StatelessWidget {
  const NavigationScreen({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Navigation')),
        body: ListView(children: [
          TextButton(
            onPressed: () => Navigator.of(context).pushNamed('/nav/a'),
            child: const Text('Push /nav/a'),
          ),
          TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (context) => AlertDialog(
                title: const Text('Unnamed DialogRoute'),
                actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
              ),
            ),
            child: const Text('Show dialog'),
          ),
          for (final keyed in [true, false])
            TextButton(
              onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                settings: RouteSettings(name: keyed ? '/nav/keyed' : '/nav/unkeyed'),
                builder: (_) => NestedNavigationScreen(keyed: keyed),
              )),
              child: Text(keyed ? 'Nested Navigator WITH key' : 'Nested Navigator WITHOUT key'),
            ),
          TextButton(
            // Replace the app's root rather than nesting another navigator: the pages
            // fixture must have exactly one mounted Navigator to prove its own stack.
            onPressed: () => runApp(const PagesNavigationSample()),
            child: const Text('Navigator.pages (restart sample to return to hub)'),
          ),
        ]),
      );
}

class NavigationStep extends StatelessWidget {
  const NavigationStep({super.key, required this.name});
  final String name;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(name)),
        body: Center(
          child: name == '/nav/a'
              ? TextButton(
                  onPressed: () => Navigator.of(context).pushNamed('/nav/a/b'),
                  child: const Text('Push /nav/a/b'),
                )
              : const Text('Current: /nav/a/b'),
        ),
      );
}

class NestedNavigationScreen extends StatefulWidget {
  const NestedNavigationScreen({super.key, required this.keyed});
  final bool keyed;

  @override
  State<NestedNavigationScreen> createState() => _NestedNavigationScreenState();
}

class _NestedNavigationScreenState extends State<NestedNavigationScreen> {
  final navigatorKey = GlobalKey<NavigatorState>();

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(widget.keyed ? 'Keyed nested navigator' : 'Unkeyed nested navigator')),
        body: Navigator(
          key: widget.keyed ? navigatorKey : null,
          onGenerateRoute: (settings) => MaterialPageRoute<void>(
            settings: const RouteSettings(name: '/nested'),
            builder: (_) => const Center(child: Text('Nested current route: /nested')),
          ),
        ),
      );
}

class PagesNavigationSample extends StatefulWidget {
  const PagesNavigationSample({super.key});

  @override
  State<PagesNavigationSample> createState() => _PagesNavigationSampleState();
}

class _PagesNavigationSampleState extends State<PagesNavigationSample> {
  final navigatorKey = GlobalKey<NavigatorState>();
  bool detail = true;

  @override
  Widget build(BuildContext context) => MaterialApp(
        // builder replaces MaterialApp's implicit navigator; only this one is mounted.
        builder: (context, child) => Navigator(
          key: navigatorKey,
          pages: [
            MaterialPage<void>(
              key: const ValueKey('list'),
              name: '/pages/list',
              child: Scaffold(
                appBar: AppBar(title: const Text('Pages list')),
                body: TextButton(
                  onPressed: () => setState(() => detail = true),
                  child: const Text('Show detail page'),
                ),
              ),
            ),
            if (detail)
              MaterialPage<void>(
                key: const ValueKey('detail'),
                name: '/pages/detail',
                child: Scaffold(
                  appBar: AppBar(title: const Text('Pages detail')),
                  body: TextButton(
                    onPressed: () => setState(() => detail = false),
                    child: const Text('Remove detail page'),
                  ),
                ),
              ),
          ],
          // Supported by both fixture SDKs; onDidRemovePage is newer than 3.22.
          // ignore: deprecated_member_use
          onPopPage: (route, result) {
            if (!route.didPop(result)) return false;
            setState(() => detail = false);
            return true;
          },
        ),
      );
}
