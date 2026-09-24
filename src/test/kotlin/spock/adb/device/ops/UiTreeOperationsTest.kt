package spock.adb.device.ops

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.TimeoutException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.uitree.DisplayMetrics
import spock.adb.uitree.UiCaptureException
import spock.adb.uitree.UiCaptureException.Kind
import spock.adb.uitree.UiNode
import java.io.File
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Capturing the screen, which the UI Inspector tab and the `android_get_ui_tree` family had a
 * copy of each.
 */
class UiTreeOperationsTest {

    @Test
    fun `a capture dumps, reads the dump back and removes it`() {
        val (device, commands) = scriptedDevice { command ->
            if (command.startsWith("cat")) screen(listOf("Continue")) else ""
        }

        UiTreeOperations(device).read()

        assertEquals(
            listOf(
                "uiautomator dump ${UiTreeOperations.DUMP_PATH}",
                "cat '${UiTreeOperations.DUMP_PATH}'",
                "rm -f '${UiTreeOperations.DUMP_PATH}'",
            ),
            commands,
        )
    }

    @Test
    fun `a refused dump is raised with what the device said`() {
        val (device, commands) = scriptedDevice { command ->
            if (command.startsWith("uiautomator")) "ERROR: could not get idle state." else ""
        }

        val thrown = assertThrows<IllegalStateException> { UiTreeOperations(device).read() }

        assertTrue(thrown.message!!.contains("could not get idle state"), thrown.message)
        assertTrue(commands.none { it.startsWith("cat") }, "nothing to read back: $commands")
        assertEquals(Kind.DUMP_REFUSED, (thrown as UiCaptureException).kind)
    }

    @Test
    fun `an empty dump is refused rather than parsed into an empty screen`() {
        val (device, _) = scriptedDevice { "" }

        val thrown = assertThrows<IllegalStateException> { UiTreeOperations(device).read() }

        assertEquals(Kind.EMPTY_DUMP, (thrown as UiCaptureException).kind)
    }

    @Test
    fun `a screen too large for the agent's old cap is still parsed whole`() {
        // The agent's copy read the XML through the 400,000-character cap meant for text
        // returned to an agent, so a busy screen reached the parser cut off mid-element. The
        // dump is never shown raw — it is parsed here — so there is nothing to cap.
        val labels = (1..LARGE_SCREEN_NODES).map { "Row $it" }
        val xml = screen(labels)
        assertTrue(xml.length > OLD_AGENT_CAP, "fixture is not large enough to prove anything")
        val (device, _) = scriptedDevice { command -> if (command.startsWith("cat")) xml else "" }

        val tree = UiTreeOperations(device).read()

        assertTrue(
            tree.root!!.flatten().any { it.text == "Row $LARGE_SCREEN_NODES" },
            "the last row of the screen did not survive the capture",
        )
    }

    @Test
    fun `only one place in the plugin dumps the UI`() {
        // The two copies dumped to different files under different names, so a failed cleanup
        // left litter neither side would recognise. This is the guard against a third.
        // The opening quote is what separates sending the command from naming it in a comment.
        val dumpers = File(MAIN_SOURCES).walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.readText().contains("\"uiautomator dump") }
            .map { it.name }
            .toList()

