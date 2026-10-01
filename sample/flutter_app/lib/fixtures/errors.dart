import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'common.dart';

/// The native side of [nativeChannel] lives in MainActivity.kt and AppDelegate.swift.
const nativeChannel = MethodChannel('spock.sample/native');

/// Never registered on either platform, so every call is a MissingPluginException.
const missingChannel = MethodChannel('spock.sample/not_registered');

/// Each button produces one kind of failure, left uncaught so it reaches the logs the way it
/// would in a real app.
class ErrorsScreen extends StatefulWidget {
  const ErrorsScreen({super.key});

  @override
  State<ErrorsScreen> createState() => _ErrorsScreenState();
}

class _ErrorsScreenState extends State<ErrorsScreen> {
  bool _brokenBuild = false;

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Errors and plugin failures',
      note: 'Each button logs one failure. "Native crash" kills the app on purpose.',
      children: [
        IdButton(
          id: 'error_gesture',
          label: 'Throw in a tap handler',
          onPressed: () => throw StateError('Sample error thrown in a tap handler'),
        ),
        IdButton(
          id: 'error_async',
          label: 'Unhandled async exception',
          onPressed: () => Future<void>(() => throw const FormatException('Sample unhandled async error')),
        ),
        IdButton(
          id: 'error_missing_plugin',
          label: 'MissingPluginException',
          onPressed: () => missingChannel.invokeMethod<void>('ping'),
        ),
        IdButton(
          id: 'error_platform',
          label: 'PlatformException from native',
          onPressed: () => nativeChannel.invokeMethod<void>('platformError'),
        ),
        IdButton(
          id: 'error_channel_handler',
          label: 'Exception in a channel handler',
          onPressed: () => nativeChannel.invokeMethod<void>('throwInHandler'),
        ),
        IdButton(
          id: 'error_build',
          label: _brokenBuild ? 'Fix the broken build' : 'Break build() (red screen)',
          onPressed: () => setState(() => _brokenBuild = !_brokenBuild),
        ),
        if (_brokenBuild) const _ThrowsInBuild(),
        IdButton(
          id: 'error_native_crash',
          label: 'Native crash',
          onPressed: () => nativeChannel.invokeMethod<void>('crash'),
        ),
      ],
    );
  }
}

class _ThrowsInBuild extends StatelessWidget {
  const _ThrowsInBuild();

  @override
  Widget build(BuildContext context) => throw ArgumentError('Sample error thrown in build()');
}
