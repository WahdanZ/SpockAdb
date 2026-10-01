import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import 'common.dart';

/// Slow frames and a rebuild storm. Frame times mean something only in a profile build
/// (`flutter run --profile`); a debug build is slow everywhere.
class FramesScreen extends StatefulWidget {
  const FramesScreen({super.key});

  @override
  State<FramesScreen> createState() => _FramesScreenState();
}

class _FramesScreenState extends State<FramesScreen> {
  bool _slowFrames = false;
  bool _rebuildStorm = false;

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Frames and rebuilds',
      note: 'Slow frames: every frame busy-waits 40 ms. Rebuild storm: a counter rebuilds every frame.',
      children: [
        IdButton(
          id: 'frames_slow',
          label: _slowFrames ? 'Stop slow frames' : 'Start slow frames',
          onPressed: () => setState(() => _slowFrames = !_slowFrames),
        ),
        IdButton(
          id: 'frames_rebuild_storm',
          label: _rebuildStorm ? 'Stop rebuild storm' : 'Start rebuild storm',
          onPressed: () => setState(() => _rebuildStorm = !_rebuildStorm),
        ),
        if (_slowFrames) const _SlowSpinner(),
        if (_rebuildStorm) const _RebuildStorm(),
      ],
    );
  }
}

class _SlowSpinner extends StatefulWidget {
  const _SlowSpinner();

  @override
  State<_SlowSpinner> createState() => _SlowSpinnerState();
}

class _SlowSpinnerState extends State<_SlowSpinner> with SingleTickerProviderStateMixin {
  late final AnimationController _controller =
      AnimationController(vsync: this, duration: const Duration(seconds: 1))..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        final stopwatch = Stopwatch()..start();
        while (stopwatch.elapsedMilliseconds < 40) {}
        return Transform.rotate(angle: _controller.value * 6.28, child: const Icon(Icons.refresh, size: 64));
      },
    );
  }
}

class _RebuildStorm extends StatefulWidget {
  const _RebuildStorm();

  @override
  State<_RebuildStorm> createState() => _RebuildStormState();
}

class _RebuildStormState extends State<_RebuildStorm> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  int _count = 0;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker((_) => setState(() => _count++))..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ResultText('Rebuilt $_count times');
}
