import 'package:flutter/material.dart';

import 'common.dart';

/// Widgets an agent drives through Spock's existing `android_*` element tools.
///
/// Each control has `Semantics(identifier:)`, which Flutter publishes as the Android
/// resource-id. The "Remember me" switch and the "Help" text deliberately have none, so the
/// UI tree shows what an unidentified Flutter widget looks like.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _remember = false;
  String _result = 'Not signed in';

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  void _signIn() {
    setState(() {
      _result = _email.text.contains('@') && _password.text.length >= 4
          ? 'Signed in as ${_email.text}'
          : 'Invalid email or password';
    });
  }

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Login',
      note: 'Find by resource-id: login_email, login_password, login_submit, result. '
          '"Remember me" has no identifier on purpose.',
      children: [
        Semantics(
          identifier: 'login_email',
          child: TextField(controller: _email, decoration: const InputDecoration(labelText: 'Email')),
        ),
        Semantics(
          identifier: 'login_password',
          child: TextField(
            controller: _password,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Password'),
          ),
        ),
        SwitchListTile(
          title: const Text('Remember me'),
          value: _remember,
          onChanged: (value) => setState(() => _remember = value),
        ),
        IdButton(id: 'login_submit', label: 'Sign in', onPressed: _signIn),
        const Text('Help'),
        ResultText(_result),
      ],
    );
  }
}
