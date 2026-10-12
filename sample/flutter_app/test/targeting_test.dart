import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spock_flutter_sample/main.dart';

/// The semantics Spock's element tools and audit read on the *Element targeting* screen (#182).
void main() {
  Future<void> openTargeting(WidgetTester tester) async {
    await tester.pumpWidget(const SpockFlutterSample());
    await tester.scrollUntilVisible(find.text('Element targeting'), 100);
    await tester.tap(find.text('Element targeting'));
    await tester.pumpAndSettle();
  }

  testWidgets('the icon tab is named only by its Semantics label, and taps', (tester) async {
    final semantics = tester.ensureSemantics();
    await openTargeting(tester);

    expect(find.bySemanticsLabel('Suche'), findsOneWidget);
    expect(find.text('Suche'), findsNothing, reason: 'no visible text: the label is all there is');
    await tester.tap(find.bySemanticsLabel('Suche'));
    await tester.pump();
    expect(find.text('Tab Suche selected'), findsOneWidget);
    semantics.dispose();
  });

  testWidgets('two buttons share one label', (tester) async {
    await openTargeting(tester);

    expect(find.widgetWithText(OutlinedButton, 'Details'), findsNWidgets(2));
  });

  testWidgets('the badge is part of its tappable card, not a node of its own', (tester) async {
    final semantics = tester.ensureSemantics();
    await openTargeting(tester);

    final card = tester.getSemantics(find.text('GRATIS E-SIM').first);
    expect(card.label, 'GRATIS E-SIM\nTarif S');
    expect(card.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
    semantics.dispose();
  });

  testWidgets('the offer banner does not scroll by touch', (tester) async {
    await openTargeting(tester);

    final banner = tester.widget<PageView>(find.byType(PageView));
    expect(banner.physics, isA<NeverScrollableScrollPhysics>());
    for (final tariff in ['Tarif M', 'Tarif L', 'Tarif XL']) {
      expect(find.text(tariff), findsOneWidget);
    }
  });

  testWidgets('the info button is shrink-wrapped below 48dp', (tester) async {
    await openTargeting(tester);

    final size = tester.getSize(find.byTooltip('Roaming info'));
    expect(size.width, lessThan(48));
    expect(size.height, lessThan(48));
  });

  testWidgets('the save button is found by its Semantics identifier', (tester) async {
    await openTargeting(tester);

    final wrapper = find.byWidgetPredicate(
      (widget) => widget is Semantics && widget.properties.identifier == 'targeting_save',
    );
    expect(wrapper, findsOneWidget);
    await tester.tap(find.descendant(of: wrapper, matching: find.text('Speichern')));
    await tester.pump();
    expect(find.text('Saved'), findsOneWidget);
  });
}
