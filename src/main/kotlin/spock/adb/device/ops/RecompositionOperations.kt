package spock.adb.device.ops

import com.android.ddmlib.IDevice
import spock.adb.CancellationSignal
import spock.adb.ShellQuote
import spock.adb.uitree.RecompositionCounts
import spock.adb.uitree.RecompositionTraceParser
import spock.adb.uitree.UiCaptureShell
import java.util.Base64

/**
 * Records how often each composable of a running app composes, for a few seconds.
 *
 * The counts come from Compose's composition tracing, the same slices Android Studio's system
 * trace shows, read over plain ADB:
 * 1. The app is asked to turn tracing on, through the `androidx.tracing.perfetto` receiver its
 *    `runtime-tracing` dependency brings in. Shell holds the DUMP permission that receiver asks for.
 * 2. `perfetto` records track events for the requested window.
 * 3. The trace is read back and counted by [RecompositionTraceParser], then deleted.
 *
 * Shared by the UI Inspector's Recompositions tab and `android_get_recomposition_counts`, so the
 * two cannot disagree about what a count is.
 *
 * Every call blocks for at least the recording's length: never on the EDT.
 */
class RecompositionOperations(
    private val device: IDevice,
    private val serial: String = device.serialNumber,
    private val cancellation: CancellationSignal = CancellationSignal.currentThread(),
) {

    /**
     * @throws RecompositionException when the app cannot be traced, saying what to change.
     * @throws spock.adb.uitree.UiCaptureException when the device is lost, a command times out,
     *   or the recording is cancelled.
     */
    fun record(packageName: String, durationMs: Long): RecompositionCounts {
        ShellQuote.requireValidComponent(packageName, "Package name")
        val duration = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        val pid = pidOf(packageName) ?: throw RecompositionException(
            "$packageName is not running. Open the screen you want to measure, then record again.",
        )
        enableTracing(packageName)
        val trace = recordTrace(duration)
        return try {
            RecompositionTraceParser.parse(trace, duration, pid)
        } catch (e: IllegalArgumentException) {
            throw RecompositionException("The trace could not be read: ${e.message}", e)
        }
    }

    private fun pidOf(packageName: String): Int? =
        shell(SHORT_TIMEOUT_SECONDS).run("pidof ${ShellQuote.quote(packageName)}")
            .trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /** Records [durationMs] of track events and returns the trace, leaving no file behind on the device. */
    private fun recordTrace(durationMs: Long): ByteArray {
        val remote = ShellQuote.quote("$TRACE_DIRECTORY/spock-recomposition-${System.currentTimeMillis()}.pftrace")
        try {
            val output = shell(durationMs / MILLIS_PER_SECOND + RECORD_SLACK_SECONDS)
                .run("echo ${ShellQuote.quote(config(durationMs))} | perfetto --txt -c - -o $remote 2>&1")
            if (!output.contains("Wrote")) {
                throw RecompositionException(
                    "Perfetto could not record a trace. It said:\n${output.trim().take(MAX_ERROR_CHARS)}",
                )
            }
            val encoded = shell(SHORT_TIMEOUT_SECONDS).run("base64 $remote")
            return try {
                Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() })
            } catch (e: IllegalArgumentException) {
                throw RecompositionException(
                    "The trace could not be read back: ${encoded.trim().take(MAX_ERROR_CHARS)}",
                    e,
                )
            }
        } finally {
            runCatching { shell(SHORT_TIMEOUT_SECONDS).run("rm -f $remote") }
        }
    }

    private fun enableTracing(packageName: String) {
        val output = shell(SHORT_TIMEOUT_SECONDS).run(
            "am broadcast -a $ENABLE_ACTION -n ${ShellQuote.quote("$packageName/$RECEIVER")}",
        )
        when (val result = TracingHandshake.parse(output)) {
            // Just turned on, the app attaches its trace writer a moment later: a recording started at
            // once missed about a third of a five-second window on an API 34 emulator, one started a
            // second later missed nothing.
            TracingHandshake.Result.ENABLED -> warmUp()
            TracingHandshake.Result.ALREADY_ENABLED -> Unit
            else -> throw RecompositionException(result.advice(packageName, output))
        }
    }

    private fun warmUp() {
        val until = System.currentTimeMillis() + WARM_UP_MS
        while (System.currentTimeMillis() < until) {
            shell(SHORT_TIMEOUT_SECONDS).throwIfCancelled("waiting for tracing to start")
            try {
                Thread.sleep(WARM_UP_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                shell(SHORT_TIMEOUT_SECONDS).throwIfCancelled("waiting for tracing to start")
                throw e
            }
        }
    }

    private fun shell(timeoutSeconds: Long) =
        UiCaptureShell(device, timeoutSeconds, serial, cancellation, what = "Recomposition recording")

    companion object {
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 30_000L
        const val DEFAULT_DURATION_MS = 5_000L

        const val ENABLE_ACTION = "androidx.tracing.perfetto.action.ENABLE_TRACING"
        const val RECEIVER = "androidx.tracing.perfetto.TracingReceiver"

        /** The one directory `perfetto` may write to on every Android version that has it. */
        private const val TRACE_DIRECTORY = "/data/misc/perfetto-traces"
        private const val SHORT_TIMEOUT_SECONDS = 15L
        private const val RECORD_SLACK_SECONDS = 20L
        internal const val WARM_UP_MS = 1_500L
        private const val WARM_UP_POLL_MS = 100L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MAX_ERROR_CHARS = 600
        private const val BUFFER_KB = 16_384

        /** One line, so it survives `echo`: Perfetto's text config does not need newlines. */
        internal fun config(durationMs: Long) =
            "buffers { size_kb: $BUFFER_KB fill_policy: RING_BUFFER } " +
                "data_sources { config { name: \"track_event\" } } " +
                "duration_ms: $durationMs"
    }
}

