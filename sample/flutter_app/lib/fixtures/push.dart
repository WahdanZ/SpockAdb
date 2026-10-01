import 'dart:io';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'common.dart';

/// Android: PushReceiver.kt stands in for Firebase Messaging's receiver and stores the last
/// message under the SharedPreferences key `flutter.last_push`, which is `last_push` here.
/// iOS simulator: `simctl push` shows a system banner once notifications are allowed; while the
/// app is in the foreground, AppDelegate.swift also stores a summary under `flutter.last_push`.
class PushScreen extends StatefulWidget {
  const PushScreen({super.key});

  @override
  State<PushScreen> createState() => _PushScreenState();
}

class _PushScreenState extends State<PushScreen> with WidgetsBindingObserver {
  String _last = 'Nothing received yet';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.reload();
    if (mounted) setState(() => _last = prefs.getString('last_push') ?? 'Nothing received yet');
  }

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Push messages',
      note: Platform.isAndroid
          ? 'Send from Spock, then Refresh. A message with a title also posts a notification.'
          : 'Allow notifications first on the Permissions screen. Send with ios_send_push while '
              'the app is in the foreground, then Refresh.',
      children: [
        IdButton(id: 'push_refresh', label: 'Refresh', onPressed: _load),
        ResultText(_last),
      ],
    );
  }
}
