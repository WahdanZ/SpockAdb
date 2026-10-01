import 'dart:developer' as developer;

import 'package:flutter/material.dart';

import 'common.dart';

/// print / debugPrint land in logcat under the `flutter` tag; dart:developer `log` goes only to
/// the VM Service Logging stream. The secrets line checks redaction.
class LogsScreen extends StatelessWidget {
  const LogsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Logs',
      note: 'Logcat tag: flutter. developer.log appears only on the VM Service.',
      children: [
        IdButton(
          id: 'log_print',
          label: 'print()',
          // The fixture exists to exercise print() itself.
          // ignore: avoid_print
          onPressed: () => print('Sample print line'),
        ),
        IdButton(id: 'log_debug_print', label: 'debugPrint()', onPressed: () => debugPrint('Sample debugPrint line')),
        IdButton(
          id: 'log_developer',
          label: 'developer.log (info, warning, severe)',
          onPressed: () {
            developer.log('Sample info', name: 'spock.sample', level: 800);
            developer.log('Sample warning', name: 'spock.sample', level: 900);
            developer.log('Sample severe', name: 'spock.sample', level: 1000, error: StateError('sample'));
          },
        ),
        IdButton(
          id: 'log_burst',
          label: '200-line burst',
          onPressed: () {
            for (var i = 0; i < 200; i++) {
              debugPrint('Burst line $i');
            }
          },
        ),
        IdButton(
          id: 'log_secrets',
          label: 'Line with secrets',
          onPressed: () => debugPrint(
            'GET https://api.example.com/me?token=abc123 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.sample.sig',
          ),
        ),
      ],
    );
  }
}
