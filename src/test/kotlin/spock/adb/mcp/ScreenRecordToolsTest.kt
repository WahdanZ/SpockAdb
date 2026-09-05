package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.PullFileTool
import spock.adb.mcp.tools.ScreenRecorder
import spock.adb.mcp.tools.StartScreenRecordTool
import spock.adb.mcp.tools.StopScreenRecordTool
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A recording session outlives the tool call that started it, so the state machine — not the
 * shell command — is what these tests are about.
 *
 * The fake device blocks on `screenrecord` the way a real one does, and releases when the
 * interrupt arrives. Without that, the recording would "finish" the instant it started and
 * the double-start case could never be reproduced.
 */
class ScreenRecordToolsTest {

    @TempDir
    lateinit var pullDirectory: Path

    private val issued: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val recordingEnded = CountDownLatch(1)

    private val device = mockk<IDevice>(relaxed = true)
    private val connected: ConnectedDevice = FakeToolContext.device("emulator-5554").copy(device = device)
    private val context get() = FakeToolContext(available = listOf(connected))

    private fun stopTool() = StopScreenRecordTool(PullFileTool { pullDirectory })

    @BeforeEach
    fun setUp() {
        ScreenRecorder.reset()

        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val issuedCommand = command.captured
            issued += issuedCommand
            when {
                // screenrecord blocks until it is interrupted or hits its time limit.
                issuedCommand.startsWith("screenrecord") ->
                    recordingEnded.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                issuedCommand.startsWith("pkill") -> recordingEnded.countDown()
            }
            receiver.captured.flush()
        }
        every { device.pullFile(any(), any()) } answers {
            Files.write(Path.of(secondArg<String>()), byteArrayOf(0x00, 0x01, 0x02))
            Unit
        }
    }

    @AfterEach
    fun tearDown() {
        recordingEnded.countDown()
        ScreenRecorder.reset()
    }

    private fun start(arguments: JsonObject = JsonObject()) =
        StartScreenRecordTool().execute(arguments, context)

    /**
     * Starts once the previous recording has released the device.
     *
     * A recording that ended on its own does not announce it — the thread notices when the
     * shell call returns — so this retries rather than sleeping for a guessed interval.
     */
    private fun startWhenFree(): spock.adb.mcp.tools.ToolResult {
        val deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1_000
        while (System.currentTimeMillis() < deadline) {
            runCatching { return start() }
            Thread.sleep(POLL_MS)
        }
        error("the expired recording never released the device; saw ${issued.toList()}")
    }

    private fun awaitCommand(prefix: String) {
        val deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1_000
        while (System.currentTimeMillis() < deadline) {
            if (issued.toList().any { it.startsWith(prefix) }) return
            Thread.sleep(POLL_MS)
        }
        error("no '$prefix' command was issued; saw ${issued.toList()}")
    }

    @Test
    fun `starting records to the device and says how to retrieve it`() {
        val result = start()
        awaitCommand("screenrecord")

        assertFalse(result.isError)
        assertTrue(result.text().contains("android_stop_screen_recording"), result.text())
        val command = issued.toList().first { it.startsWith("screenrecord") }
        assertTrue(command.contains("--time-limit 180"), command)
        assertTrue(command.contains("spock-adb-recording"), command)
    }

    @Test
    fun `a time limit beyond the platform's three-minute cap is clamped`() {
        start(JsonObject().apply { addProperty("timeLimitSeconds", 9_999) })
        awaitCommand("screenrecord")

        val command = issued.toList().first { it.startsWith("screenrecord") }
        assertTrue(command.contains("--time-limit 180"), command)
    }

    @Test
    fun `starting a second recording on the same device is refused`() {
        start()
        awaitCommand("screenrecord")

        val failure = assertThrows<IllegalStateException> { start() }

        assertTrue(failure.message!!.contains("has been running"), failure.message)
        assertTrue(failure.message!!.contains("emulator-5554"), failure.message)
        assertTrue(
            failure.message!!.contains("android_stop_screen_recording"),
            "should say how to recover: ${failure.message}",
        )
    }

    @Test
    fun `stopping without starting says so rather than pulling a file that is not there`() {
        val failure = assertThrows<IllegalStateException> { stopTool().execute(JsonObject(), context) }

        assertTrue(failure.message!!.contains("No recording is running"), failure.message)
    }

    @Test
    fun `stopping interrupts rather than kills, so the video is playable`() {
        start()
        awaitCommand("screenrecord")

        stopTool().execute(JsonObject(), context)

        val kill = issued.toList().first { it.startsWith("pkill") }
        // SIGKILL would leave an MP4 with no index that no player will open.
        assertTrue(kill.contains("-INT"), kill)
    }

    @Test
    fun `stopping retrieves the video and clears it off the device`() {
        start()
        awaitCommand("screenrecord")

        val result = stopTool().execute(JsonObject(), context)

        assertFalse(result.isError, result.text())
        val pulled = Files.list(pullDirectory).use { it.toList() }
        assertTrue(pulled.size == 1, "expected exactly one pulled file, got $pulled")
        assertTrue(pulled.single().fileName.toString().endsWith(".mp4"), pulled.toString())
        assertTrue(issued.toList().any { it.startsWith("rm -f") }, issued.toList().toString())
    }

    @Test
    fun `after stopping, a new recording can start`() {
        start()
        awaitCommand("screenrecord")
        stopTool().execute(JsonObject(), context)

        val result = start()

        assertFalse(result.isError, result.text())
    }

    private companion object {
        const val AWAIT_SECONDS = 5L
        const val POLL_MS = 10L
    }

    @Test
    fun `a recording that hit its own time limit is cleaned off the device`() {
        start()
        awaitCommand("screenrecord")

        // The time limit elapses: screenrecord returns on its own and no one ever calls stop,
        // so nothing will pull this file. Starting again drops the dead session — and used to
        // drop the recording with it, leaving one abandoned mp4 on /sdcard every time.
        recordingEnded.countDown()

        startWhenFree()

        awaitCommand("rm -f")
        assertTrue(
            issued.toList().any { it.startsWith("rm -f") && it.contains(".mp4") },
            "the expired recording should be removed from the device; saw ${issued.toList()}",
        )
    }
}
