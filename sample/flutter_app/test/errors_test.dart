import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spock_flutter_sample/fixtures/errors.dart';

void main() {
  test('custom onError prints one marker line and switches back', () {
    final original = FlutterError.onError;
    final printed = <String?>[];
    final originalDebugPrint = debugPrint;
    debugPrint = (message, {wrapWidth}) => printed.add(message);
    try {
      setCustomOnError(true);
      expect(customOnErrorInstalled, isTrue);
      FlutterError.onError!(FlutterErrorDetails(exception: StateError('first line\nsecond line')));
      expect(printed, ['$customOnErrorMarker: Bad state: first line']);
    } finally {
      setCustomOnError(false);
      debugPrint = originalDebugPrint;
    }
    expect(customOnErrorInstalled, isFalse);
    expect(FlutterError.onError, same(original));
  });

  testWidgets('the switch installs and removes the handler', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: ErrorsScreen()));
    final original = FlutterError.onError;
    await tester.tap(find.text('Custom FlutterError.onError'));
    await tester.pump();
    expect(customOnErrorInstalled, isTrue);
    expect(FlutterError.onError, isNot(same(original)));
    await tester.tap(find.text('Custom FlutterError.onError'));
    await tester.pump();
    expect(customOnErrorInstalled, isFalse);
    expect(FlutterError.onError, same(original));
  });
}
