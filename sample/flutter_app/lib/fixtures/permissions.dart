import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'common.dart';

/// Requests that open the system's own permission dialog — a native screen above Flutter that
/// flutter_driver cannot see, but Spock's UI tree can.
class PermissionsScreen extends StatefulWidget {
  const PermissionsScreen({super.key});

  @override
  State<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends State<PermissionsScreen> with WidgetsBindingObserver {
  static const _permissions = <String, Permission>{
    'camera': Permission.camera,
    'location': Permission.locationWhenInUse,
    'notifications': Permission.notification,
  };

  final _status = <String, PermissionStatus>{};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // Granted or revoked from Spock while the app was in the background.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    for (final entry in _permissions.entries) {
      _status[entry.key] = await entry.value.status;
    }
    if (mounted) setState(() {});
  }

  Future<void> _request(String name) async {
    _status[name] = await _permissions[name]!.request();
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Native permission dialogs',
      note: 'Request opens the system dialog. Status refreshes when the app resumes.',
      children: [
        for (final name in _permissions.keys)
          IdButton(id: 'perm_$name', label: 'Request $name', onPressed: () => _request(name)),
        ResultText(_permissions.keys.map((name) => '$name: ${_status[name]?.name ?? '…'}').join('\n')),
      ],
    );
  }
}
