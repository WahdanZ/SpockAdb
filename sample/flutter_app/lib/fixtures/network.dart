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
      setState(() => _result = 'HTTP ${response.statusCode}\n${_firstLine(response.body)}');
    } on SocketException catch (e) {
      debugPrint('HTTP failed GET $url: $e');
      setState(() => _result = 'Failed: ${e.message}');
    } on Exception catch (e) {
      debugPrint('HTTP failed GET $url: $e');
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
