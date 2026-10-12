import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart';

import 'common.dart';

// Assembled rather than written out whole: a complete JWT literal in a tracked file trips secret
// scanners. Not a credential: the payload reads "fake-spock-token".
const _jwtHeader = 'eyJhbGciOiJIUzI1NiJ9';
const _jwtPayload = 'ZmFrZS1zcG9jay10b2tlbg';

/// Writes one of every SharedPreferences value type, a Hive box and a sqflite database, plus a
/// cache file Clear Cache should remove and a support file it should keep.
///
/// On Android, SharedPreferences land in `shared_prefs/FlutterSharedPreferences.xml` with a
/// `flutter.` prefix on every key; lists and doubles are stored as encoded strings.
class StorageScreen extends StatefulWidget {
  const StorageScreen({super.key});

  @override
  State<StorageScreen> createState() => _StorageScreenState();
}

class _StorageScreenState extends State<StorageScreen> {
  String _result = 'Nothing written yet';
  bool _writing = false;

  Future<void> _write() async {
    setState(() => _writing = true);
    try {
      await _writeAll();
    } finally {
      if (mounted) setState(() => _writing = false);
    }
  }

  Future<void> _writeAll() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('username', 'spock');
    await prefs.setInt('launch_count', (prefs.getInt('launch_count') ?? 0) + 1);
    await prefs.setDouble('volume', 0.75);
    await prefs.setBool('dark_mode', true);
    await prefs.setStringList('recent_searches', ['adb', 'flutter', 'spock']);

    final docs = await getApplicationDocumentsDirectory();
    Hive.init(docs.path);
    final box = await Hive.openBox<dynamic>('settings');
    try {
      await box.putAll({'theme': 'dark', 'font_scale': 1.2, 'onboarded': true});
    } finally {
      await box.close();
    }

    final db = await openDatabase(
      p.join(await getDatabasesPath(), 'notes.db'),
      version: 1,
      onCreate: (db, _) => db.execute('CREATE TABLE notes(id INTEGER PRIMARY KEY, body TEXT, created INTEGER)'),
    );
    final int? count;
    try {
      await db.insert('notes', {'body': 'Note ${DateTime.now()}', 'created': DateTime.now().millisecondsSinceEpoch});
      count = Sqflite.firstIntValue(await db.rawQuery('SELECT COUNT(*) FROM notes'));
    } finally {
      await db.close();
    }

    final cache = File(p.join((await getTemporaryDirectory()).path, 'cached_image.bin'));
    await cache.writeAsBytes(List.filled(4096, 7));
    final keep = File(p.join((await getApplicationSupportDirectory()).path, 'must_survive_clear_cache.txt'));
    await keep.writeAsString('Clear Cache must not remove this file');

    if (!mounted) return;
    setState(() {
      _result = 'prefs: username, launch_count=${prefs.getInt('launch_count')}, volume, dark_mode, recent_searches\n'
          'hive: ${docs.path}/settings.hive\n'
          'sqflite: notes.db, $count rows\n'
          'cache: ${cache.path}\n'
          'kept: ${keep.path}';
    });
  }

  /// One ~40 KB cached JSON value, which `android_read_app_storage` cuts at `maxValueChars`, and
  /// three fake secrets it redacts until `showSecrets` is approved. All in FlutterSharedPreferences.xml.
  Future<void> _writeBigAndSecret() async {
    final prefs = await SharedPreferences.getInstance();
    final feed = jsonEncode({
      'items': [
        for (var i = 1; i <= 540; i++) {'id': i, 'title': 'Cached item $i', 'body': 'Lorem ipsum dolor sit amet.'},
      ],
    });
    await prefs.setString('cached_feed', feed);
    await prefs.setString('auth_token', [_jwtHeader, _jwtPayload, 'fake-signature'].join('.'));
    await prefs.setString('refreshToken', 'rt-fake-0123456789abcdef');
    await prefs.setString('user_password', 'not-a-real-password');
    await prefs.setBool('session_active', true);
    if (!mounted) return;
    setState(() => _result = 'prefs: cached_feed (${feed.length} chars), auth_token, refreshToken, '
        'user_password, session_active');
  }

  Future<void> _readBack() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.reload();
    final keys = prefs.getKeys().toList()..sort();
    if (!mounted) return;
    setState(() => _result = keys.map((key) => '$key = ${prefs.get(key)}').join('\n'));
  }

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Storage',
      note: 'Write, then open App Storage in Spock. Edit a value there, then Read back.',
      children: [
        IdButton(
          id: 'storage_write',
          label: _writing ? 'Writing…' : 'Write everything',
          onPressed: _writing ? null : _write,
        ),
        IdButton(id: 'storage_big_secret', label: 'Write big + secret prefs', onPressed: _writeBigAndSecret),
        IdButton(id: 'storage_read', label: 'Read back prefs', onPressed: _readBack),
        ResultText(_result),
      ],
    );
  }
}
