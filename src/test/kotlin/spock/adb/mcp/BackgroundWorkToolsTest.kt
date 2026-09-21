package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.GetPendingAlarmsTool
import spock.adb.mcp.tools.GetScheduledJobsTool
import spock.adb.mcp.tools.RunJobNowTool
import java.util.concurrent.TimeUnit

/** The background work tools, against a device that answers from recorded dumps. */
class BackgroundWorkToolsTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/dumpsys/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    /** A device on which [installed] is installed and each command prefix answers as mapped. */
    private fun context(
        installed: String = DIALER,
        apiLevel: String = "34",
        answers: Map<String, String> = emptyMap(),
        sent: MutableList<String> = mutableListOf(),
    ): FakeToolContext {
        val device = mockk<IDevice>(relaxed = true)
        every { device.getProperty("ro.build.version.sdk") } returns apiLevel
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val text = command.captured
            sent += text
            val output = when {
                text.startsWith("pm list packages") ->
                    if (text.contains("'$installed'")) "package:$installed" else ""
                else -> answers.entries.firstOrNull { text.startsWith(it.key) }?.value.orEmpty()
            }
            val bytes = output.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            Unit
        }
        return FakeToolContext(
            available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)),
            applicationId = installed,
        )
    }

    @Test
    fun `lists jobs with what each is waiting on`() {
        val result = GetScheduledJobsTool().execute(
            JsonObject(),
            context(answers = mapOf("dumpsys jobscheduler" to fixture("jobscheduler-api34.txt"))),
        )

        assertFalse(result.isError, result.text())
        val text = result.text()
        assertTrue(text.startsWith("19 scheduled job(s) for $DIALER"), text)
        assertTrue(text.contains("Job 100 — $DIALER/com.android.dialer.shortcuts.PeriodicJobService"), text)
        assertTrue(text.contains("Waiting on: charging (CHARGING), device idle (IDLE)"), text)
        assertTrue(text.contains("WorkManager: work spec id not shown by this dump"), text)
    }

    @Test
    fun `an app without jobs says so rather than returning nothing`() {
        val result = GetScheduledJobsTool().execute(
            JsonObject(),
            context(answers = mapOf("dumpsys jobscheduler" to "Registered 0 jobs:\n")),
        )

        assertFalse(result.isError)
        assertEquals("$DIALER has no scheduled jobs.", result.text())
    }

    @Test
    fun `an unreadable jobs dump is an error`() {
        val result = GetScheduledJobsTool().execute(
            JsonObject(),
            context(answers = mapOf("dumpsys jobscheduler" to "Can't find service: jobscheduler")),
        )

        assertTrue(result.isError)
    }

    @Test
    fun `lists only the app's alarms, with clock times`() {
        val result = GetPendingAlarmsTool().execute(
            JsonObject().apply { addProperty("packageName", "com.android.settings") },
            context(installed = "com.android.settings", answers = mapOf("dumpsys alarm" to fixture("alarm-api34.txt"))),
        )

        assertFalse(result.isError, result.text())
        val text = result.text()
        assertTrue(text.startsWith("1 pending alarm(s) for com.android.settings"), text)
        assertTrue(text.contains("RTC_WAKEUP — *walarm*:com.android.settings.battery.action.PERIODIC_JOB_UPDATE"), text)
        assertTrue(text.contains("(in 27m 58s)"), text)
        assertTrue(text.contains("Window: exact"), text)
    }

    @Test
    fun `run now reports success only when the device started the job`() {
        val sent = mutableListOf<String>()
        val started = RunJobNowTool().execute(
            JsonObject().apply { addProperty("jobId", 100) },
            context(answers = mapOf("cmd jobscheduler run" to "Running job [FORCED]"), sent = sent),
        )

        assertFalse(started.isError, started.text())
        assertTrue(sent.contains("cmd jobscheduler run -f '$DIALER' 100"), sent.toString())

        val refused = RunJobNowTool().execute(
            JsonObject().apply { addProperty("jobId", 5) },
            context(answers = mapOf("cmd jobscheduler run" to "Could not find job 5 in package $DIALER / user 0")),
        )
        assertTrue(refused.isError)
        assertTrue(refused.text().contains("Could not find job 5"), refused.text())
    }

    @Test
    fun `run now addresses a namespaced WorkManager job with -n`() {
        val sent = mutableListOf<String>()
        val result = RunJobNowTool().execute(
            JsonObject().apply {
                addProperty("jobId", 6)
                addProperty("namespace", "androidx.work.systemjobscheduler")
            },
            context(answers = mapOf("cmd jobscheduler run" to "Running job [FORCED]"), sent = sent),
        )

        assertFalse(result.isError, result.text())
        assertTrue(
            sent.contains("cmd jobscheduler run -f -n 'androidx.work.systemjobscheduler' '$DIALER' 6"),
            sent.toString(),
        )
    }

    @Test
    fun `run now finds a WorkManager job's namespace when the caller gives only the id`() {
        val sent = mutableListOf<String>()
        val result = RunJobNowTool().execute(
            JsonObject().apply {
                addProperty("jobId", 6)
                addProperty("packageName", SAMPLE)
            },
            context(
                installed = SAMPLE,
                answers = mapOf(
                    "dumpsys jobscheduler" to fixture("jobscheduler-api34-sample-app.txt"),
                    "cmd jobscheduler run" to "Running job [FORCED]",
                ),
                sent = sent,
            ),
        )

        assertFalse(result.isError, result.text())
        assertTrue(
            sent.contains("cmd jobscheduler run -f -n 'androidx.work.systemjobscheduler' '$SAMPLE' 6"),
            sent.toString(),
        )
        assertTrue(result.text().contains("namespace androidx.work.systemjobscheduler"), result.text())
        // WorkManager may still decline to run it, and the result has to say so.
        assertTrue(result.text().contains("WorkManager runs the Worker only if its work is due"), result.text())
    }

    @Test
    fun `a direct JobScheduler job carries no WorkManager caveat`() {
        val result = RunJobNowTool().execute(
            JsonObject().apply {
                addProperty("jobId", 4343)
                addProperty("packageName", SAMPLE)
            },
            context(
                installed = SAMPLE,
                answers = mapOf(
                    "dumpsys jobscheduler" to fixture("jobscheduler-api34-sample-app.txt"),
                    "cmd jobscheduler run" to "Running job [FORCED]",
                ),
            ),
        )

        assertFalse(result.isError, result.text())
        assertEquals("Started job 4343 of $SAMPLE, ignoring its constraints.", result.text())
    }

    @Test
    fun `run now is refused on a device too old to force jobs, without sending the command`() {
        val sent = mutableListOf<String>()
        val result = RunJobNowTool().execute(
            JsonObject().apply { addProperty("jobId", 1) },
            context(apiLevel = "23", sent = sent),
        )

        assertTrue(result.isError)
        assertTrue(sent.none { it.startsWith("cmd jobscheduler") }, sent.toString())
    }

    @Test
    fun `a package that is not installed is an error for every tool`() {
        listOf(GetScheduledJobsTool(), GetPendingAlarmsTool(), RunJobNowTool()).forEach { tool ->
            val result = tool.execute(
                JsonObject().apply {
                    addProperty("packageName", "com.not.installed")
                    addProperty("jobId", 1)
                },
                context(),
            )
            assertTrue(result.isError, tool.name)
        }
    }

    private companion object {
        const val DIALER = "com.google.android.dialer"
        const val SAMPLE = "spock.adb.sample"
    }
}
