package spock.adb.sample

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

val Context.userPrefs by preferencesDataStore(name = "user_prefs")

/**
 * Seeds every kind of value the Storage tab can show and edit, and reads them back on resume so an
 * edit made in the plugin shows up here:
 *  - shared_prefs/settings.xml: boolean, int, long, float, string, string set
 *  - files/datastore/user_prefs.preferences_pb: the same, plus double
 *  - shared_prefs/session.xml: a ~40 KB value and secret-looking keys, for the MCP read bounds
 *  - cache/ and code_cache/ files, for Clear Cache (only these go) versus Clear Data (all go)
 */
class StorageActivity : SampleActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Storage") {
            note("Seed, then open Spock ADB › Storage. Edit a value there and come back to see it here.")
            button("Seed SharedPreferences and DataStore") { seed() }
            button("Write big + secret prefs") { writeBigAndSecret() }
            button("Write cache files") { writeCache() }
            button("Refresh") { refresh() }
            output = output()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun seed() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("onboarding_seen", false)
            .putInt("launch_count", 3)
            .putLong("last_sync_epoch_ms", System.currentTimeMillis())
            .putFloat("font_scale", 1.15f)
            .putString("api_base_url", "https://api.staging.example.com")
            .putStringSet("enabled_flags", setOf("new_checkout", "dark_mode"))
            .apply()
        lifecycleScope.launch {
            userPrefs.edit {
                it[booleanPreferencesKey("notifications_enabled")] = true
                it[intPreferencesKey("theme_id")] = 2
                it[longPreferencesKey("user_id")] = 1_234_567_890L
                it[doublePreferencesKey("balance")] = 42.5
                it[stringPreferencesKey("display_name")] = "Spock"
                it[stringSetPreferencesKey("recent_searches")] = setOf("vulcan", "enterprise")
            }
            refresh()
        }
    }

    /**
     * shared_prefs/session.xml: one ~40 KB cached JSON value, which android_read_app_storage cuts at
     * maxValueChars, and three fake secrets it redacts until showSecrets is approved.
     */
    private fun writeBigAndSecret() {
        val feed = (1..540).joinToString(",", """{"items":[""", "]}") {
            """{"id":$it,"title":"Cached item $it","body":"Lorem ipsum dolor sit amet."}"""
        }
        getSharedPreferences("session", MODE_PRIVATE).edit()
            .putString("cached_feed", feed)
            .putString("auth_token", listOf(JWT_HEADER, JWT_PAYLOAD, "fake-signature").joinToString("."))
            .putString("refreshToken", "rt-fake-0123456789abcdef")
            .putString("user_password", "not-a-real-password")
            .putBoolean("session_active", true)
            .apply()
        output.text = "shared_prefs/session.xml: cached_feed (${feed.length} chars), auth_token, refreshToken, " +
            "user_password, session_active"
    }

    private fun writeCache() {
        File(cacheDir, "images").mkdirs()
        File(cacheDir, "images/thumb_1.bin").writeBytes(ByteArray(64 * 1024))
        File(cacheDir, "http_response.json").writeText("""{"cached":true}""")
        File(codeCacheDir, "compiled.tmp").writeText("code cache")
        File(filesDir, "keep_me.txt").writeText("Clear Cache must not delete this file.")
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE).all.toSortedMap()
            val store = userPrefs.data.first().asMap().mapKeys { it.key.name }.toSortedMap()
            output.text = buildString {
                appendLine("shared_prefs/settings.xml")
                prefs.forEach { (k, v) -> appendLine("  $k = $v") }
                appendLine("datastore/user_prefs.preferences_pb")
                store.forEach { (k, v) -> appendLine("  $k = $v") }
                appendLine("cache/: ${cacheDir.walk().count { it.isFile }} file(s)")
                appendLine("code_cache/: ${codeCacheDir.walk().count { it.isFile }} file(s)")
                appendLine("files/keep_me.txt exists: ${File(filesDir, "keep_me.txt").exists()}")
            }
        }
    }

    private companion object {
        /**
         * Assembled rather than written out whole, as in LogcatActivity: a complete JWT literal in a
         * tracked file trips secret scanners. Not a credential: the payload reads "fake-spock-token".
         */
        const val JWT_HEADER = "eyJhbGciOiJIUzI1NiJ9"
        const val JWT_PAYLOAD = "ZmFrZS1zcG9jay10b2tlbg"
    }
}
