package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import spock.adb.ShellQuote
import java.util.concurrent.ConcurrentHashMap

/**
 * Screen recording, one session per device.
 *
 * Recording goes through `executeShellCommand("screenrecord …")` rather than
 * `IDevice.startScreenRecorder`, which Android Studio does not implement — it throws "This
 * method is not used in Android Studio", the same way `getScreenshot` did before the
 * screenshot tool was rewritten. `StubbedIDeviceApiTest` fails the build if anyone reaches
 * for it again.
 *
 * `screenrecord` blocks until it stops, so the call runs on its own thread rather than a
 * pooled one: occupying a shared pool thread for three minutes is not what that pool is for.
 */
internal object ScreenRecorder {

    /** The platform's own hard cap. `screenrecord` stops itself here whatever we ask for. */
    const val MAX_SECONDS = 180
    const val DEFAULT_SECONDS = 180

    private const val SHELL_SLACK_SECONDS = 30L
    private const val FINALISE_TIMEOUT_MS = 15_000L
    private const val MILLIS_PER_SECOND = 1_000L

    private val sessions = ConcurrentHashMap<String, Session>()

    class Session(val serial: String, val remotePath: String, val timeLimitSeconds: Int) {
        val startedAt: Long = System.currentTimeMillis()

        @Volatile
        var thread: Thread? = null

        @Volatile
        var finished: Boolean = false

        val elapsedSeconds: Long get() = (System.currentTimeMillis() - startedAt) / MILLIS_PER_SECOND
    }

    /** @throws IllegalStateException when a recording is already running on this device. */
    fun start(device: IDevice, serial: String, timeLimitSeconds: Int): Session {
        // A session whose time limit already elapsed is over, not in the way — but its
        // recording is still sitting on the device, and now that the session is gone nothing
        // will ever pull it. Dropping the session without the file leaves one abandoned mp4
        // per expired recording, which is the accumulation the rm -f after a pull exists to
        // prevent.
        sessions[serial]?.takeIf { it.finished }?.let { expired ->
            if (sessions.remove(serial, expired)) {
                runCatching { McpShell.run(device, "rm -f " + ShellQuote.quote(expired.remotePath)) }
            }
        }

        val session = Session(
            serial = serial,
            remotePath = "/sdcard/spock-adb-recording-${System.currentTimeMillis()}.mp4",
            timeLimitSeconds = timeLimitSeconds.coerceIn(1, MAX_SECONDS),
        )
        val existing = sessions.putIfAbsent(serial, session)
        check(existing == null) {
            "A recording has been running on $serial for ${existing?.elapsedSeconds}s " +
                "(${existing?.remotePath}). Call android_stop_screen_recording first."
        }

        val command = "screenrecord --time-limit ${session.timeLimitSeconds} " +
            ShellQuote.quote(session.remotePath)
        session.thread = Thread({
            try {
                McpShell.run(
                    device = device,
                    command = command,
                    timeoutSeconds = session.timeLimitSeconds + SHELL_SLACK_SECONDS,
                )
            } finally {
                session.finished = true
            }
        }, "spock-adb-screenrecord-$serial").apply {
            isDaemon = true
            start()
        }
        return session
    }

    /** @throws IllegalStateException when nothing is recording on this device. */
    fun stop(device: IDevice, serial: String): Session {
        val session = sessions.remove(serial)
            ?: error("No recording is running on $serial. Call android_start_screen_recording first.")

        // SIGINT, not SIGKILL: screenrecord writes the MP4 container's index on the way out,
        // and a killed recording leaves a file no player will open.
        runCatching { McpShell.run(device, "pkill -INT screenrecord || killall -INT screenrecord") }
        session.thread?.join(FINALISE_TIMEOUT_MS)
        return session
    }

    fun active(serial: String): Session? = sessions[serial]?.takeIf { !it.finished }

    /** Test seam: sessions outlive a single tool instance, so they must be resettable. */
    fun reset() = sessions.clear()
}

/** `android_start_screen_recording` — begin capturing the screen. */
class StartScreenRecordTool : AdbTool {

    override val name = "android_start_screen_recording"

    override val description =
        "Start recording the device screen to a file on the device. Stop it with " +
            "android_stop_screen_recording, which retrieves the video. Recording captures " +
            "whatever is on screen, including notifications and any personal content, so ask " +
            "before recording anything you were not asked to record. The platform stops the " +
            "recording after three minutes whatever limit you set."

    override val safety = ToolSafety.SAFE_ACTION

    override val inputSchema: JsonObject = Schema.obj {
        integer(
            "timeLimitSeconds",
            "Stop automatically after this many seconds. Defaults to 180, which is also the " +
                "platform's hard maximum.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val limit = arguments.optionalInt("timeLimitSeconds", ScreenRecorder.DEFAULT_SECONDS)

        val session = ScreenRecorder.start(device.device, device.serialNumber, limit)
        return ToolResult.text(
            "Recording ${device.info.describe()} to ${session.remotePath}, stopping after " +
                "${session.timeLimitSeconds}s. Call android_stop_screen_recording to end it " +
                "and retrieve the file.",
        )
    }
}

/** `android_stop_screen_recording` — end the capture and bring the video back. */
class StopScreenRecordTool(
    private val pullFileTool: PullFileTool = PullFileTool(),
) : AdbTool {

    override val name = "android_stop_screen_recording"

    override val description =
        "Stop the recording started with android_start_screen_recording, copy the video off " +
            "the device into the IDE's pull directory, and report where it landed. The file " +
            "is removed from the device afterwards."

    override val safety = ToolSafety.SAFE_ACTION

    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val session = ScreenRecorder.stop(device.device, device.serialNumber)

        val pulled = pullFileTool.execute(
            JsonObject().apply {
                addProperty("remotePath", session.remotePath)
                arguments.optionalString("deviceSerial")?.let { addProperty("deviceSerial", it) }
            },
            context,
        )

        // Leaving recordings on the device fills /sdcard a few hundred megabytes at a time,
        // but only delete once the pull actually succeeded.
        if (!pulled.isError) {
            runCatching {
                McpShell.run(device.device, "rm -f ${ShellQuote.quote(session.remotePath)}")
            }
        }

        val detail = pulled.content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }
        return ToolResult(
            content = listOf(
                ToolContent.Text("Recorded ${session.elapsedSeconds}s on ${device.info.describe()}.\n$detail"),
            ),
            isError = pulled.isError,
        )
    }
}
