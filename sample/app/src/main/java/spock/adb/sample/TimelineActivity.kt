package spock.adb.sample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import spock.adb.sample.fragments.NavigationActivity

/**
 * Makes the events the Debug Timeline records, so each row kind can be seen arrive: activity
 * lifecycle, fragments after a resume, the app's warnings and errors (a stack trace folded into one
 * row), a crash with the process dying, and a scripted "bug" whose lead-up the timeline should show
 * in order.
 */
class TimelineActivity : SampleActivity() {

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Debug Timeline") {
            note(
                "Select \"$packageName\" in the tool window header and open the Timeline tab. Opening this " +
                    "screen is already on it: TimelineActivity created, started, resumed.",
            )
            heading("One event each")
            button("Log a warning") { Log.w(TAG, "Cart total is stale, recalculating") }
            button("Log an error with a stack trace (one row, trace in the detail)") {
                try {
                    error("Coupon SAVE10 expired at checkout")
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Could not apply the coupon", e)
                }
            }
            button("Open another activity (paused, stopped, then resumed on Back)") {
                startActivity(Intent(this@TimelineActivity, StackActivity::class.java))
            }
            button("Open the fragments screen (a Fragments row after it resumes)") {
                startActivity(Intent(this@TimelineActivity, NavigationActivity::class.java))
            }

            heading("A bug to reproduce")
            note(
                "Press this, wait for the crash, reopen the app, then select from the warning to the crash in " +
                    "the Timeline and press Copy Range — or ask an agent for android_get_debug_timeline.",
            )
            button("Warn, open a screen, then crash 3 s later") {
                Log.w(TAG, "Checkout started with an empty address")
                startActivity(Intent(this@TimelineActivity, StackActivity::class.java))
                main.postDelayed({ throw IllegalStateException("Checkout crashed: address was null") }, CRASH_DELAY_MS)
            }
        }
    }

    private companion object {
        const val TAG = "${SampleApp.TAG}.Timeline"
        const val CRASH_DELAY_MS = 3_000L
    }
}
