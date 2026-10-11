import 'package:auto_route/auto_route.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart' hide RouteData;

/// Routing packages that mount several navigators (#180): auto_route's nested routers and tabs,
/// go_router's shells. Each replaces the sample's root with its own app, as the Navigator.pages
/// fixture does, so the navigators listed are the package's and nothing of the hub's.
/// Restart the sample to return to the hub.
class RoutingPackagesScreen extends StatelessWidget {
  const RoutingPackagesScreen({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Routing packages')),
        body: ListView(children: [
          TextButton(
            onPressed: () => runApp(GoRouterSample()),
            child: const Text('go_router: StatefulShellRoute + ShellRoute'),
          ),
          TextButton(
            onPressed: () => runApp(AutoRouteSample()),
            child: const Text('auto_route: AutoTabsRouter + nested AutoRouter'),
          ),
        ]),
      );
}

// ---------------------------------------------------------------------------------- go_router

/// Two shells: `StatefulShellRoute.indexedStack` keeps one navigator per branch alive, the
/// inactive one offstage; `ShellRoute` adds one navigator around its routes. Every route has a
/// `name`, which go_router puts in its page's `name`.
class GoRouterSample extends StatelessWidget {
  GoRouterSample({super.key});

  final GoRouter _router = GoRouter(
    initialLocation: '/feed',
    routes: [
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => Scaffold(
          body: shell,
          bottomNavigationBar: NavigationBar(
            selectedIndex: shell.currentIndex,
            onDestinationSelected: shell.goBranch,
            destinations: const [
              NavigationDestination(icon: Icon(Icons.list), label: 'Feed'),
              NavigationDestination(icon: Icon(Icons.person), label: 'Profile'),
            ],
          ),
        ),
        branches: [
          StatefulShellBranch(routes: [
            GoRoute(
              path: '/feed',
              name: 'feed',
              builder: (context, state) => _Step(
                title: 'go_router /feed',
                actions: {
                  'Push /feed/post': () => context.pushNamed('feed-post'),
                  'Go to ShellRoute /shell': () => context.go('/shell'),
                },
              ),
              routes: [
                GoRoute(
                  path: 'post',
                  name: 'feed-post',
                  builder: (context, state) => const _Step(title: 'go_router /feed/post'),
                ),
              ],
            ),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(
              path: '/profile',
              name: 'profile',
              builder: (context, state) => _Step(
                title: 'go_router /profile',
                actions: {'Push /profile/settings': () => context.pushNamed('profile-settings')},
              ),
              routes: [
                GoRoute(
                  path: 'settings',
                  name: 'profile-settings',
                  builder: (context, state) => const _Step(title: 'go_router /profile/settings'),
                ),
              ],
            ),
          ]),
        ],
      ),
      ShellRoute(
        builder: (context, state, child) => Scaffold(
          appBar: AppBar(title: const Text('ShellRoute')),
          body: child,
        ),
        routes: [
          GoRoute(
            path: '/shell',
            name: 'shell',
            builder: (context, state) => _Step(
              title: 'go_router /shell',
              actions: {
                'Push /shell/detail': () => context.pushNamed('shell-detail'),
                'Back to the tabs /feed': () => context.go('/feed'),
              },
            ),
            routes: [
              GoRoute(
                path: 'detail',
                name: 'shell-detail',
                builder: (context, state) => const _Step(title: 'go_router /shell/detail'),
              ),
            ],
          ),
        ],
      ),
    ],
  );

  @override
  Widget build(BuildContext context) => MaterialApp.router(routerConfig: _router);
}

// --------------------------------------------------------------------------------- auto_route

/// Routes declared by hand, so the sample needs no code generation: a [PageInfo] name per page,
/// and [_SampleAutoRouter.pagesMap] building each one, as generated code would. auto_route puts
/// the name in each page's `name`.
const _homeTabs = PageInfo('HomeTabsRoute');
const _feedTab = PageInfo('FeedTabRoute');
const _profileTab = PageInfo('ProfileTabRoute');
const _feedList = PageInfo('FeedListRoute');
const _feedPost = PageInfo('FeedPostRoute');
const _profileHome = PageInfo('ProfileHomeRoute');
const _profileSettings = PageInfo('ProfileSettingsRoute');
const _nested = PageInfo('NestedRoute');
const _nestedA = PageInfo('NestedARoute');
const _nestedB = PageInfo('NestedBRoute');

