package spock.adb.timeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import spock.adb.ActionResult
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.mcp.McpCall
import spock.adb.mcp.McpServerService

/**
 * The project's Debug Timeline: what happened, from every part of Spock, in one order.
 *
 * Sources and where they come in:
 * - actions the tool window and the Tools menu run — [AdbControllerImp][spock.adb.AdbControllerImp]
 *   reports each result here as well as to the status bar;
 * - Storage writes, Run Now, and device-condition changes — their panels call [record];
 * - agent tool calls — a listener on [McpServerService], which the in-IDE assistant records
 *   through too;
 * - devices connecting and disconnecting — a device-list observer;
 * - the app on the device — a [DeviceEventRecorder] for the device and app the tool window has
 *   selected, started by [follow].
 */
@Service(Service.Level.PROJECT)
class DebugTimelineService(private val project: Project) : Disposable {

    val timeline = DebugTimeline()

    private var recorder: DeviceEventRecorder? = null
    private var followedDevice: ConnectedDevice? = null
    private var followedApp: String? = null
    private var knownDevices: Map<String, DeviceInfo>? = null

    @Volatile
    private var disposed = false

    /** Whether the device's log is being read. Actions and agent calls are recorded regardless. */
    var recordingDevice: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            restartRecorder()
        }

    /** Where device events come from now, for the panel's status line; null when nothing is being read. */
    @Volatile
    var recordingTarget: String? = null
        private set

    private val mcpListener: (McpCall) -> Unit = { call -> recordAgentCall(call) }

    init {
        McpServerService.getInstance().addCallListener(mcpListener)
        SpockAdbService.getInstance(project).controller.observeDevices(::onDevices)
    }

    fun record(
        category: TimelineCategory,
        severity: TimelineSeverity,
        title: String,
        detail: String = "",
        deviceSerial: String? = followedDevice?.serialNumber,
    ) {
        if (disposed) return
        timeline.record(
            TimelineEvent(
                timeMs = System.currentTimeMillis(),
                category = category,
                severity = severity,
                title = title,
                detail = detail,
                deviceSerial = deviceSerial,
            ),
        )
    }

    fun recordAction(result: ActionResult) {
        val elapsed = result.elapsedMs?.let { "\nTook $it ms." }.orEmpty()
        record(
            TimelineCategory.SPOCK_ACTION,
            if (result.ok) TimelineSeverity.INFO else TimelineSeverity.ERROR,
            result.message.lineSequence().first().take(TITLE_LIMIT),
            result.message + elapsed,
        )
    }

    /**
     * A Storage tab write, whichever way it ended.
     *
     * @param done what a write that landed says; null when it did not.
     * @param unverified the write landed but did not read back.
     */
    @Suppress("LongParameterList")
    fun recordStorageWrite(
        path: String,
        packageName: String,
        done: String?,
        unverified: Boolean,
        message: String,
        deviceSerial: String,
    ) {
        val severity = when {
            done != null -> TimelineSeverity.INFO
            unverified -> TimelineSeverity.WARNING
            else -> TimelineSeverity.ERROR
        }
        val title = done ?: "Write to $path in $packageName: ${message.lineSequence().first()}"
        record(TimelineCategory.STORAGE, severity, title, message, deviceSerial)
    }

    /** A note the developer adds, to find the moment they saw the bug. */
    fun addMarker(note: String) {
        record(TimelineCategory.MARKER, TimelineSeverity.INFO, note.ifBlank { "Marker" })
    }

    /**
     * Records [packageName] on [device] from now on. Called on the EDT whenever the tool window's
     * device or app changes; the same pair again changes nothing.
     */
    fun follow(device: ConnectedDevice?, packageName: String?) {
        val usable = device?.takeIf { it.info.isUsable }
        val app = packageName?.takeIf { it.isNotBlank() }
        if (usable?.serialNumber == followedDevice?.serialNumber && app == followedApp) return
        followedDevice = usable
        followedApp = app
        restartRecorder()
    }

    private fun restartRecorder() {
        recorder?.stop()
        recorder = null
        recordingTarget = null
        val device = followedDevice
        val app = followedApp
        if (disposed || !recordingDevice) return
        if (device == null || app == null) return

        recordingTarget = "$app on ${device.info.displayName}"
        record(
            TimelineCategory.DEVICE,
            TimelineSeverity.INFO,
            "Recording $app on ${device.info.displayName}",
            deviceSerial = device.serialNumber,
        )
        recorder = DeviceEventRecorder(device, app, sink = { event -> if (!disposed) timeline.record(event) })
            .also { it.start() }
    }

    private fun onDevices(devices: List<ConnectedDevice>) {
        val now = devices.associate { it.serialNumber to it.info }
        val before = knownDevices
        knownDevices = now
        // The first list is the state of the world, not a change to it.
        if (before == null) return
        deviceChanges(before, now).forEach { timeline.record(it) }
    }

    private fun recordAgentCall(call: McpCall) {
        if (call.toolName == GET_TIMELINE_TOOL) return
        val outcome = if (call.isError) " failed" else ""
        timeline.record(
            TimelineEvent(
                timeMs = call.timestamp,
                category = TimelineCategory.MCP,
                severity = if (call.isError) TimelineSeverity.WARNING else TimelineSeverity.INFO,
                title = "${call.toolName}$outcome" + (call.client?.let { " ($it)" }.orEmpty()),
                detail = "Arguments: ${call.arguments}\nResult: ${call.result.take(DETAIL_LIMIT)}\n" +
                    "Took ${call.durationMs} ms.",
                deviceSerial = call.deviceSerial,
            ),
        )
    }

    override fun dispose() {
        disposed = true
        McpServerService.getInstance().removeCallListener(mcpListener)
        recorder?.stop()
        recorder = null
    }

    companion object {
        /** Not recorded: an agent reading the timeline is not something that happened to the app. */
        const val GET_TIMELINE_TOOL = "android_get_debug_timeline"

        private const val TITLE_LIMIT = 200
        private const val DETAIL_LIMIT = 2_000

        fun getInstance(project: Project): DebugTimelineService =
            project.getService(DebugTimelineService::class.java)

        /** What changed between two device lists, keyed by serial. Pure, for testing. */
        internal fun deviceChanges(
            before: Map<String, DeviceInfo>,
            after: Map<String, DeviceInfo>,
            nowMs: Long = System.currentTimeMillis(),
        ): List<TimelineEvent> {
            fun event(serial: String, severity: TimelineSeverity, title: String) = TimelineEvent(
                timeMs = nowMs,
                category = TimelineCategory.DEVICE,
                severity = severity,
                title = title,
                deviceSerial = serial,
            )
            val connected = (after.keys - before.keys).map { serial ->
                event(serial, TimelineSeverity.INFO, "Connected: ${after.getValue(serial).displayName}")
            }
            val disconnected = (before.keys - after.keys).map { serial ->
                event(serial, TimelineSeverity.WARNING, "Disconnected: ${before.getValue(serial).displayName}")
            }
            val changed = after.keys.intersect(before.keys)
                .filter { before.getValue(it).state != after.getValue(it).state }
                .map { serial ->
                    val info = after.getValue(serial)
                    event(
                        serial,
                        if (info.isUsable) TimelineSeverity.INFO else TimelineSeverity.WARNING,
                        "${info.displayName}: ${before.getValue(serial).state.label} → ${info.state.label}",
                    )
                }
            return connected + disconnected + changed
        }
    }
}
