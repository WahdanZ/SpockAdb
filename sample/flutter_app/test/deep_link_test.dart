import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spock_flutter_sample/main.dart';

void main() {
  testWidgets('cold-start deep link opens Item 42 above the Items list', (tester) async {
    tester.binding.platformDispatcher.defaultRouteNameTestValue = '/item/42?ref=spock';
    addTearDown(tester.binding.platformDispatcher.clearDefaultRouteNameTestValue);

    await tester.pumpWidget(const SpockFlutterSample());
    await tester.pumpAndSettle();

    expect(find.text('Item 42'), findsOneWidget);
    expect(find.text('id=42 ref=spock'), findsOneWidget);

    tester.state<NavigatorState>(find.byType(Navigator)).pop();
    await tester.pumpAndSettle();

    expect(find.text('Items'), findsOneWidget);
    expect(find.text('No such route'), findsNothing);

    tester.state<NavigatorState>(find.byType(Navigator)).pop();
    await tester.pumpAndSettle();
    expect(find.text('Spock Flutter Sample'), findsOneWidget);
  });
}
