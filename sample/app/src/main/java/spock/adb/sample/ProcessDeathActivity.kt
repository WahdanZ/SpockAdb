package spock.adb.sample

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel

/**
 * Three counters kept three ways. After a plugin action, which ones survived tells you what
 * happened:
 *  - Don't keep activities / rotation: the plain field resets; the ViewModel and saved state survive
 *    rotation, and only saved state survives Don't keep activities.
 *  - Process death (the plugin's action, or `am kill`): only saved state survives, and the pid changes.
 *  - Force stop / Restart / Clear data: everything resets.
 */
class ProcessDeathActivity : AppCompatActivity() {

    class Counters : ViewModel() {
        var viewModelCount = 0
    }

    private val counters: Counters by viewModels()
    private var plainCount = 0
    private var savedCount = 0
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedCount = savedInstanceState?.getInt(KEY_SAVED) ?: 0
        val restored = savedInstanceState != null
        screen("Process death") {
            note("Press Count a few times, send the app to the background, then use the plugin's Process Death, Don't Keep Activities or Restart.")
            output = output()
            button("Count") {
                plainCount++
                counters.viewModelCount++
                savedCount++
                render(restored)
            }
        }
        render(restored)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SAVED, savedCount)
    }

    private fun render(restored: Boolean) {
        val uptime = (SystemClock.elapsedRealtime() - SampleApp.processStartedAt) / 1000
        output.text = """
            pid:              ${Process.myPid()} (up ${uptime}s)
            restored state:   $restored
            plain field:      $plainCount
            ViewModel:        ${counters.viewModelCount}
            savedInstanceState: $savedCount
        """.trimIndent()
    }

    private companion object {
        const val KEY_SAVED = "saved_count"
    }
}
