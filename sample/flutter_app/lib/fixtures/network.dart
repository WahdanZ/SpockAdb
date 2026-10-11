import 'dart:io';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import 'common.dart';

/// HTTP through dart:io's HttpClient (what package:http uses on mobile), which is what the VM
/// Service's HTTP profile records. Responses go to the log as one line each.
class NetworkScreen extends StatefulWidget {
  const NetworkScreen({super.key});

  @override
  State<NetworkScreen> createState() => _NetworkScreenState();
}

class _NetworkScreenState extends State<NetworkScreen> {
  String _result = 'No request yet';

  Future<void> _get(String url) async {
    setState(() => _result = 'GET $url …');
    try {
      final response = await http.get(Uri.parse(url)).timeout(const Duration(seconds: 15));
      debugPrint('HTTP ${response.statusCode} GET $url');
      if (!mounted) return;
      setState(() => _result = 'HTTP ${response.statusCode}\n${_firstLine(response.body)}');
    } on SocketException catch (e) {
      debugPrint('HTTP failed GET $url: $e');
      if (!mounted) return;
      setState(() => _result = 'Failed: ${e.message}');
    } on Exception catch (e) {
      debugPrint('HTTP failed GET $url: $e');
      if (!mounted) return;
      setState(() => _result = 'Failed: $e');
    }
  }

  /// Kept for the screen's life and never closed: the response below is never read, and closing
  /// the client is not what this fixture is about.
  final HttpClient _undrainedClient = HttpClient();

  /// A 404 whose body is never read. dart:io marks a response finished in the HTTP profile only
  /// when its body stream is done, so this one stays "in flight" for good, with its status known.
  /// Spock must still count it as failed.
  Future<void> _getUndrained() async {
    const url = 'https://httpbin.org/status/404';
    setState(() => _result = 'GET $url, body never read …');
    try {
      final request = await _undrainedClient.getUrl(Uri.parse(url)).timeout(const Duration(seconds: 15));
      final response = await request.close().timeout(const Duration(seconds: 15));
      debugPrint('HTTP ${response.statusCode} GET $url (body never read)');
      if (!mounted) return;
      setState(() => _result = 'HTTP ${response.statusCode}, body never read');
    } on Exception catch (e) {
      debugPrint('HTTP failed GET $url: $e');
      if (!mounted) return;
      setState(() => _result = 'Failed: $e');
    }
  }

  String _firstLine(String body) => body.split('\n').first.trim();

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Network',
      note: 'Set a proxy or turn Wi-Fi off from Spock, then repeat a request.',
      children: [
        IdButton(id: 'net_ok', label: 'GET 200', onPressed: () => _get('https://httpbin.org/status/200')),
        IdButton(id: 'net_500', label: 'GET 500', onPressed: () => _get('https://httpbin.org/status/500')),
        IdButton(id: 'net_404', label: 'GET 404', onPressed: () => _get('https://httpbin.org/status/404')),
        IdButton(id: 'net_404_undrained', label: 'GET 404, body never read', onPressed: _getUndrained),
        IdButton(
          id: 'net_unknown_host',
          label: 'Unknown host',
          onPressed: () => _get('https://no-such-host.spock.invalid/'),
        ),
        ResultText(_result),
      ],
    );
  }
}