        assertEquals(listOf("UiTreeOperations.kt"), dumpers)
    }

    @Test
    fun `a capture cancelled after the dump reads nothing back and cleans nothing up`() {
        var cancelled = false
        val (device, commands) = scriptedDevice { command ->
            // The cancel lands while the dump is running; the dump itself still answers.
            if (command.startsWith("uiautomator")) cancelled = true
            if (command.startsWith("cat")) screen(listOf("Continue")) else ""
        }

        val thrown = assertThrows<UiCaptureException> {
            UiTreeOperations(device, cancellation = { cancelled }).read()
        }

        assertEquals(Kind.CANCELLED, thrown.kind)
        assertEquals(listOf("uiautomator dump ${UiTreeOperations.DUMP_PATH}"), commands)
    }

    @Test
    fun `a capture cancelled before it starts sends nothing`() {
        val (device, commands) = scriptedDevice { "" }

        val thrown = assertThrows<UiCaptureException> {
            UiTreeOperations(device, cancellation = { true }).read()
        }

        assertEquals(Kind.CANCELLED, thrown.kind)
        assertEquals(emptyList<String>(), commands)
    }

    @Test
    fun `the receiver ddmlib polls reports cancelled once the capturing thread is interrupted`() {
        val device = mockk<IDevice>(relaxed = true)
        val entered = CountDownLatch(1)
        lateinit var handed: IShellOutputReceiver
        every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
            handed = secondArg()
            entered.countDown()
            // ddmlib's read loop: keep polling the receiver, return normally once it says stop.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (!handed.isCancelled() && System.nanoTime() < deadline) Thread.onSpinWait()
        }
        var failure: Throwable? = null
        val owner = thread(isDaemon = true) {
            failure = runCatching { UiTreeOperations(device).read() }.exceptionOrNull()
        }

        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the capture never reached the device")
        // Polled from this thread, not the owner: what counts is the owner's interrupt flag.
        assertFalse(handed.isCancelled())
        owner.interrupt()
        assertTrue(handed.isCancelled())
        owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertFalse(owner.isAlive, "the capture did not stop")
        assertEquals(Kind.CANCELLED, (failure as UiCaptureException).kind)
    }

    @Test
    fun `an interrupt surfacing as a closed channel is a cancel, not a lost device`() {
        val (device, _) = scriptedDevice {
            Thread.currentThread().interrupt()
            throw ClosedByInterruptException()
        }

        try {
            val thrown = assertThrows<UiCaptureException> { UiTreeOperations(device).read() }

            assertEquals(Kind.CANCELLED, thrown.kind)
            assertTrue(Thread.currentThread().isInterrupted, "the owner clears the interrupt, not the capture")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an interrupt surfacing as a ddmlib timeout is a cancel, not a slow device`() {
        // What ddmlib throws when a thread blocked reading the socket is interrupted.
        val (device, _) = scriptedDevice {
            Thread.currentThread().interrupt()
            throw TimeoutException("Read interrupted with immediate timeout via interruption.")
        }

        try {
            val thrown = assertThrows<UiCaptureException> { UiTreeOperations(device).read() }

            assertEquals(Kind.CANCELLED, thrown.kind)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `adb refusing the device names it without advice meant for only one kind of caller`() {
        // The next step differs by reader: an agent calls android_list_devices, a person in the
        // Inspector reconnects. Each adds its own (UiTreeReader, captureFailureText); the shared
        // message does not presume. The agent's hint is asserted in ComposeUiObservationToolsTest.
        val (device, _) = scriptedDevice { throw AdbCommandRejectedException("device offline") }
        every { device.serialNumber } returns SERIAL

        val thrown = assertThrows<UiCaptureException> { UiTreeOperations(device).read() }

        assertEquals(Kind.DEVICE_UNAVAILABLE, thrown.kind)
        assertTrue(thrown.message!!.contains(SERIAL), thrown.message)
        assertTrue(thrown.message!!.contains("no longer available"), thrown.message)
        assertFalse(thrown.message!!.contains("android_list_devices"), thrown.message)
        assertTrue(thrown.message!!.contains("device offline"), thrown.message)
    }

    @Test
    fun `a dropped connection is an unavailable device`() {
        val (device, _) = scriptedDevice { throw IOException("Connection reset by peer") }

        val thrown = assertThrows<UiCaptureException> { UiTreeOperations(device, serial = SERIAL).read() }

        assertEquals(Kind.DEVICE_UNAVAILABLE, thrown.kind)
        assertTrue(thrown.message!!.contains(SERIAL), thrown.message)
    }

    @Test
    fun `a connection timeout names the command and how long it was given`() {
        val (device, _) = scriptedDevice { throw TimeoutException() }

        val thrown = assertThrows<UiCaptureException> {
            UiTreeOperations(device, timeoutSeconds = TIMEOUT_SECONDS).read()
        }

        assertEquals(Kind.TIMED_OUT, thrown.kind)
        assertTrue(thrown.message!!.contains("$TIMEOUT_SECONDS seconds"), thrown.message)
        assertTrue(thrown.message!!.contains("uiautomator dump"), thrown.message)
    }

    @Test
    fun `a command that stops answering is a timeout`() {
        val (device, _) = scriptedDevice { command ->
            if (command.startsWith("cat")) throw ShellCommandUnresponsiveException() else ""
        }

        val thrown = assertThrows<UiCaptureException> {
            UiTreeOperations(device, timeoutSeconds = TIMEOUT_SECONDS).read()
        }

        assertEquals(Kind.TIMED_OUT, thrown.kind)
        assertTrue(thrown.message!!.contains("$TIMEOUT_SECONDS seconds"), thrown.message)
        assertTrue(thrown.message!!.contains("cat"), thrown.message)
    }

    @Test
    fun `an observation dumps, then measures the display, in that order`() {
        val (device, commands) = scriptedDevice(::measuredScreen)

        val observation = UiTreeOperations(device, serial = SERIAL).observe()

        assertEquals(DUMP_COMMANDS + listOf("wm size", "wm density"), commands)
        assertEquals(SERIAL, observation.deviceSerial)
        assertEquals(DisplayMetrics(DENSITY, WIDTH, HEIGHT), observation.metrics)
        assertEquals(DENSITY, observation.tree.densityDpi, "the audit reads density from the tree")
        assertEquals(0, observation.tree.rotation)
        assertTrue(observation.startedAtMillis <= observation.completedAtMillis)
    }

    @Test
    fun `metrics already known are not read again`() {
        // A later wait polls one screen many times; two shell calls a poll add up.
        val (device, commands) = scriptedDevice(::measuredScreen)
        val known = DisplayMetrics(DENSITY, WIDTH, HEIGHT)

        val observation = UiTreeOperations(device).observe(known)

        assertEquals(DUMP_COMMANDS, commands)
        assertEquals(known, observation.metrics)
        assertEquals(DENSITY, observation.densityDpi)
    }

    @Test
    fun `a cancel while the display is measured cancels the capture`() {
        var cancelled = false
        val (device, commands) = scriptedDevice { command ->
            if (command == "wm size") cancelled = true
            measuredScreen(command)
        }

        val thrown = assertThrows<UiCaptureException> {
            UiTreeOperations(device, cancellation = { cancelled }).observe()
        }

        assertEquals(Kind.CANCELLED, thrown.kind)
        assertEquals("wm size", commands.last(), "nothing is sent after the cancel")
    }

    @Test
    fun `a device lost while the display is measured is a lost device, not an unmeasured screen`() {
        val (device, _) = scriptedDevice { command ->
            if (command.startsWith("wm ")) throw IOException("Connection reset by peer")
            measuredScreen(command)
        }

        val thrown = assertThrows<UiCaptureException> { UiTreeOperations(device, serial = SERIAL).observe() }

        assertEquals(Kind.DEVICE_UNAVAILABLE, thrown.kind)
    }

    @Test
    fun `a display that will not be measured leaves the metrics unknown rather than failing`() {
        // Metrics are best effort; the tree is not. A slow `wm` must not cost a good capture.
        val (device, commands) = scriptedDevice { command ->
            when (command) {
                "wm size" -> throw TimeoutException()
                "wm density" -> throw ShellCommandUnresponsiveException()
                else -> measuredScreen(command)
            }
        }

        val observation = UiTreeOperations(device).observe()

        assertEquals(DisplayMetrics.UNKNOWN, observation.metrics)
        assertEquals(null, observation.densityDpi)
        assertTrue(observation.tree.root != null)
        assertEquals("wm density", commands.last(), "one failed command does not skip the other")
    }

    @Test
    fun `an unreadable answer from wm is an unknown, not a guess`() {
        val (device, _) = scriptedDevice { command ->
            if (command.startsWith("wm ")) "Permission denial: nope" else measuredScreen(command)
        }

        assertEquals(DisplayMetrics.UNKNOWN, UiTreeOperations(device).observe().metrics)
    }

    @Test
    fun `only a refused or empty dump is worth retrying as it is`() {
        assertEquals(
            setOf(Kind.DUMP_REFUSED, Kind.EMPTY_DUMP),
            Kind.entries.filter { it.retryable }.toSet(),
        )
    }

    /** A healthy device showing one line of text on a measurable display. */
    private fun measuredScreen(command: String): String = when {
        command.startsWith("cat") -> screen(listOf("Continue"))
        command == "wm size" -> "Physical size: ${WIDTH}x$HEIGHT"
        command == "wm density" -> "Physical density: $DENSITY"
        else -> ""
    }

    private fun UiNode.flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }

    /** A `uiautomator` dump of a screen showing [labels], one text node each. */
    private fun screen(labels: List<String>): String = buildString {
        appendLine("""<hierarchy rotation="0">""")
        appendLine(
            """  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" """ +
                """package="p" content-desc="" checkable="false" checked="false" clickable="false" """ +
                """enabled="true" focusable="false" focused="false" scrollable="false" """ +
                """long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2220]">""",
        )
        labels.forEachIndexed { index, label ->
            appendLine(
                """    <node index="$index" text="$label" resource-id="" """ +
                    """class="android.widget.TextView" package="p" content-desc="" checkable="false" """ +
                    """checked="false" clickable="false" enabled="true" focusable="false" """ +
                    """focused="false" scrollable="false" long-clickable="false" password="false" """ +
                    """selected="false" bounds="[0,${index * 2}][1080,${index * 2 + 2}]" />""",
            )
        }
        appendLine("  </node>")
        append("</hierarchy>")
    }

    private companion object {
        const val MAIN_SOURCES = "src/main/kotlin"

        /** What the agent path used to truncate the XML at. */
        const val OLD_AGENT_CAP = 400_000

        /** Enough rows to clear [OLD_AGENT_CAP] comfortably. */
        const val LARGE_SCREEN_NODES = 1_500

        const val SERIAL = "emulator-5554"

        const val WIDTH = 1080
        const val HEIGHT = 2220
        const val DENSITY = 420

        val DUMP_COMMANDS = listOf(
            "uiautomator dump ${UiTreeOperations.DUMP_PATH}",
            "cat '${UiTreeOperations.DUMP_PATH}'",
            "rm -f '${UiTreeOperations.DUMP_PATH}'",
        )

        /** Distinct from the default, so a message quoting it proves which value it read. */
        const val TIMEOUT_SECONDS = 7L

        /** How long a test waits on another thread before calling it stuck. */
        const val WAIT_SECONDS = 5L
    }
}
