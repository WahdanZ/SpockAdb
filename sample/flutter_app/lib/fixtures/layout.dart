import 'package:flutter/material.dart';

import 'common.dart';

/// A RenderFlex overflow, reported by Flutter as a layout error each time it lays out.
class LayoutScreen extends StatefulWidget {
  const LayoutScreen({super.key});

  @override
  State<LayoutScreen> createState() => _LayoutScreenState();
}

class _LayoutScreenState extends State<LayoutScreen> {
  bool _overflow = false;

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Layout overflow',
      note: 'Show overflow → "A RenderFlex overflowed by … pixels on the right" in logs, '
          'Flutter.Error on the VM Service, and the yellow-black stripe on screen.',
      children: [
        IdButton(
          id: 'layout_toggle',
          label: _overflow ? 'Hide overflow' : 'Show overflow',
          onPressed: () => setState(() => _overflow = !_overflow),
        ),
        if (_overflow)
          const Row(
            children: [
              Icon(Icons.warning, size: 48),
              Text('This label is far too long to fit beside the icon in one row on any phone screen'),
            ],
          ),
      ],
    );
  }
}
