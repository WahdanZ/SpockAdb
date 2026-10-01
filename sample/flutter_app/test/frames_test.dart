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
}
