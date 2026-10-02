import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spock_flutter_sample/main.dart';

void main() {
  testWidgets('rebuild storm counts frames and leaves the screen cleanly', (tester) async {
    await tester.pumpWidget(const SpockFlutterSample());
    await tester.scrollUntilVisible(find.text('Frames and rebuilds'), 100);
    await tester.tap(find.text('Frames and rebuilds'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Start rebuild storm'));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }

    final counter = tester.widget<Text>(find.textContaining('Rebuilt ')).data!;
    final count = int.parse(RegExp(r'Rebuilt (\d+) times').firstMatch(counter)!.group(1)!);
    expect(count, greaterThan(0));

    tester.state<NavigatorState>(find.byType(Navigator)).pop();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('Spock Flutter Sample'), findsOneWidget);
  });

  testWidgets('the looping rotation turns until switched off', (tester) async {
    await tester.pumpWidget(const SpockFlutterSample());
    await tester.scrollUntilVisible(find.text('Frames and rebuilds'), 100);
    await tester.tap(find.text('Frames and rebuilds'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Start looping rotation'));
    await tester.pump();
    final rotation = find.ancestor(of: find.byIcon(Icons.autorenew), matching: find.byType(RotationTransition));
    final before = tester.widget<RotationTransition>(rotation).turns.value;
    await tester.pump(const Duration(milliseconds: 500));
    expect(tester.widget<RotationTransition>(rotation).turns.value, isNot(before));
    expect(tester.binding.hasScheduledFrame, isTrue);

    await tester.tap(find.text('Stop looping rotation'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.autorenew), findsNothing);
  });

  testWidgets('indeterminate progress animates until switched off', (tester) async {
    await tester.pumpWidget(const SpockFlutterSample());
    await tester.scrollUntilVisible(find.text('Frames and rebuilds'), 100);
    await tester.tap(find.text('Frames and rebuilds'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Start indeterminate progress'));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    // Still animating: it schedules frames for as long as it is shown.
    await tester.pump(const Duration(milliseconds: 100));
    expect(tester.binding.hasScheduledFrame, isTrue);

    await tester.tap(find.text('Stop indeterminate progress'));
    await tester.pumpAndSettle();
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });
}
