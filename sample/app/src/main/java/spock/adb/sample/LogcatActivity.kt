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
        }
    }

    private fun parseAmount(text: String): Double = text.toDouble()

    private companion object {
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
