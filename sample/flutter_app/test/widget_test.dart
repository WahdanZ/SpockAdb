import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spock_flutter_sample/fixtures/routes.dart';
import 'package:spock_flutter_sample/main.dart';

void main() {
  testWidgets('hub lists every fixture', (tester) async {
    await tester.pumpWidget(const SpockFlutterSample());
    for (final fixture in fixtures) {
      await tester.scrollUntilVisible(find.text(fixture.title), 100);
      expect(find.text(fixture.title), findsOneWidget);
    }
  });

  test('deep link path opens the item detail', () {
    final route = generateRoute(const RouteSettings(name: '/item/42?ref=spock')) as MaterialPageRoute;
    final screen = route.builder(_FakeContext()) as ItemDetailScreen;
    expect(screen.id, '42');
    expect(screen.ref, 'spock');
  });

  testWidgets('login accepts a valid email and password', (tester) async {
    await tester.pumpWidget(const SpockFlutterSample());
    await tester.tap(find.text('Login'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Email'), 'a@b.c');
    await tester.enterText(find.widgetWithText(TextField, 'Password'), 'secret');
    await tester.tap(find.text('Sign in'));
    await tester.pump();
    expect(find.text('Signed in as a@b.c'), findsOneWidget);
  });
}

class _FakeContext extends Fake implements BuildContext {}
