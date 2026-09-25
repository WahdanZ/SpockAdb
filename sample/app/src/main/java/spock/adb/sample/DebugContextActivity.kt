package spock.adb.sample

import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * A checkout that goes wrong in each of the ways `android_get_debug_context` summarises, so its
 * `likelyProblems` can be checked against something known. Every button writes what it did to
 * Logcat; none needs a network or a server.
 */
class DebugContextActivity : SampleActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Debug context") {
            note(
                "Press a few of these, then call android_get_debug_context (or use the Assistant's " +
                    "Attach debugging context). Each should come back as one line in likelyProblems.",
            )
            button("Pay: server answers HTTP 500 (OkHttp log format)") {
                // What HttpLoggingInterceptor prints. The token in the query must not reach the summary.
                Log.i(OKHTTP_TAG, "--> POST https://api.sample.spock.adb/payment?session=$DEMO_SESSION")
                Log.i(OKHTTP_TAG, "<-- 500 Internal Server Error https://api.sample.spock.adb/payment?session=$DEMO_SESSION (84ms)")
                Log.e(TAG, "Payment failed; showing the retry sheet")
            }
            button("Load cart: HTTP 404 (plain log line)") {
                Log.w(TAG, "GET https://api.sample.spock.adb/cart/42 -> HTTP 404 Not Found")
            }
            button("Resolve a host that does not exist") {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            InetAddress.getByName("no-such-host.invalid")
                        } catch (e: java.net.UnknownHostException) {
                            Log.w(TAG, "Could not reach the pricing service", e)
                        }
                    }
                }
            }
            button("Same error 25 times (should be one problem, count 25)") {
                repeat(REPEATS) { Log.e(TAG, "Price cache miss for item $it") }
            }
            button("Log a bearer token (should be redacted)") {
                Log.e(TAG, "Refresh failed, Authorization: Bearer $DEMO_SESSION")
            }
            button("Crash the app (should outrank everything)") {
                throw IllegalStateException("Checkout crashed while applying a coupon")
            }
            note("An unlabelled icon button, for the ui section's accessibility count:")
            add(ImageButton(this@DebugContextActivity).apply { setImageResource(android.R.drawable.ic_menu_send) })
        }
    }

    private companion object {
        const val TAG = SampleApp.TAG
        const val OKHTTP_TAG = "okhttp.OkHttpClient"
        const val REPEATS = 25

        /** Not a credential: shaped like one so redaction has something to catch. */
        const val DEMO_SESSION = "sample-session-0123456789abcdef"
    }
}
