package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JobSchedulerDumpParserTest {

    private fun dump(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/dumpsys/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    /** Captured from an API 34 emulator: `dumpsys jobscheduler com.google.android.dialer`. */
    private val api34 by lazy { JobSchedulerDumpParser.parse(dump("jobscheduler-api34.txt"), DIALER) }

    /** Written from AOSP's Android 9 `JobStatus.dump`; no API 28 device was to hand. */
    private val api28 by lazy {
        JobSchedulerDumpParser.parse(dump("jobscheduler-api28-reconstructed.txt"), "com.example.app")
    }

    @Test
    fun `reads every job of the package from a real API 34 dump`() {
        assertNull(api34.problem)
        assertEquals(19, api34.jobs.size)
        assertTrue(api34.jobs.all { it.sourcePackage == DIALER })
    }

    @Test
    fun `a periodic job reports its interval and the constraints holding it back`() {
        val job = api34.jobs.single { it.jobId == 100 }

        assertEquals("com.google.android.dialer/com.android.dialer.shortcuts.PeriodicJobService", job.service)
        assertEquals("interval=+1d0h0m0s0ms flex=+1d0h0m0s0ms", job.periodic)
        assertTrue(job.persisted)
        assertEquals(listOf("CHARGING", "TIMING_DELAY", "DEADLINE", "IDLE"), job.required)
        assertEquals(listOf("CHARGING", "IDLE"), job.unsatisfied)
        assertEquals(listOf("CHARGING", "IDLE"), job.blockers)
        assertEquals(false, job.ready)
        assertEquals("exponential, initial +30s0ms", job.backoff)
        assertEquals("EXEMPTED", job.standbyBucket)
    }

    @Test
    fun `WorkManager jobs are recognised, and a hidden work spec id is not invented`() {
        val job = api34.jobs.single { it.jobId == 0 }

        assertTrue(job.isWorkManager)
        assertFalse(job.isPeriodic)
        // The API 34 dump prints parcelled extras as their size only.
        assertNull(job.workSpecId)
        assertEquals(listOf("IDLE", "WITHIN_QUOTA"), job.unsatisfied)
    }

    @Test
    fun `an unmet deadline is not reported as a blocker`() {
        val job = api34.jobs.single { it.jobId == 1573857705 }

        assertEquals(listOf("TIMING_DELAY", "DEADLINE", "CONNECTIVITY", "WITHIN_QUOTA"), job.unsatisfied)
        assertEquals(listOf("TIMING_DELAY", "CONNECTIVITY", "WITHIN_QUOTA"), job.blockers)
        assertEquals("2026-09-20 22:06:06", job.lastSuccessfulRun)
        assertEquals(DumpDurations.parse("+13h4m21s186ms"), job.earliestRunOffsetMillis)
    }

    @Test
    fun `reads the work spec id, failures and last failure where the dump shows them`() {
        val job = api28.jobs.single { it.jobId == 12 }

        assertEquals("0b6f3c2e-5d1a-4f7e-9a3b-2c1d0e9f8a7b", job.workSpecId)
        assertEquals(3, job.failures)
        assertEquals("linear, initial +10s0ms", job.backoff)
        assertEquals("2019-10-04 10:31:17", job.lastFailedRun)
        assertEquals(listOf("TIMING_DELAY"), job.blockers)
    }

    @Test
    fun `filters out other packages' jobs from an unfiltered dump`() {
        assertEquals(setOf(12, 7), api28.jobs.map { it.jobId }.toSet())
    }

    @Test
    fun `a job listed under active jobs is running`() {
        val all = JobSchedulerDumpParser.parse(dump("jobscheduler-api28-reconstructed.txt"))

        assertTrue(all.jobs.single { it.jobId == 1 }.running)
        assertFalse(all.jobs.single { it.jobId == 12 }.running)
    }

    @Test
    fun `before Android 8 the unsatisfied constraints are worked out from required and satisfied`() {
        val dump = JobSchedulerDumpParser.parse(dump("jobscheduler-api24-reconstructed.txt"), "com.example.app")

        assertEquals(listOf("CHARGING"), dump.jobs.single().unsatisfied)
    }

    @Test
    fun `WorkManager 2_10 jobs on API 34 are namespaced, and read as WorkManager`() {
        // Captured from the repo's own sample app after "Schedule everything".
        val dump = JobSchedulerDumpParser.parse(dump("jobscheduler-api34-sample-app.txt"), SAMPLE)

        assertNull(dump.problem)
        assertEquals(setOf(6, 7, 10, 4242, 4343), dump.jobs.map { it.jobId }.toSet())

        val workers = dump.jobs.filter { it.isWorkManager }
        assertEquals(3, workers.size)
        assertTrue(workers.all { it.namespace == "androidx.work.systemjobscheduler" })

        val oneOff = dump.jobs.single { it.jobId == 6 }
        assertEquals("$SAMPLE/androidx.work.impl.background.systemjob.SystemJobService", oneOff.service)
        assertEquals(listOf("CHARGING", "TIMING_DELAY"), oneOff.blockers)

        val direct = dump.jobs.single { it.jobId == 4242 }
        assertNull(direct.namespace)
        assertEquals(listOf("CHARGING", "IDLE"), direct.blockers)
    }

    @Test
    fun `a namespaced job keeps its namespace`() {
        val dump = """
            Registered 1 jobs:
              JOB usagestats_prune:1000/0: 227c483 @usagestats_prune@android/com.android.server.usage.UsageStatsIdleService
                Source: uid=1000 user=0 pkg=android
                JobInfo:
                  Service: android/com.android.server.usage.UsageStatsIdleService
        """.trimIndent()

        val job = JobSchedulerDumpParser.parse(dump, "android").jobs.single()

        assertEquals("usagestats_prune", job.namespace)
        assertEquals(0, job.jobId)
        assertEquals("android/com.android.server.usage.UsageStatsIdleService", job.service)
    }

    @Test
    fun `a dump in an unknown shape is a problem, not an app with no jobs`() {
        val unknown = JobSchedulerDumpParser.parse("Something else entirely:\n  nothing here")

        assertNotNull(unknown.problem)
        assertEquals("Something else entirely:\n  nothing here", unknown.raw)

        val movedHeaders = JobSchedulerDumpParser.parse("Registered 1 jobs:\n  JOB [u0a1 5] looks different now")
        assertNotNull(movedHeaders.problem)
    }

    @Test
    fun `an app with no jobs is an empty list and no problem`() {
        val none = JobSchedulerDumpParser.parse("Registered 0 jobs:\n\nPending queue:\n", "com.example.app")

        assertNull(none.problem)
        assertTrue(none.jobs.isEmpty())
    }

    @Test
    fun `durations are read as dumpsys formats them`() {
        assertEquals(30_000L, DumpDurations.parse("+30s0ms"))
        assertEquals(-(16 * 3_600_000L + 35 * 60_000L + 13_000L + 992L), DumpDurations.parse("-16h35m13s992ms"))
        assertEquals(86_400_000L, DumpDurations.parse("+1d0h0m0s0ms"))
        assertEquals(5L, DumpDurations.parse("+5ms"))
        assertNull(DumpDurations.parse("none"))
        assertNull(DumpDurations.parse("--"))
        assertEquals("7h 24m", DumpDurations.describe(DumpDurations.parse("+7h24m45s998ms")!!))
        assertEquals("1d", DumpDurations.describe(86_400_000L))
        assertEquals("0s", DumpDurations.describe(500L))
    }

    private companion object {
        const val DIALER = "com.google.android.dialer"
        const val SAMPLE = "spock.adb.sample"
    }
}