/** Why an app's recompositions could not be recorded, in words a developer can act on. */
class RecompositionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * The reply to the enable-tracing broadcast. The receiver answers with a result code and a JSON
 * body carrying `exitCode`; no reply at all means there is no receiver, so the app does not have
 * the tracing library.
 */
internal object TracingHandshake {

    enum class Result(private val advice: String) {
        ENABLED(""),
        ALREADY_ENABLED(""),
        NO_RECEIVER(
            "%s does not have Compose composition tracing. Add " +
                "`debugImplementation(\"androidx.compose.runtime:runtime-tracing\")` and " +
                "`debugImplementation(\"androidx.tracing:tracing-perfetto-binary:<version>\")` to the app, " +
                "reinstall it, and record again. If it has them, the package name is wrong or the app is " +
                "not installed.",
        ),
        BINARY_MISSING(
            "%s has composition tracing but not its native library. Add " +
                "`debugImplementation(\"androidx.tracing:tracing-perfetto-binary:<version>\")`, matching the " +
                "tracing-perfetto version the app already uses, reinstall it, and record again.",
        ),
        BINARY_MISMATCH(
            "%s ships a tracing-perfetto-binary whose version does not match its tracing-perfetto. " +
                "Use the same version for both, reinstall the app, and record again.",
        ),
        FAILED("%s could not turn composition tracing on."),
        ;

        fun advice(packageName: String, output: String): String = when (this) {
            FAILED ->
                advice.format(packageName) + " It said: " + output.substringAfter("Broadcast completed:").trim()
            else -> advice.format(packageName)
        }
    }

    private val RESULT_CODE = Regex("""Broadcast completed: result=(-?\d+)""")
    private val EXIT_CODE = Regex(""""exitCode"\s*:\s*(\d+)""")

    fun parse(output: String): Result {
        val code = EXIT_CODE.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: RESULT_CODE.find(output)?.groupValues?.get(1)?.toIntOrNull()
        return when (code) {
            null -> Result.FAILED
            NO_RECEIVER -> Result.NO_RECEIVER
            SUCCESS -> Result.ENABLED
            ALREADY_ENABLED -> Result.ALREADY_ENABLED
            ERROR_BINARY_MISSING -> Result.BINARY_MISSING
            ERROR_BINARY_VERSION_MISMATCH -> Result.BINARY_MISMATCH
            else -> Result.FAILED
        }
    }

    /** No receiver answered: `am` reports the broadcast's initial result, 0. */
    private const val NO_RECEIVER = 0

    // androidx.tracing.perfetto's response codes.
    private const val SUCCESS = 1
    private const val ALREADY_ENABLED = 2
    private const val ERROR_BINARY_MISSING = 11
    private const val ERROR_BINARY_VERSION_MISMATCH = 12
}