/// Root stack → tabs (one stack per tab, each its own navigator) and a nested router page.
class _SampleAutoRouter extends RootStackRouter {
  @override
  List<AutoRoute> get routes => [
        AutoRoute(page: _homeTabs, path: '/', initial: true, children: [
          AutoRoute(page: _feedTab, path: 'feed', initial: true, children: [
            AutoRoute(page: _feedList, path: '', initial: true),
            AutoRoute(page: _feedPost, path: 'post'),
          ]),
          AutoRoute(page: _profileTab, path: 'profile', children: [
            AutoRoute(page: _profileHome, path: '', initial: true),
            AutoRoute(page: _profileSettings, path: 'settings'),
          ]),
        ]),
        AutoRoute(page: _nested, path: '/nested', children: [
          AutoRoute(page: _nestedA, path: '', initial: true),
          AutoRoute(page: _nestedB, path: 'b'),
        ]),
      ];

  @override
  Map<String, PageFactory> get pagesMap => {
        _homeTabs.name: (data) => _page(data, (_) => const _AutoTabs()),
        _feedTab.name: (data) => _page(data, (_) => const AutoRouter()),
        _profileTab.name: (data) => _page(data, (_) => const AutoRouter()),
        _feedList.name: (data) => _page(
              data,
              (context) => _Step(
                title: 'auto_route FeedListRoute',
                actions: {
                  'Push FeedPostRoute': () => context.router.push(PageRouteInfo(_feedPost.name)),
                  'Push NestedRoute (root)': () => context.router.root.push(PageRouteInfo(_nested.name)),
                },
              ),
            ),
        _feedPost.name: (data) => _page(data, (_) => const _Step(title: 'auto_route FeedPostRoute')),
        _profileHome.name: (data) => _page(
              data,
              (context) => _Step(
                title: 'auto_route ProfileHomeRoute',
                actions: {
                  'Push ProfileSettingsRoute': () => context.router.push(PageRouteInfo(_profileSettings.name)),
                },
              ),
            ),
        _profileSettings.name: (data) =>
            _page(data, (_) => const _Step(title: 'auto_route ProfileSettingsRoute')),
        _nested.name: (data) => _page(
              data,
              (_) => Scaffold(appBar: AppBar(title: const Text('Nested AutoRouter')), body: const AutoRouter()),
            ),
        _nestedA.name: (data) => _page(
              data,
              (context) => _Step(
                title: 'auto_route NestedARoute',
                actions: {'Push NestedBRoute': () => context.router.push(PageRouteInfo(_nestedB.name))},
              ),
            ),
        _nestedB.name: (data) => _page(data, (_) => const _Step(title: 'auto_route NestedBRoute')),
      };

  static AutoRoutePage<dynamic> _page(RouteData data, WidgetBuilder builder) =>
      AutoRoutePage<dynamic>(routeData: data, child: Builder(builder: builder));
}

class AutoRouteSample extends StatelessWidget {
  AutoRouteSample({super.key});

  final _router = _SampleAutoRouter();

  @override
  Widget build(BuildContext context) => MaterialApp.router(routerConfig: _router.config());
}

class _AutoTabs extends StatelessWidget {
  const _AutoTabs();

  @override
  Widget build(BuildContext context) => AutoTabsRouter(
        routes: [PageRouteInfo(_feedTab.name), PageRouteInfo(_profileTab.name)],
        builder: (context, child) {
          final tabs = AutoTabsRouter.of(context);
          return Scaffold(
            body: child,
            bottomNavigationBar: NavigationBar(
              selectedIndex: tabs.activeIndex,
              onDestinationSelected: tabs.setActiveIndex,
              destinations: const [
                NavigationDestination(icon: Icon(Icons.list), label: 'Feed'),
                NavigationDestination(icon: Icon(Icons.person), label: 'Profile'),
              ],
            ),
          );
        },
      );
}

// ------------------------------------------------------------------------------------- shared

/// One screen of a fixture: its route as the title and body, and the buttons that move on.
class _Step extends StatelessWidget {
  const _Step({required this.title, this.actions = const {}});
  final String title;
  final Map<String, VoidCallback> actions;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(title), automaticallyImplyLeading: Navigator.of(context).canPop()),
        body: ListView(children: [
          Padding(padding: const EdgeInsets.all(16), child: Text('Current: $title')),
          for (final entry in actions.entries) TextButton(onPressed: entry.value, child: Text(entry.key)),
        ]),
      );
}
