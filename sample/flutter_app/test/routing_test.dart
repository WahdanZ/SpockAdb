import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spock_flutter_sample/fixtures/routing.dart';

/// Each navigator's page names, outermost first. Offstage tabs included: they stay mounted, which
/// is what makes these apps "several navigators" to Spock (#180).
List<List<String?>> pageNames(WidgetTester tester) => tester
    .widgetList<Navigator>(find.byType(Navigator, skipOffstage: false))
    .map((navigator) => navigator.pages.map((page) => page.name).toList())
    .toList();

void main() {
  testWidgets('go_router: a branch navigator per visited tab, and one more for the ShellRoute', (tester) async {
    await tester.pumpWidget(GoRouterSample());
    await tester.pumpAndSettle();
    expect(find.text('Current: go_router /feed'), findsOneWidget);

    await tester.tap(find.text('Push /feed/post'));
    await tester.pumpAndSettle();
    expect(find.text('Current: go_router /feed/post'), findsOneWidget);

    await tester.tap(find.text('Profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Push /profile/settings'));
    await tester.pumpAndSettle();
    expect(find.text('Current: go_router /profile/settings'), findsOneWidget);
    // The feed branch keeps its stack offstage.
    expect(find.text('Current: go_router /feed/post', skipOffstage: false), findsOneWidget);
    // The root navigator holds the shell's page, which has no name; each visited branch has its own.
    expect(pageNames(tester), [
      [null],
      ['feed', 'feed-post'],
      ['profile', 'profile-settings'],
    ]);

    await tester.tap(find.text('Feed'));
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('Go to ShellRoute /shell'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Push /shell/detail'));
    await tester.pumpAndSettle();
    expect(find.text('Current: go_router /shell/detail'), findsOneWidget);
    // Leaving the StatefulShellRoute unmounts its branches; the ShellRoute brings one navigator.
    expect(pageNames(tester), [
      [null],
      ['shell', 'shell-detail'],
    ]);
  });

  testWidgets('auto_route: a navigator per tab, and one for the nested router', (tester) async {
    await tester.pumpWidget(AutoRouteSample());
    await tester.pumpAndSettle();
    expect(find.text('Current: auto_route FeedListRoute'), findsOneWidget);

    await tester.tap(find.text('Push FeedPostRoute'));
    await tester.pumpAndSettle();
    expect(find.text('Current: auto_route FeedPostRoute'), findsOneWidget);

    await tester.tap(find.text('Profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Push ProfileSettingsRoute'));
    await tester.pumpAndSettle();
    expect(find.text('Current: auto_route ProfileSettingsRoute'), findsOneWidget);
    expect(pageNames(tester), [
      ['HomeTabsRoute'],
      ['FeedListRoute', 'FeedPostRoute'],
      ['ProfileHomeRoute', 'ProfileSettingsRoute'],
    ]);

    await tester.tap(find.text('Feed'));
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('Push NestedRoute (root)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Push NestedBRoute'));
    await tester.pumpAndSettle();
    expect(find.text('Current: auto_route NestedBRoute'), findsOneWidget);
    // The tabs stay mounted under the nested route: four navigators, one of them on screen.
    expect(pageNames(tester), [
      ['HomeTabsRoute', 'NestedRoute'],
      ['FeedListRoute'],
      ['ProfileHomeRoute', 'ProfileSettingsRoute'],
      ['NestedARoute', 'NestedBRoute'],
    ]);
  });
}
