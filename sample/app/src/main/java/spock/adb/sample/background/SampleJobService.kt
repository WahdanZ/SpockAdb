package spock.adb.sample.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** A job scheduled with JobScheduler directly, not through WorkManager. */
class SampleJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        RunLog.record(this, "SampleJobService ran job ${params.jobId}")
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        /** Needs charging and an idle device, so on a desk it waits. Run Now forces it. */
        const val CHARGING_IDLE_JOB = 4242

        /** Needs an unmetered network, and is persisted across reboots. */
        const val UNMETERED_JOB = 4343

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val service = ComponentName(context, SampleJobService::class.java)
            scheduler.schedule(
                JobInfo.Builder(CHARGING_IDLE_JOB, service)
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            scheduler.schedule(
                JobInfo.Builder(UNMETERED_JOB, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true)
                    .setMinimumLatency(10 * 60 * 1000L)
                    .build(),
            )
        }
    }
}
