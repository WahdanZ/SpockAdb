package spock.adb.flutter

import com.android.ddmlib.IDevice
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.AndroidProbe
import spock.adb.diagnostics.DiagnosticCollector
import spock.adb.diagnostics.DiagnosticSections
import spock.adb.diagnostics.FlutterDiagnosticSource
import spock.adb.diagnostics.FlutterSection
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.TOKEN
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceEvent
import spock.adb.timeline.FlutterTimelineRecorder
import spock.adb.timeline.TimelineCategory
import spock.adb.timeline.TimelineEvent
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whoever holds a VM Service token can run code in the app [FR9]. One fake token, in every place
 * an app can put it, runs through the whole wiring — the session's events, the event log, the
 * Diagnose section and the Timeline — and comes out nowhere.
 */
class FlutterTokenPathTest {

    private val vm = FakeVmService()
    private val session = FlutterSession()
    private val identity = AppIdentity(SERIAL, APP, 4242, IdentityCheck.DTD_PID_START)
    private val log = FlutterEventLog()
    private val rows = CopyOnWriteArrayList<TimelineEvent>()
    private val recorder = FlutterTimelineRecorder(
        sink = { rows += it },
        ticker = { CompletableFuture<Unit>() },
    )
    private val deviceTime = DeviceTime(0, 40, ZoneOffset.ofHours(2))

    @AfterEach
    fun tearDown() {
        recorder.dispose()
        session.close()
        vm.close()
    }

    @Test
    fun `a token the app reports reaches no log, report or row`() {
        vm.on(SessionReads.HTTP_PROFILE) { FakeVmService.Reply.Result(profileWithToken()) }
        listOf<FlutterSessionServiceListener>(log, recorder).forEach { it.sessionCreated(session) }
        session.connect(PastedUriDiscovery(vm.uri).discover().single())
        session.deviceTime.set(deviceTime)
        val connected = FlutterSessionChange.Connected(session, identity)
        listOf<FlutterSessionServiceListener>(log, recorder).forEach { it.sessionChanged(connected) }
        eventually(message = "HTTP recording on") { session.snapshot.httpRecording == HttpRecording.EnabledBySpock }

        vm.pushEvent("Extension", errorEvent())
        vm.pushEvent("Extension", navigationEvent())
        eventually(message = "the events in the log") {
            log.contents(session)?.let { it.errors.isNotEmpty() && it.navigation.isNotEmpty() } == true
        }
        eventually(message = "the failed request on the Timeline") {
            recorder.tick()
            rows.any { it.category == TimelineCategory.HTTP && "getVM" in it.title }
        }

        val contents = checkNotNull(log.contents(session))
        val report = DiagnosticCollector.render(diagnose(contents))
        val timeline = rows.joinToString("\n") { "${it.title}\n${it.detail}\n${it.deviceTime}" }

        assertTrue(rows.any { it.title.contains("Navigator") }, "the route reached the Timeline: $timeline")
        assertTrue(rows.any { it.title.contains("RenderFlex") }, "the error reached the Timeline: $timeline")
        assertTrue(rows.any { it.category == TimelineCategory.HTTP && "getVM" in it.title }, timeline)
        assertTrue(report.contains("RenderFlex"), "the error reached the report")
        assertTrue(report.contains("/status/500") || report.contains("getVM"), "the HTTP failures reached the report")
        listOf(
            "log" to contents.toString(),
            "report" to report,
            "timeline" to timeline,
        ).forEach { (where, text) -> assertNoToken(where, text) }
    }

    @Test
    fun `a token that got past the session is still scrubbed by the section`() {
        // Defence in depth: events handed to the log as they would be without the session's scrub.
        val raw = FlutterSession()
        log.sessionCreated(raw)
        log.sessionChanged(FlutterSessionChange.Connected(raw, identity))
        log.accept(raw, FlutterEvent(VmServiceEvent("Extension", errorEvent()), false))
        log.accept(raw, FlutterEvent(VmServiceEvent("Extension", navigationEvent()), false))
        val contents = checkNotNull(log.contents(raw))
        assertTrue(contents.toString().contains(TOKEN), "the raw event does carry the token")

        val report = DiagnosticCollector.render(
            diagnose(contents, outcomeMessage = "ws://127.0.0.1:41234/$TOKEN/ws refused the connection"),
        )

        assertTrue(report.contains("RenderFlex"))
        assertNoToken("report", report)
        raw.close()
    }

