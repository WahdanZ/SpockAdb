package spock.adb.sample

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.ProxySelector
import java.net.URI
import java.net.URL

/**
 * Makes plain HTTP and HTTPS requests with the platform stack, which honours the global proxy, so
 * Set HTTP Proxy can be checked in a debugging proxy (Charles, mitmproxy, Proxyman). Also shows
 * which transport is active, for the Wi-Fi and mobile data toggles.
 */
class NetworkActivity : AppCompatActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Network") {
            note("Set a proxy from the plugin, then send a request and watch it arrive in your proxy.")
            button("GET http://example.com") { fetch("http://example.com") }
            button("GET https://example.com") { fetch("https://example.com") }
            button("Refresh connection state") { output.text = connectionState() }
            output = output(connectionState())
        }
    }

    private fun fetch(url: String) {
        output.text = "Requesting $url…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    try {
                        "HTTP ${connection.responseCode} ${connection.responseMessage}"
                    } finally {
                        connection.disconnect()
                    }
                }.getOrElse { "Failed: ${it.javaClass.simpleName}: ${it.message}" }
            }
            Log.i(SampleApp.TAG, "GET $url -> $result")
            output.text = "$url\n$result\n\n${connectionState()}"
        }
    }

    private fun connectionState(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val proxy = ProxySelector.getDefault().select(URI("http://example.com")).joinToString()
        return "active network: $transport\nproxy for http: $proxy"
    }
}
