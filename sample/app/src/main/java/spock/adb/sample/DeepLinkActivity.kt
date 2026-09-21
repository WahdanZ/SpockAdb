package spock.adb.sample

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows exactly what a deep link delivered, so Open Deep Link's report can be checked against
 * what the app received.
 *
 * Try:
 *  - spocksample://open/item/42?ref=spock
 *  - https://sample.spock.adb/item/7
 *  - spocksample://nav/detail/99   (lands in the Navigation fragment stack instead)
 *  - spocksample://nowhere         (matches no filter: the plugin should say nothing handled it)
 */
class DeepLinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val uri = intent.data
        Log.i(SampleApp.TAG, "Deep link received: $uri")
        screen("Deep link") {
            note("Open a link with the plugin's Open Deep Link field, e.g. spocksample://open/item/42?ref=spock")
            output(
                buildString {
                    appendLine("action: ${intent.action}")
                    appendLine("uri:    ${uri ?: "(none — opened from the app)"}")
                    uri?.let {
                        appendLine("scheme: ${it.scheme}")
                        appendLine("host:   ${it.host}")
                        appendLine("path:   ${it.path}")
                        it.queryParameterNames.forEach { name -> appendLine("?$name=${it.getQueryParameter(name)}") }
                    }
                },
            )
        }
    }
}
