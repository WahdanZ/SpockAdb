package spock.adb.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.AppIdentity
import spock.adb.flutter.DeviceTime
import spock.adb.flutter.FlutterAppStatus
import spock.adb.flutter.FlutterAttachOutcome
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterIsolate
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionService
import spock.adb.flutter.FlutterSessionSnapshot
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.IdentifiedCandidate
import spock.adb.flutter.IdentityCheck
import spock.adb.flutter.SessionState
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.mcp.tools.FlutterAppStatusTool
import java.time.ZoneOffset

class FlutterAppStatusToolTest {

    private val identity = AppIdentity(SERIAL, APP, 4242, IdentityCheck.DTD_PID_START)

    private fun run(status: FlutterAppStatus): JsonObject {
        val tool = FlutterAppStatusTool { _, _, _ -> status }
        val result = tool.execute(JsonObject().apply { addProperty("packageName", APP) }, FakeToolContext())
        assertFalse(result.isError, result.text())
        return JsonParser.parseString(result.text()).asJsonObject
    }

    private fun status(outcome: FlutterAttachOutcome?, live: FlutterAppStatus.Live? = null, note: String? = null) =
        FlutterAppStatus(APP, SERIAL, FlutterBuild.DEBUG, outcome, note, live)

    @Test
    fun `every attach outcome is said in words, and only a session reads as connected`() {
        val candidate = IdentifiedCandidate(
            PastedUriDiscovery("ws://127.0.0.1:1/$TOKEN/ws").discover().single(),
            identity.copy(verifiedBy = IdentityCheck.DTD_PID_AMBIGUOUS),
        )
        val outcomes = mapOf(
            FlutterAttachOutcome.NotRunning(SERIAL, APP) to "not running",
            FlutterAttachOutcome.ReleaseBuild(SERIAL, APP) to "release build",
            FlutterAttachOutcome.NotReady("No address yet.", 1_000) to "the app is starting",
            FlutterAttachOutcome.NoDdsSession(identity, FlutterSessionService.NO_DDS_MESSAGE) to "without a debugger",
            FlutterAttachOutcome.Ambiguous(listOf(candidate, candidate), "choose one") to "does not guess",
            FlutterAttachOutcome.NotFound("nothing logged an address") to "No Flutter session found",
            FlutterAttachOutcome.Failed("adb went away") to "Could not connect",
        )

        outcomes.forEach { (outcome, words) ->
            val json = run(status(outcome))
            assertTrue(json["attach"].asString.contains(words, ignoreCase = true), "$outcome → $json")
            assertFalse(json["connected"].asBoolean)
            assertTrue(json["flutterApp"].asBoolean)
            assertEquals("debug", json["apkBuild"].asString)
            assertFalse(json.has("identity"), "$json")
        }
    }

    @Test
    fun `a live session reports how it was found, its kind, isolate, versions, HTTP and clock`() {
        val json = run(status(FlutterAttachOutcome.Connected(FlutterSession(), identity, reused = true), live()))

        assertTrue(json["connected"].asBoolean)
        assertTrue(json["attach"].asString.startsWith("Connected to $APP on $SERIAL (pid 4242), dtd+pid+start"))
        val found = json.getAsJsonObject("identity")
        assertEquals(4242, found["pid"].asInt)
        assertEquals("dtd+pid+start", found["verifiedBy"].asString)
        assertTrue(found["how"].asString.contains("Dart Tooling Daemon"))
        assertEquals("dds", json["connectionKind"].asString)
        assertEquals("debug", json["buildMode"].asString)
        val isolate = json.getAsJsonObject("uiIsolate")
        assertEquals("isolates/1", isolate["id"].asString)
        assertEquals("main", isolate["name"].asString)
        val versions = json.getAsJsonObject("versions")
        assertEquals("3.4.3 (stable)", versions["dart"].asString)
        assertTrue(versions["flutter"].asString.startsWith("unknown"))
        assertEquals("on", json["structuredErrors"].asString)
        assertTrue(json["httpRecording"].asString.startsWith("on: Spock turned it on"))
        val clock = json.getAsJsonObject("clock")
        assertEquals("measured", clock["state"].asString)
        assertEquals("+02:00", clock["zone"].asString)
    }

    @Test
    fun `the clock says measuring, then unavailable when the device would not say`() {
        fun clockOf(done: Boolean) = run(
            status(
                FlutterAttachOutcome.Connected(FlutterSession(), identity, reused = false),
                live(deviceTime = null, clockDone = done),
            ),
        ).getAsJsonObject("clock")["state"].asString

        assertEquals("measuring", clockOf(done = false))
        assertEquals("unavailable", clockOf(done = true))
    }

