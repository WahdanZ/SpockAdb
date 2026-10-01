import 'package:flutter/material.dart';

/// A fixture screen: a short note on what Spock should show, then the controls.
class FixtureScaffold extends StatelessWidget {
  const FixtureScaffold({super.key, required this.title, required this.note, required this.children});

  final String title;
  final String note;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(note, style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 12),
          ...children,
        ],
      ),
    );
  }
}

/// A button with a stable Android resource-id, so `android_tap_element` can find it.
class IdButton extends StatelessWidget {
  const IdButton({super.key, required this.id, required this.label, required this.onPressed});

  final String id;
  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Semantics(
        identifier: id,
        child: FilledButton.tonal(onPressed: onPressed, child: Text(label)),
      ),
    );
  }
}

/// Last result line, shown under the buttons.
class ResultText extends StatelessWidget {
  const ResultText(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      identifier: 'result',
      child: Padding(
        padding: const EdgeInsets.only(top: 12),
        child: SelectableText(text, style: const TextStyle(fontFamily: 'monospace')),
      ),
    );
  }
}
