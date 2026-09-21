package spock.adb.sample

import android.content.Intent
import android.os.Bundle

/**
 * Builds a back stack you can read with Current Activity and Activity Stack: each press adds
 * another instance, numbered, and one button starts a second task.
 */
class StackActivity : SampleActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val depth = intent.getIntExtra(EXTRA_DEPTH, 1)
        screen("Stack #$depth") {
            note("Activity Stack should list $depth StackActivity instance(s) above MainActivity.")
            button("Push another StackActivity") {
                startActivity(Intent(this@StackActivity, StackActivity::class.java).putExtra(EXTRA_DEPTH, depth + 1))
            }
            button("Start SeparateTaskActivity (singleTask, own affinity)") {
                startActivity(Intent(this@StackActivity, SeparateTaskActivity::class.java))
            }
        }
    }

    companion object {
        const val EXTRA_DEPTH = "depth"
    }
}

/** Lives in its own task, so the stack view has two tasks to show. */
class SeparateTaskActivity : SampleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Separate task") {
            note("This activity runs in task affinity spock.adb.sample.separate.")
        }
    }
}