    @Test
    fun `logcat discovery and a read-only direct VM are named as such`() {
        val logcat = identity.copy(verifiedBy = IdentityCheck.LOGCAT_PID)
        val snapshot = snapshot().copy(connectionKind = ConnectionKind.DIRECT_NO_DDS)
        val json = run(
            status(FlutterAttachOutcome.Connected(FlutterSession(), logcat, reused = true), live(logcat, snapshot)),
        )

        assertEquals("logcat-pid", json.getAsJsonObject("identity")["verifiedBy"].asString)
        assertTrue(json["connectionKind"].asString.contains("read-only"))
    }

    @Test
    fun `an app that is not Flutter says so, with no session to look for`() {
        val json = run(FlutterAppStatus(APP, SERIAL, build = null, outcome = null))

        assertFalse(json["flutterApp"].asBoolean)
        assertTrue(json["attach"].asString.contains("is not a Flutter app"))
        assertFalse(json.has("connected"))
    }

    @Test
    fun `without a project the status says why there is no session`() {
        val json = run(status(outcome = null, note = "No project is open: Spock's Flutter session belongs to it."))

        assertTrue(json["attach"].asString.startsWith("No project is open"))
    }

    @Test
    fun `no VM Service address or token reaches the answer, whichever text carried it`() {
        val address = "ws://127.0.0.1:52511/$TOKEN/ws"
        val texts = listOf(
            status(FlutterAttachOutcome.Failed("Could not open $address: refused")),
            status(FlutterAttachOutcome.NotFound("the VM at http://127.0.0.1:52511/$TOKEN/ did not answer")),
            status(FlutterAttachOutcome.NotReady("DDS at $address is not up yet", 500)),
            status(outcome = null, note = "pasted $address"),
            status(
                FlutterAttachOutcome.Connected(FlutterSession(), identity, reused = true),
                live(snapshot = snapshot().copy(uiIsolate = isolate(name = "main $address"))),
            ),
        ).map { FlutterAppStatusTool { _, _, _ -> it }.render(it) }

        texts.forEach { text ->
            assertFalse(text.contains(TOKEN), text)
            assertFalse(text.contains("52511/"), text)
        }
    }

    /** Which app the tool asked about, with [arguments], [selected] in Spock and the project's [projectApp]. */
    private fun askedFor(arguments: JsonObject, selected: String?, projectApp: String?): String {
        val asked = mutableListOf<String>()
        val tool = FlutterAppStatusTool(selectedApp = { selected }) { _, _, packageName ->
            asked += packageName
            status(outcome = null)
        }
        tool.execute(arguments, FakeToolContext(applicationId = projectApp))
        return asked.single()
    }

    @Test
    fun `with no packageName the app selected in Spock is described, not the project's other flavor`() {
        assertEquals("$APP.dev", askedFor(JsonObject(), selected = "$APP.dev", projectApp = APP))
    }

    @Test
    fun `with nothing selected the project's app is described, and an argument wins over both`() {
        assertEquals(APP, askedFor(JsonObject(), selected = null, projectApp = APP))
        val asked = JsonObject().apply { addProperty("packageName", "$APP.second") }
        assertEquals("$APP.second", askedFor(asked, selected = "$APP.dev", projectApp = APP))
    }

    private fun live(
        identity: AppIdentity = this.identity,
        snapshot: FlutterSessionSnapshot = snapshot(),
        deviceTime: DeviceTime? = DeviceTime(epochOffsetMs = 0, uncertaintyMs = 40, zone = ZoneOffset.ofHours(2)),
        clockDone: Boolean = true,
    ) = FlutterAppStatus.Live(
        identity,
        snapshot,
        FlutterBuild.DEBUG,
        FlutterAppStatus.dartVersion("3.4.3 (stable) (Tue Jun 4 19:51:39 2024 +0000) on \"android_arm64\""),
        deviceTime,
        clockDone,
    )

    private fun snapshot() = FlutterSessionSnapshot(
        state = SessionState.Connected("isolates/1"),
        uiIsolate = isolate(),
        structuredErrorsEnabled = true,
        connectionKind = ConnectionKind.DDS,
        vmPid = 4242,
        httpRecording = HttpRecording.EnabledBySpock,
    )

    private fun isolate(name: String = "main") =
        FlutterIsolate("isolates/1", name, setOf(FlutterIsolate.STRUCTURED_ERRORS), pauseKind = "Resume")

    private companion object {
        const val SERIAL = "emulator-5554"
        const val APP = "spock.adb.spock_flutter_sample"

        /** Obviously fake: a VM Service token grants code execution, so tests never use a real one. */
        const val TOKEN = "FaKeToKeN0="
    }
}
