import 'package:flutter/material.dart';

import 'common.dart';
import 'errors.dart' show nativeChannel;

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
      note: 'Show overflow → the yellow-black stripe on screen and "A RenderFlex overflowed by … '
          'pixels on the right". Debug builds report it to the VM Service as Flutter.Error only, '
          'not to logcat. Overflow with a native warning also logs one W/SpockSample line at the '
          'same moment: Diagnose should show the two together.',
      children: [
        IdButton(
          id: 'layout_toggle',
          label: _overflow ? 'Hide overflow' : 'Show overflow',
          onPressed: () => setState(() => _overflow = !_overflow),
        ),
        IdButton(
          id: 'layout_overflow_logged',
          label: 'Overflow with a native warning',
          onPressed: _overflow
              ? null
              : () {
                  nativeChannel.invokeMethod<void>('logWarning');
                  setState(() => _overflow = true);
                },
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
