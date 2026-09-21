package spock.adb.sample.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import spock.adb.sample.SampleApp

/** Where every piece of background work records that it ran, for the screen to show. */
object RunLog {
    private const val PREFS = "background_runs"

    fun record(context: Context, what: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $what"
        Log.i(SampleApp.TAG, "Background: $what")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lines = (listOf(line) + prefs.getString("log", "").orEmpty().lines()).filter { it.isNotBlank() }.take(20)
        prefs.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", null) ?: "(nothing has run yet)"
}

/** Succeeds. Scheduled one-off with constraints, and periodic. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RunLog.record(applicationContext, "SyncWorker ran (attempt ${runAttemptCount + 1}, tags ${tags.filterNot { it.contains('.') }})")
        return Result.success()
    }
}

/** Always asks to be retried, so the job builds up a failure count and backoff. */
class FlakyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RunLog.record(applicationContext, "FlakyWorker ran and asked for a retry (attempt ${runAttemptCount + 1})")
        return Result.retry()
    }
}
