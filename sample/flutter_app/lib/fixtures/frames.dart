import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import 'common.dart';

/// Slow frames, a rebuild storm, and animations that must not read as one. Frame times mean
/// something only in a profile build (`flutter run --profile`); a debug build is slow everywhere.
///
/// Rebuilds expected in H3 (plan): the storm is reported with no hint. The looping rotation and
/// the slow spinner's `AnimatedBuilder` rebuild every frame by design: reported as INFO with "expected
/// if this widget animates continuously", never as a warning. The indeterminate progress indicator
/// redraws inside Flutter's own widgets, which the inspector's counts leave out: nothing at all.
/// Scrolling *List → detail routes* (500 items) should not be reported either.
class FramesScreen extends StatefulWidget {
  const FramesScreen({super.key});

  @override
  State<FramesScreen> createState() => _FramesScreenState();
}

class _FramesScreenState extends State<FramesScreen> {
  bool _slowFrames = false;
  bool _rebuildStorm = false;
  bool _progress = false;
  bool _rotation = false;

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Frames and rebuilds',
      note: 'Slow frames: every frame busy-waits 40 ms. Rebuild storm: a counter rebuilds every frame. '
          'Looping rotation: an animation rebuilt every frame on purpose. '
          'Indeterminate progress: animates every frame and is not a rebuild storm.',
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
        IdButton(
          id: 'frames_rotation',
          label: _rotation ? 'Stop looping rotation' : 'Start looping rotation',
          onPressed: () => setState(() => _rotation = !_rotation),
        ),
        IdButton(
          id: 'frames_progress',
          label: _progress ? 'Stop indeterminate progress' : 'Start indeterminate progress',
          onPressed: () => setState(() => _progress = !_progress),
        ),
        if (_slowFrames) const _SlowSpinner(),
        if (_rebuildStorm) const _RebuildStorm(),
        if (_rotation) const _LoopingRotation(),
        if (_progress) const Center(child: CircularProgressIndicator()),
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

/// An animation that loops on purpose, in the app's own code and with no busy-wait: the
/// `RotationTransition` is rebuilt on every tick, like the storm, and should be reported with the
/// hint that it is expected while it animates — never as a warning. Its child is `const`, so
/// nothing under it is rebuilt.
class _LoopingRotation extends StatefulWidget {
  const _LoopingRotation();

  @override
  State<_LoopingRotation> createState() => _LoopingRotationState();
}

class _LoopingRotationState extends State<_LoopingRotation> with SingleTickerProviderStateMixin {
  late final AnimationController _controller =
      AnimationController(vsync: this, duration: const Duration(seconds: 2))..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Center(
      child: RotationTransition(turns: _controller, child: const Icon(Icons.autorenew, size: 64)),
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
