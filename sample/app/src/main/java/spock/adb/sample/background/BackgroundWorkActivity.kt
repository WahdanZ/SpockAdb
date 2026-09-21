package spock.adb.sample.background

import android.app.job.JobScheduler
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import spock.adb.sample.screen
import java.util.concurrent.TimeUnit

/**
 * Schedules background work of every shape the Background Work tab reads, and shows what has run.
 *
 * Schedule everything, open the tab, and each job should be listed with what it is waiting on.
 * Select one and press Run Now: it runs here, and the log below says so.
 */
class BackgroundWorkActivity : AppCompatActivity() {

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Background work") {
            note("Schedule, then open the plugin's Background Work tab.")
            val result = output()
            button("Schedule everything") { result.text = scheduleAll() }
            button("WorkManager: one-off, charging + unmetered, 15 min delay") { result.text = scheduleOneOff() }
            button("WorkManager: periodic every 15 min, battery not low") { result.text = schedulePeriodic() }
            button("WorkManager: flaky worker (retries, linear backoff)") { result.text = scheduleFlaky() }
            button("JobScheduler: two direct jobs (4242, 4343)") {
                SampleJobService.schedule(this@BackgroundWorkActivity)
                result.text = "Scheduled jobs ${SampleJobService.CHARGING_IDLE_JOB} and ${SampleJobService.UNMETERED_JOB}"
            }
            button("AlarmManager: exact, inexact and repeating") { result.text = AlarmReceiver.schedule(this@BackgroundWorkActivity) }
            button("Cancel everything") { result.text = cancelAll() }
            heading("What has run")
            log = output()
            button("Refresh") { refresh() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        log.text = RunLog.read(this)
    }

    private fun scheduleAll(): String {
        scheduleOneOff()
        schedulePeriodic()
        scheduleFlaky()
        SampleJobService.schedule(this)
        return "WorkManager: 3 requests. JobScheduler: 2 jobs. ${AlarmReceiver.schedule(this)}"
    }

    private fun scheduleOneOff(): String {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setInitialDelay(15, TimeUnit.MINUTES)
            .addTag("one_off_sync")
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("one_off_sync", ExistingWorkPolicy.REPLACE, request)
        return "Enqueued one-off SyncWorker ${request.id}"
    }

    private fun schedulePeriodic(): String {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag("periodic_sync")
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("periodic_sync", ExistingPeriodicWorkPolicy.UPDATE, request)
        return "Enqueued periodic SyncWorker ${request.id}"
    }

    private fun scheduleFlaky(): String {
        val request = OneTimeWorkRequestBuilder<FlakyWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("flaky")
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("flaky", ExistingWorkPolicy.REPLACE, request)
        return "Enqueued FlakyWorker ${request.id}"
    }

    private fun cancelAll(): String {
        WorkManager.getInstance(this).cancelAllWork()
        getSystemService(JobScheduler::class.java).apply {
            cancel(SampleJobService.CHARGING_IDLE_JOB)
            cancel(SampleJobService.UNMETERED_JOB)
        }
        AlarmReceiver.cancel(this)
        return "Cancelled all work, jobs and alarms"
    }
}
