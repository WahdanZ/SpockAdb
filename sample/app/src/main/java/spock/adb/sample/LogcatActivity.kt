package spock.adb.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Produces the kinds of log output the Logcat tab groups, filters and hands to the Assistant:
 * every level, a burst, a caught exception with its stack trace, secrets for the redactor, and a
 * real crash.
 */
class LogcatActivity : SampleActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Logcat") {
            note("Tag: ${SampleApp.TAG}. Set Spock Logcat to App, press Live, then press these.")
            button("Play the scripted Logcat demo (scripts/demo/LogcatDemo.kt)") {
                com.example.logcatdemo.LogcatDemo.play()
            }
            button("One line at each level (V D I W E)") {
                Log.v(SampleApp.TAG, "Verbose: cache lookup for key=user_42")
                Log.d(SampleApp.TAG, "Debug: rendering 12 items")
                Log.i(SampleApp.TAG, "Info: sync finished in 340 ms")
                Log.w(SampleApp.TAG, "Warn: retrying request, attempt 2 of 3")
                Log.e(SampleApp.TAG, "Error: payment declined, code=51")
            }
            button("Burst of 200 lines") {
                repeat(200) { Log.d("${SampleApp.TAG}.Burst", "line $it of 200") }
            }
            button("Caught exception with stack trace") {
                try {
                    parseAmount("12,50€")
                } catch (e: NumberFormatException) {
                    Log.e(SampleApp.TAG, "Could not parse the amount", e)
                }
            }
            button("Log values the redactor should hide") {
                Log.i(SampleApp.TAG, "Authorization: Bearer $DEMO_JWT")
                Log.i(SampleApp.TAG, "login email=spock@example.com password=hunter2")
            }
            button("Crash the app (uncaught exception)") {
                throw IllegalStateException("Sample crash from the Logcat screen")
            }
            button("Block the main thread for 8s (ANR)") {
                Handler(Looper.getMainLooper()).post { Thread.sleep(8_000) }
            }
            heading("Noise ranking")
            note(
                "android_get_debug_context should rank the app's one error p1 and the emulator noise " +
                    "last, tagged emulatorNoise. Press both, then ask.",
            )
            button("Log emulator noise ×80") { logEmulatorNoise() }
            button("Log one app error") {
                Log.e(SampleApp.TAG, "Checkout failed: payment provider returned DECLINED for order 42")
            }
        }
    }

    /**
     * The lines Spock's noise list names (`LogNoise.kt`), with the tags and messages Android uses.
     * The mapper and back-dispatcher lines come from inside the app's own process on a real
     * emulator too; the binder line comes from a system process there, and only from here so the
     * same screen exercises every entry. Mostly errors, many times over, so that by count alone the
     * noise would outrank the app's one error.
     */
    private fun logEmulatorNoise() {
        repeat(NOISE_REPEATS) {
            Log.e(
                "mapper.ranchu",
                "getStandardMetadataImpl:886 failure: UNSUPPORTED: id=${130 + it}: unexpected standardMetadataType=21",
            )
        }
        repeat(NOISE_PAIRS) {
            Log.w("WindowOnBackDispatcher", "OnBackInvokedCallback is not enabled for the application.")
            Log.w(
                "WindowOnBackDispatcher",
                "Set 'android:enableOnBackInvokedCallback=\"true\"' in the application manifest.",
            )
        }
        repeat(NOISE_PAIRS) { Log.e("IPCThreadState", "binder thread pool (1 threads) starved for ${100 + it} ms") }
    }

    private fun parseAmount(text: String): Double = text.toDouble()

    private companion object {
        /** 50 mapper errors, 10 back-dispatcher pairs and 10 binder lines: 80 W/E noise lines in all. */
        const val NOISE_REPEATS = 50
        const val NOISE_PAIRS = 10

        /**
         * Assembled rather than written out whole, as in scripts/demo/LogcatDemo.kt.
         *
         * It has to keep the shape the Logcat redactor matches, which is what it is here to show,
         * but a complete JWT literal in a tracked file trips secret scanners. Not a credential:
         * the header says `alg: none` and the payload says it is an example.
         */
        const val JWT_HEADER = "eyJhbGciOiJub25lIn0"
        const val JWT_PAYLOAD = "eyJub3RlIjoiZXhhbXBsZS1vbmx5In0"
        val DEMO_JWT = "$JWT_HEADER.$JWT_PAYLOAD.not-a-real-signature"
    }
}
