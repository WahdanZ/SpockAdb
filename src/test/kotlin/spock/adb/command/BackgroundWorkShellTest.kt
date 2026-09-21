package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackgroundWorkShellTest {

    @Test
    fun `run now forces the job and quotes the package`() {
        assertEquals(
            "cmd jobscheduler run -f 'com.example.app' 12",
            BackgroundWorkShell.runJobCommand(RunJobRequest("com.example.app", 12)),
        )
    }

    @Test
    fun `a namespaced job is addressed with -n`() {
        assertEquals(
            "cmd jobscheduler run -f -n 'sync' 'com.example.app' 3",
            BackgroundWorkShell.runJobCommand(RunJobRequest("com.example.app", 3, namespace = "sync")),
        )
    }

    @Test
    fun `a package that is not a package is refused before it reaches the shell`() {
        assertThrows<IllegalArgumentException> {
            BackgroundWorkShell.runJobCommand(RunJobRequest("com.example; reboot", 1))
        }
        assertThrows<IllegalArgumentException> { BackgroundWorkShell.jobsCommand("a|b") }
    }

    @Test
    fun `run now is unavailable before API 24, and for a namespace before API 34`() {
        assertNotNull(BackgroundWorkShell.runJobUnavailableReason(23))
        assertNull(BackgroundWorkShell.runJobUnavailableReason(24))
        assertNotNull(BackgroundWorkShell.runJobUnavailableReason(33, namespace = "sync"))
        assertNull(BackgroundWorkShell.runJobUnavailableReason(34, namespace = "sync"))
        // An unknown level is not a reason to refuse; the device will say if it cannot.
        assertNull(BackgroundWorkShell.runJobUnavailableReason(null))
    }

    @Test
    fun `a job id is resolved to its namespace, and an ambiguous one is refused`() {
        fun job(id: Int, namespace: String?) = spock.adb.parser.JobSchedulerDumpParser.parse(
            "Registered 1 jobs:\n  JOB ${namespace?.let { "$it:" } ?: "#"}u0a1/$id: abc pkg/.Svc\n",
        ).jobs.single()

        val wm = "androidx.work.systemjobscheduler"
        assertEquals(wm, BackgroundWorkShell.resolveNamespace(6, listOf(job(6, wm), job(4242, null))))
        assertNull(BackgroundWorkShell.resolveNamespace(4242, listOf(job(6, wm), job(4242, null))))
        assertNull(BackgroundWorkShell.resolveNamespace(99, listOf(job(6, wm))))
        assertThrows<IllegalStateException> {
            BackgroundWorkShell.resolveNamespace(6, listOf(job(6, wm), job(6, null)))
        }
    }

    @Test
    fun `the device clock is read in seconds`() {
        assertEquals(1_790_017_611_000L, BackgroundWorkShell.parseClock("1790017611\n"))
        assertNull(BackgroundWorkShell.parseClock("date: bad format"))
    }

    @Test
    fun `only the device saying it started the job counts as success`() {
        val request = RunJobRequest("com.example.app", 7)

        assertNull(BackgroundWorkShell.runJobFailure(request, "Running job [FORCED]"))
        assertEquals(
            "Could not find job 7 in package com.example.app / user 0",
            BackgroundWorkShell.runJobFailure(request, "Could not find job 7 in package com.example.app / user 0\n"),
        )
        assertNotNull(BackgroundWorkShell.runJobFailure(request, ""))
    }
}