    private fun diagnose(contents: FlutterEventLog.Contents, outcomeMessage: String? = null): JsonObject {
        val reads = object : FlutterDiagnosticSource.Reads {
            override fun refreshRate(): Double? = null
            override fun httpProfile(): JsonObject = SessionReads.httpProfile(session, null, READ_MS)
        }
        val outcome = outcomeMessage?.let { FlutterAttachOutcome.Failed(it) }
            ?: FlutterAttachOutcome.Connected(session, identity, reused = true)
        val source = FlutterDiagnosticSource(
            APP,
            FlutterBuild.DEBUG,
            outcome,
            note = "Reached through http://127.0.0.1:41234/$TOKEN/",
            live = FlutterDiagnosticSource.Live(
                identity,
                session.snapshot,
                FlutterBuild.DEBUG,
                contents,
                deviceTime,
                reads,
            ),
        )
        val probe = AndroidProbe(mockk<IDevice>(relaxed = true), SERIAL, APP, flutter = source)
        return DiagnosticCollector().collect(listOf(FlutterSection), probe)
            .also { assertTrue(DiagnosticSections.ALL.contains(FlutterSection)) }
    }

    private fun assertNoToken(where: String, text: String) {
        TOKEN_FORMS.forEach { form -> assertFalse(text.contains(form), "the token ($form) is in the $where:\n$text") }
    }

    private fun errorEvent() = isolateEvent("Extension", UI_ISOLATE, DEVICE_MS) {
        addProperty("extensionKind", FlutterExtensionEvent.ERROR)
        add(
            "extensionData",
            JsonObject().apply {
                addProperty("description", "Exception caught by rendering library at http://127.0.0.1:41234/$TOKEN/")
                addProperty(
                    "renderedErrorText",
                    "A RenderFlex overflowed by 219 pixels on the right.\n" +
                        "Inspect it in DevTools: http://127.0.0.1:9100/#/inspector?uri=ws%3A%2F%2F127.0.0.1%3A41234" +
                        "%2F${TOKEN.removeSuffix("=")}%3D%2Fws",
                )
                add(
                    "properties",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("type", "ErrorSummary")
                                addProperty(
                                    "description",
                                    "A RenderFlex overflowed; see ws://127.0.0.1:41234/$TOKEN/ws",
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun navigationEvent() = isolateEvent("Extension", UI_ISOLATE, DEVICE_MS + 1) {
        addProperty("extensionKind", FlutterExtensionEvent.NAVIGATION)
        add(
            "extensionData",
            JsonObject().apply {
                add(
                    "route",
                    JsonObject().apply {
                        addProperty("description", "MaterialPageRoute(http://localhost:41234/$TOKEN/)")
                        add("settings", JsonObject().apply { addProperty("name", "/vm/127.0.0.1:41234/$TOKEN/") })
                    },
                )
            },
        )
    }

    /** A request to the VM Service itself, failed, with the address in its URI and its error. */
    private fun profileWithToken() = JsonObject().apply {
        addProperty("type", "HttpProfile")
        addProperty("timestamp", DEVICE_MS * 1_000)
        add(
            "requests",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("id", "1")
                        addProperty("method", "GET")
                        addProperty("uri", "http://127.0.0.1:41234/$TOKEN/getVM")
                        addProperty("startTime", DEVICE_MS * 1_000)
                        add(
                            "request",
                            JsonObject().apply {
                                addProperty("error", "SocketException: http://127.0.0.1:41234/$TOKEN/ refused")
                            },
                        )
                    },
                )
            },
        )
    }

    private companion object {
        const val SERIAL = "emulator-5554"
        const val APP = "spock.adb.spock_flutter_sample"
        const val DEVICE_MS = 1_790_000_000_000L
        const val READ_MS = 2_000L

        /** Raw, and percent-encoded as DevTools links carry it. */
        val TOKEN_FORMS = listOf(TOKEN, TOKEN.removeSuffix("="), "${TOKEN.removeSuffix("=")}%3D")
    }
}
