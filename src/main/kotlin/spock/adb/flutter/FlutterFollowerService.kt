package spock.adb.flutter

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import spock.adb.AppSettingService
import spock.adb.context.SpockSelection
import spock.adb.device.ConnectedDevice
import spock.adb.diagnostics.DiagnosticShell
import spock.adb.diagnostics.FlutterDiagnosticSource
import spock.adb.diagnostics.FlutterSection
import spock.adb.flutter.analysis.FlutterLogcatErrors
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.pidsOf
import spock.adb.timeline.DebugTimelineService
import java.io.IOException
import com.android.ddmlib.TimeoutException as AdbTimeoutException

/**
 * The project's automatic Flutter attach, and what it keeps: a [FlutterFollower] driven by the
 * selection, the selected app's process starts (read by the Timeline's device recorder) and
 * Diagnose; and the [FlutterEventLog] of the live session, for Diagnose.
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
            FlutterBuildCache.shared.detectOn(device.device, device.serialNumber, applicationId)
        },
        pidsOf = ::pidsOrNull,
        recordHttp = { AppSettingService.getInstance().state.recordFlutterHttp },
        background = { ApplicationManager.getApplication().executeOnPooledThread(it) },
    )

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

                override fun flutterLog(): String? = adbOrNull { DiagnosticShell.run(device.device, FLUTTER_LOG) }
            },
        )
    }

    override fun dispose() = Unit

    companion object {
        /** How long Diagnose waits for an attach before saying the app is starting (design §3). */
        const val ATTACH_BUDGET_MS = 3_000L

        /** Each read of a live session Diagnose makes, and the wait for its clock. */
        const val READ_BUDGET_MS = 2_000L

        /** The wait for the clock of a session the report itself just opened. */
        const val NEW_CLOCK_WAIT_MS = 5_000L

        private const val ADB_SECONDS = 5L

        /** What Flutter printed, for framework errors while structured errors are off. */
        private const val FLUTTER_LOG =
            "logcat -d -v threadtime -t ${FlutterSection.LOGCAT_WINDOW_LINES} ${FlutterLogcatErrors.FILTER}"

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
