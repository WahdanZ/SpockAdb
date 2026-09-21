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
 *  - cache/ and code_cache/ files, for Clear Cache (only these go) versus Clear Data (all go)
 */
class StorageActivity : SampleActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Storage") {
            note("Seed, then open the plugin's Storage tab. Edit a value there and come back to see it here.")
            button("Seed SharedPreferences and DataStore") { seed() }
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
}
