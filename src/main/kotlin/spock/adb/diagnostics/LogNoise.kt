package spock.adb.diagnostics

/**
 * Warnings and errors Android writes whatever the app does: noise, mostly from an emulator's
 * graphics stack, that an agent should not read as the app's problem.
 *
 * Most of these are logged from inside the app's own process — the gralloc mapper and the
 * renderer run on its RenderThread, and `WindowOnBackDispatcher` is framework code in its UI
 * thread — so filtering by pid keeps them, and they used to top `likelyProblems`: 79 lines of
 * `mapper.ranchu` outranked the app's one real error because there were more of them (#182).
 *
 * They are tagged, not dropped. Each entry is narrow — a tag and a message shape — so the same tag
 * saying something else still counts, and a reader can still find them in the raw log.
 */
object LogNoise {

    /** One kind of noise: [tag] and a [message] shape, with an [example] line as logcat printed it. */
    class Entry(val tag: Regex, val message: Regex, val example: String)

    val ENTRIES: List<Entry> = listOf(
        // The emulator's gralloc mapper does not implement every standard metadata type; the
        // renderer asks for one per buffer, so it repeats on every frame.
        Entry(
            Regex("""mapper\.ranchu|gralloc\w*""", RegexOption.IGNORE_CASE),
            Regex("""getStandardMetadataImpl.*UNSUPPORTED"""),
            "E mapper.ranchu: getStandardMetadataImpl:886 failure: UNSUPPORTED: id=131: " +
                "unexpected standardMetadataType=21",
        ),
        // Android 13+ for every window of an app without android:enableOnBackInvokedCallback, on
        // devices too: a reminder to opt in to predictive back, not a fault. Two lines per window.
        Entry(
            Regex("""WindowOnBackDispatcher"""),
            Regex("""OnBackInvokedCallback is not enabled for the application"""),
            "W WindowOnBackDispatcher: OnBackInvokedCallback is not enabled for the application.",
        ),
        Entry(
            Regex("""WindowOnBackDispatcher"""),
            Regex("""Set 'android:enableOnBackInvokedCallback="true"' in the application manifest"""),
            "W WindowOnBackDispatcher: Set 'android:enableOnBackInvokedCallback=\"true\"' in the application manifest.",
        ),
        // Not here: IPCThreadState's "binder thread pool starved". On an emulator a HAL logs it
        // constantly, but from its own pid, which the app's pid filter already leaves out; logged
        // from the app's pid it is the app's own binder threads starving, which is worth seeing.
        //
        // The emulator's EGL has no preserved-swap or 10-bit configs; HWUI falls back and says so
        // once per window.
        Entry(
            Regex("""OpenGLRenderer"""),
            Regex(
                """Unable to match the desired swap behavior|""" +
                    """Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED|""" +
                    """Failed to initialize \d+-\d+ format, error = EGL_SUCCESS""",
            ),
            "E OpenGLRenderer: Unable to match the desired swap behavior.",
        ),
        // Frame timing bookkeeping in the app's RenderThread, Android 13+.
        Entry(
            Regex("""FrameEvents"""),
            Regex("""updateAcquireFence: Did not find frame"""),
            "E FrameEvents: updateAcquireFence: Did not find frame.",
        ),
        // Every app start looks for a dex metadata file next to the APK; a build from the IDE has none.
        Entry(
            Regex("""ziparchive"""),
            Regex("""Unable to open '[^']*\.dm': No such file or directory"""),
            "W ziparchive: Unable to open '/data/app/~~x==/com.example.app-y==/base.dm': No such file or directory",
        ),
    )

    /** Whether a line with [tag] and [message] is one of [ENTRIES]. */
    fun matches(tag: String, message: String): Boolean =
        ENTRIES.any { it.tag.matches(tag.trim()) && it.message.containsMatchIn(message) }
}
