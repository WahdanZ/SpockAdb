package com.example.logcatdemo

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A scripted, realistic log for trying out the Spock ADB Logcat tab — emitted from **the app's
 * own process**, which is what `scripts/demo-logcat.sh` cannot do.
 *
 * That difference is the whole reason this file exists. The shell script writes through the
 * `log` command, so its lines carry the shell's PID and are only visible under the `Related` and
 * `All` scopes. These lines carry the app's PID, so `App` scope, the PID/TID fields in the
 * details pane, and the `App:` line in the AI context all show the real thing.
 *
 * **Drop-in, not a dependency.** Copy this file into a sample app — any package, rename it — and
 * call it once from `MainActivity.onCreate`:
 *
 * ```kotlin
 * if (BuildConfig.DEBUG) LogcatDemo.play()
 * ```
 *
 * It is not part of the plugin and nothing in the plugin imports it. Do not ship it in a release
 * build: the point of several of these lines is that they look like credentials.
 *
 * Everything here is synthetic. The tokens are invented, the host does not exist, and no request
 * is made — [play] only writes to logcat.
 */
object LogcatDemo {

    private const val STEP_MS = 900L

    /**
     * Plays the scenario on the main thread, one step at a time.
     *
     * Posted rather than looped so the panel receives it as a live stream — a tight loop would
     * deliver the whole story inside one flush interval and show up as a single block, which is
     * the one thing a Logcat demo should not do.
     */
    fun play(handler: Handler = Handler(Looper.getMainLooper())) {
        steps().forEachIndexed { index, step ->
            handler.postDelayed(step, STEP_MS * index)
        }
    }

    /** Every line at once, for a test or a screenshot where timing does not matter. */
    fun playNow() = steps().forEach { it.run() }

    private fun steps(): List<Runnable> = listOf(
        Runnable { startup() },
        Runnable { authRequest() },
        Runnable { authResponse() },
        Runnable { offersRequest() },
        Runnable { offersResponse() },
        Runnable { noise() },
        Runnable { crash() },
    )

    // ---------------------------------------------------------------- startup

    private fun startup() {
        Log.i("MyApplication", "App initialized in 412 ms")
        Log.d("MainActivity", "Screen opened: Offers")
        Log.i("ConnectivityService", "Network available: WIFI")
    }

    // ---------------------------------------------------------------- network and JSON

    /** Headers with credentials in them: what the AI context's redaction step is for. */
    private fun authRequest() {
        Log.d("ApiClient", "--> POST https://api.example.com/v1/auth/token")
        Log.d("ApiClient", "X-Api-Key: " + DEMO_API_KEY)
        Log.d(
            "ApiClient",
            """{"grant_type":"refresh_token","refresh_token":"EXAMPLE-NOT-A-REAL-TOKEN"}""",
        )
        Log.d("ApiClient", "--> END POST (112-byte body)")
    }

    /**
     * A pretty-printed JSON body.
     *
     * One `Log.d` call with embedded newlines, because that is what an HTTP logging interceptor
     * actually does — and it is worth seeing how a multi-line record reads in the panel.
     */
    private fun authResponse() {
        Log.d("ApiClient", "<-- 200 OK https://api.example.com/v1/auth/token (287ms)")
        Log.d("ApiClient", "Set-Cookie: session=EXAMPLE-NOT-A-REAL-SESSION; Path=/; HttpOnly; Secure")
        Log.d(
            "ApiClient",
            """
            {
              "access_token": "$DEMO_JWT",
              "token_type": "Bearer",
              "expires_in": 3600,
              "scope": "offers.read profile.read"
            }
            """.trimIndent(),
        )
    }

    private fun offersRequest() {
        Log.d("OkHttp", "--> GET https://api.example.com/v1/offers?city=berlin&limit=12")
        Log.d("OkHttp", "Authorization: Bearer $DEMO_JWT")
        Log.d("OkHttp", "--> END GET")
    }

    private fun offersResponse() {
        Log.i("OkHttp", "<-- 200 OK https://api.example.com/v1/offers (432ms, 3.4 kB)")
        Log.d(
            "ApiClient",
            """
            {
              "city": "berlin",
              "count": 12,
              "offers": [
                { "id": "of_8812", "title": "Weekend city break", "price": { "amount": 149.00, "currency": "EUR" } },
                { "id": "of_8813", "title": "Museum pass",        "price": { "amount":  29.50, "currency": "EUR" } },
                { "id": "of_8814", "title": "Airport transfer",   "price": { "amount":  19.00, "currency": "EUR" } }
              ],
              "page": { "next": null, "total": 12 }
            }
            """.trimIndent(),
        )
        Log.i("OfferRepository", "Offers loaded: 12 results in 1.2s (cache miss)")
    }

    // ---------------------------------------------------------------- noise

    /** A run of identical lines, so the AI context has something to collapse. */
    private fun noise() {
        repeat(REPEATS) {
            Log.w("ImageLoader", "Bitmap allocation exceeded the soft limit, evicting the LRU entry")
        }
        Log.i("Choreographer", "Skipped 47 frames!  The application may be doing too much work on its main thread.")
        Log.w("CheckoutViewModel", "Price recalculation took 318 ms on the main thread")
    }

    // ---------------------------------------------------------------- the crash

    /**
     * A real exception, logged rather than thrown.
     *
     * `Log.e(tag, message, throwable)` prints the header and every frame beneath it as separate
     * records under one tag and one PID — the exact shape the details pane reassembles. Throwing
     * it for real would work too, and would also end the demo.
     */
    private fun crash() {
        val cause = java.io.IOException("Unexpected end of stream on https://api.example.com/v1/offers")
        val failure = IllegalStateException("Required value was null", cause)
        Log.e("AndroidRuntime", "FATAL EXCEPTION: main", failure)
    }

    private const val REPEATS = 8

    /**
     * Assembled rather than written out whole.
     *
     * The value has to keep the shape a redaction rule matches — that is what it is here to
     * demonstrate — but a complete JWT literal in a tracked file is exactly what a secret
     * scanner is for, and a false positive costs somebody a triage every time this file
     * changes. Nothing here is a credential: the header says `alg: none` and the payload says
     * so in words.
     */
    private const val JWT_HEADER = "eyJhbGciOiJub25lIn0"
    private const val JWT_PAYLOAD = "eyJub3RlIjoiZXhhbXBsZS1vbmx5In0"
    private val DEMO_JWT = "$JWT_HEADER.$JWT_PAYLOAD.not-a-real-signature"

    private const val DEMO_API_KEY = "EXAMPLE-NOT-A-REAL-KEY"
}
