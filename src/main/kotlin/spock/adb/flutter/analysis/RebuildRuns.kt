package spock.adb.flutter.analysis

/**
 * Per widget creation location, runs of consecutive frames in which it was built, and the longest
 * run seen. A run is measured in time, by the frames' `startTime`, so a 120 Hz display does not
 * reach a threshold in half the time a 60 Hz one does.
 *
 * Frames are posted only when one is rendered, so two frames a location was built in can be
 * seconds apart with nothing in between — an idle app tapped twice. A gap longer than
 * [MAX_FRAME_GAP_MS] between frames therefore ends every run.
 *
 * Not thread-safe: [RebuildTracker] holds its lock around every call.
 */
internal class RebuildRuns {

    /**
     * @param startUs the first frame's engine-monotonic start, null when the app sent none.
     * @param frames how many frames in a row.
     */
    data class Run(val startUs: Long?, val lastUs: Long?, val frames: Int) {
        /** First to last frame start; null when the frames carried no start time. */
        val spanMs: Long? get() = if (startUs != null && lastUs != null) (lastUs - startUs) / MICROS_PER_MILLI else null

        /** Long enough to report: [MIN_RUN_MS] of frames, or [MIN_RUN_FRAMES] when untimed. */
        val sustained: Boolean get() = spanMs?.let { it >= MIN_RUN_MS } ?: (frames >= MIN_RUN_FRAMES)
    }

    private val current = mutableMapOf<Int, Run>()
    private val longest = mutableMapOf<Int, Run>()

    /** One frame: the locations in [built] extend or start a run; every other run ends. */
    fun frame(startUs: Long?, built: Set<Int>) {
        current.keys.retainAll(built)
        built.forEach { id ->
            val run = current[id]
            val next = if (run != null && continuous(run.lastUs, startUs)) {
                run.copy(lastUs = startUs ?: run.lastUs, frames = run.frames + 1)
            } else {
                Run(startUs, startUs, 1)
            }
            current[id] = next
            if (longest[id]?.let { LONGER.compare(next, it) > 0 } != false) longest[id] = next
        }
    }

    fun longest(id: Int): Run? = longest[id]

    fun clear() {
        current.clear()
        longest.clear()
    }

    private fun continuous(lastUs: Long?, startUs: Long?): Boolean =
        lastUs == null || startUs == null || startUs - lastUs <= MAX_FRAME_GAP_MS * MICROS_PER_MILLI

    companion object {
        /**
         * How long a location must be built in every frame to be reported: a second.
         *
         * A frame's count cannot tell a rebuild from a first build: the inspector's hook ignores
         * `builtOnce`, and counts per creation location, not per element (Flutter 3.22,
         * `_onRebuildWidget`). So scrolling a `ListView.builder` counts each item that scrolls in
         * at its `itemBuilder`'s location, and a burst of new items can average one or more per
         * frame. What a first build cannot do is repeat in **every** frame for a second: that
         * takes something that ticks — an animation, a stream, a `setState` in a listener, a
         * parent recreating the widget — or a scroll fast enough to bring a new item in on each
         * frame for a whole second.
         *
         * Not validated on a device yet (plan H3): the sample's Frames screen has the cases.
         */
        const val MIN_RUN_MS = 1_000L

        /** When the frames carry no `startTime`: a second at 60 Hz. */
        const val MIN_RUN_FRAMES = 60

        /**
         * Frames further apart than this are not "every frame": the app went idle between them. A
         * quarter second also covers a debug build's slow frames on an emulator.
         */
        const val MAX_FRAME_GAP_MS = 250L

        private const val MICROS_PER_MILLI = 1000L

        private val LONGER: Comparator<Run> = compareBy<Run> { it.spanMs ?: -1 }.thenBy { it.frames }
    }
}
