package spock.adb.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Every screen but the hub: shows an Up arrow in the toolbar that does what system Back does.
 *
 * Up goes through the back dispatcher rather than a parent activity, so a screen that handles
 * Back itself — the NavHostFragment popping its fragment back stack — handles Up the same way.
 */
abstract class SampleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
