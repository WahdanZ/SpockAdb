package spock.adb.flutter

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.Client
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import spock.adb.AppSettingService
import spock.adb.CancellationSignal
import spock.adb.context.SpockSelection
import spock.adb.device.ConnectedDevice
import spock.adb.diagnostics.DiagnosticShell
import spock.adb.diagnostics.FlutterDiagnosticSource
import spock.adb.diagnostics.FlutterWords
import spock.adb.flutter.analysis.FlutterLogcatErrors
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.string
import spock.adb.pidsOf
import spock.adb.timeline.DebugTimelineService
import java.io.IOException
import com.android.ddmlib.TimeoutException as AdbTimeoutException

/**
 * The project's automatic Flutter attach, and what it keeps: a [FlutterFollower] driven by the
 * selection, the selected app's process starts (read by the Timeline's device recorder, and
 * ddmlib naming a new process), and Diagnose; and the [FlutterEventLog] of the live session,
 * for Diagnose.
 *
 * Created with the tool window, or by the first Diagnose an agent asks for.
 */
@Service(Service.Level.PROJECT)
class FlutterFollowerService(private val project: Project) : Disposable {

    private val sessions = FlutterSessionService.getInstance(project)

    val eventLog = FlutterEventLog()

    val follower = FlutterFollower(
        attach = { device, applicationId, startedAt, build, recordHttp ->
            sessions.ensureSession(device, applicationId, startedAt, build, recordHttp)
        },
        buildOf = { device, applicationId ->
            FlutterBuildCache.shared.readOn(device.device, device.serialNumber, applicationId)
        },
        pidsOf = ::pidsOrNull,
        recordHttp = { AppSettingService.getInstance().state.recordFlutterHttp },
        background = { ApplicationManager.getApplication().executeOnPooledThread(it) },
    )

    /** ddmlib named a process: the selected app's, maybe restarted with no device log to say so. */
    private val clientNamed = AndroidDebugBridge.IClientChangeListener { client, mask ->
        val data = client.clientData
        val app = namedProcess(mask, data.clientDescription, data.packageName) ?: return@IClientChangeListener
        follower.processSeen(client.device.serialNumber, app, data.pid.toLong())
    }

    init {
        Disposer.register(this, follower)
        sessions.addListener(this, eventLog)
        sessions.addListener(this, follower::sessionChanged)
        SpockSelection.getInstance(project).addListener(this) { snapshot, changes ->
            if (SpockSelection.Change.DEVICE in changes || SpockSelection.Change.APP in changes) {
                follower.follow(snapshot.device?.takeIf { it.info.isUsable }, snapshot.app)
            }
        }
        DebugTimelineService.getInstance(project).let { timeline ->
            timeline.addProcessStartListener(this) { serial, app, pid, hostMs ->
                follower.processStarted(serial, app, pid, hostMs)
            }
            timeline.addForegroundListener(this, follower::foreground)
        }
        // ddmlib's listeners are static: they outlive an adb restart, which the device log may not.
        AndroidDebugBridge.addClientChangeListener(clientNamed)
    }

    /**
     * What Diagnose reports in its `flutter` section for [applicationId] on [device]: null for an
     * app that is not a Flutter app, so there is no section. Attaches when there is no session
     * yet, within [ATTACH_BUDGET_MS], then reads the session within [READ_BUDGET_MS] more. Never
     * starts rebuild recording. Blocking: from a pooled thread.
     */
    fun diagnosticSource(device: ConnectedDevice, applicationId: String?): FlutterDiagnosticSource? {
        val app = applicationId?.takeIf { it.isNotBlank() } ?: return null
        val build = FlutterBuildCache.shared.detectOn(device.device, device.serialNumber, app) ?: return null
        val outcome = follower.attachNow(device, app, build, ATTACH_BUDGET_MS)
        val connected = outcome as? FlutterAttachOutcome.Connected
        return FlutterDiagnosticSource(
            applicationId = app,
            build = build,
            outcome = outcome,
            live = connected?.let { live(device, it) },
        )
    }

    /**
     * What `flutter_app_status` reports for [applicationId], a [build] of a Flutter app on
     * [device]: attaches when there is no session yet, within [ATTACH_BUDGET_MS], as Diagnose
     * does. Never waits for the session's clock: a session just opened says it is measuring.
     * Blocking: from a pooled thread.
     */
    fun status(device: ConnectedDevice, applicationId: String, build: FlutterBuild): FlutterAppStatus {
        val outcome = follower.attachNow(device, applicationId, build, ATTACH_BUDGET_MS)
        val live = (outcome as? FlutterAttachOutcome.Connected)?.let { connected ->
            val session = connected.session
            FlutterAppStatus.Live(
                identity = connected.identity,
                snapshot = session.snapshot,
                buildMode = session.buildMode,
                dartVersion = FlutterAppStatus.dartVersion(session.vm?.string("version")),
                deviceTime = session.deviceTime.current,
                clockDone = session.deviceTime.done,
            )
        }
        return FlutterAppStatus(applicationId, device.serialNumber, build, outcome, live = live)
    }

