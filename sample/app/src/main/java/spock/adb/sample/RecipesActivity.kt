package spock.adb.sample

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import spock.adb.sample.background.SampleJobService

/**
 * A fixture for each built-in Debug Recipe (`android_run_recipe`). Every line the recipes check
 * is its own TextView, so `expectText` matches one node rather than part of a block:
 *
 *  - process_death, expectText "Saved count: 3" — press Count three times, then run it. Only
 *    savedInstanceState carries the count, and process death keeps it.
 *  - restart_keeps_state, expectText "Persisted note: saved" — press Save note first. The note is in
 *    SharedPreferences and the hub shows it too, since a restart relaunches the hub: it passes. With
 *    expectText "Saved count: 3" it fails, because a restart drops saved state — the case to try.
 *  - deep_link, uri "spocksample://recipe/42", expectActivity "RecipesActivity",
 *    expectText "Recipe item 42".
 *  - job_in_doze, jobId 4545 — press Schedule recipe job first. It waits an hour on its own, so it
 *    only runs when the recipe forces it; the run shows in logcat as "ran job 4545".
 */
class RecipesActivity : SampleActivity() {

    private var savedCount = 0
    private lateinit var countView: TextView
    private lateinit var noteView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedCount = savedInstanceState?.getInt(KEY_SAVED) ?: 0
        val item = intent?.data?.lastPathSegment

        screen("Debug Recipes") {
            note("Fixtures for android_run_recipe. Each recipe's parameters are in this class's KDoc.")

            heading("Deep link")
            output(if (item != null) "Recipe item $item" else "Opened without a deep link")

            heading("process_death / restart_keeps_state")
            output(if (savedInstanceState != null) "Restored from saved state" else "Fresh start")
            countView = output()
            button("Count") {
                savedCount++
                render()
            }
            noteView = output()
            button("Save note") {
                prefs().edit().putString(KEY_NOTE, "saved").apply()
                render()
            }
            button("Clear note") {
                prefs().edit().remove(KEY_NOTE).apply()
                render()
            }

            heading("job_in_doze")
            val jobView = output()
            button("Schedule recipe job ($RECIPE_JOB)") {
                val scheduler = getSystemService(JobScheduler::class.java)
                scheduler.schedule(
                    JobInfo.Builder(RECIPE_JOB, ComponentName(this@RecipesActivity, SampleJobService::class.java))
                        .setMinimumLatency(60 * 60 * 1000L)
                        .build(),
                )
                jobView.text = "Job $RECIPE_JOB scheduled"
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SAVED, savedCount)
    }

    private fun render() {
        countView.text = "Saved count: $savedCount"
        noteView.text = persistedNote(this)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SAVED = "saved_count"
        private const val KEY_NOTE = "note"
        private const val PREFS = "recipes"
        private const val RECIPE_JOB = 4545

        /** The line restart_keeps_state checks, for the hub to show after a restart. */
        fun persistedNote(context: Context): String =
            "Persisted note: ${context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NOTE, null) ?: "none"}"
    }
}