    /**
     * A rebuild recording window on [applicationId]'s session, for an explicit request
     * (`android_get_recomposition_counts`, the Inspector's Recompositions tab) — never for
     * Diagnose: it writes to the app. Attaches within [ATTACH_BUDGET_MS] when there is no session
     * yet; without one, [FlutterRebuildRecorder.Result.NoSession] says why, and the caller may
     * record Compose instead (an add-to-app host). [record] runs the window on the session's
     * recorder. Blocking for the window: from a pooled thread.
     */
    fun recordRebuilds(
        device: ConnectedDevice,
        applicationId: String,
        build: FlutterBuild,
        record: (FlutterRebuildRecorder) -> FlutterRebuildRecorder.Result,
    ): FlutterRebuildRecorder.Result {
        val outcome = follower.attachNow(device, applicationId, build, ATTACH_BUDGET_MS)
        val connected = outcome as? FlutterAttachOutcome.Connected
            ?: return FlutterRebuildRecorder.Result.NoSession(
                Redaction.scrub(
                    "$applicationId ships the Flutter engine; recording its widget rebuilds needs a live debug " +
                        "session, and " +
                        FlutterWords.attach(FlutterDiagnosticSource(applicationId, build, outcome)).replaceFirstChar {
                            it.lowercase()
                        },
                ),
            )
        return record(FlutterRebuildRecorder(connected.session))
    }

    /**
     * [recordRebuilds] for [applicationId] when its APK ships the Flutter engine, recording for
     * [windowMs] or until [cancelled]; null for any other app, which the caller records as Compose.
     * Blocking for the window: from a pooled thread.
     */
    fun recordRebuildsIfFlutter(
        device: ConnectedDevice,
        applicationId: String,
        windowMs: Long,
        cancelled: CancellationSignal,
        limit: Int,
    ): FlutterRebuildRecorder.Result? {
        val build = FlutterBuildCache.shared.detectOn(device.device, device.serialNumber, applicationId) ?: return null
        return recordRebuilds(device, applicationId, build) { it.record(windowMs, cancelled, limit) }
    }

    private fun live(device: ConnectedDevice, connected: FlutterAttachOutcome.Connected): FlutterDiagnosticSource.Live {
        val session = connected.session
        return FlutterDiagnosticSource.Live(
            identity = connected.identity,
            snapshot = session.snapshot,
            buildMode = session.buildMode,
            events = eventLog.contents(session),
            // A session this report just opened is still measuring its clock: five `date` round
            // trips can take longer than one read.
            deviceTime = session.deviceTime.await(if (connected.reused) READ_BUDGET_MS else NEW_CLOCK_WAIT_MS),
            reads = object : FlutterDiagnosticSource.Reads {
                override fun refreshRate(): Double? = readRefreshRate(session)

                override fun httpProfile(): JsonObject = SessionReads.httpProfile(session, null, READ_BUDGET_MS)

                override fun flutterLog(lines: Int): String? =
                    adbOrNull { DiagnosticShell.run(device.device, flutterLogCommand(lines)) }
            },
        )
    }

    override fun dispose() {
        AndroidDebugBridge.removeClientChangeListener(clientNamed)
    }

    companion object {
        /** How long Diagnose waits for an attach before saying the app is starting (design §3). */
        const val ATTACH_BUDGET_MS = 3_000L

        /** Each read of a live session Diagnose makes, and the wait for its clock. */
        const val READ_BUDGET_MS = 2_000L

        /** The wait for the clock of a session the report itself just opened. */
        const val NEW_CLOCK_WAIT_MS = 5_000L

        private const val ADB_SECONDS = 5L

        /** What Flutter printed in the last [lines] of logcat, for framework errors while structured errors are off. */
        private fun flutterLogCommand(lines: Int) = "logcat -d -v threadtime -t $lines ${FlutterLogcatErrors.FILTER}"

        /**
         * The name to match a ddmlib client against the selected app: its process name, which is
         * the applicationId only for the app's main process. Not ddmlib's `packageName`, which is
         * the package for every process of the app — `com.foo:bg` too (ddmlib 31.8: the real
         * package on Android 11+, the process name up to its `:` before) — while `pidof com.foo`
         * lists only the main process, so a restarting secondary process would re-arm the
         * follower each time. The package only while the process name is not known.
         */
        internal fun processName(description: String?, packageName: String?): String? =
            description?.takeIf { it.isNotBlank() } ?: packageName?.takeIf { it.isNotBlank() }

        /** The process a ddmlib client change with [mask] names, or null when it names none. */
        internal fun namedProcess(mask: Int, description: String?, packageName: String?): String? =
            if (mask and Client.CHANGE_NAME == 0) null else processName(description, packageName)

        /** `pidof` as the follower needs it; null when adb fails. */
        private fun pidsOrNull(device: ConnectedDevice, applicationId: String): Set<Long>? = adbOrNull {
            device.device.pidsOf(applicationId, ADB_SECONDS).mapNotNull(String::toLongOrNull).toSet()
        }

        /** [read], or null when adb fails. */
        private fun <T> adbOrNull(read: () -> T): T? = try {
            read()
        } catch (_: IOException) {
            null
        } catch (_: AdbCommandRejectedException) {
            null
        } catch (_: ShellCommandUnresponsiveException) {
            null
        } catch (_: AdbTimeoutException) {
            null
        }

        /** Null when the app will not say: the budget is assumed then. */
        private fun readRefreshRate(session: FlutterSession): Double? = try {
            SessionReads.refreshRate(session, READ_BUDGET_MS)
        } catch (_: VmServiceException) {
            null
        }

        /**
         * Not the inline `project.service<T>()`, which inlines a call missing before 2023.3
         * (docs/COMPATIBILITY.md).
         */
        fun getInstance(project: Project): FlutterFollowerService =
            project.getService(FlutterFollowerService::class.java)
    }
}
