package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.STRUCTURED_ERRORS
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.fixture
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.util.concurrent.CopyOnWriteArrayList

/** A [FlutterSession] on a [FakeVmService], closed after each test, and what the tests read off the fake. */
abstract class FlutterSessionFixture {

    protected val vm = FakeVmService()
    protected val connectedAt = 1_727_776_801_500L
    protected val session = FlutterSession(clock = { connectedAt })
    protected val events = CopyOnWriteArrayList<FlutterEvent>()

    init {
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) {
                    events += event
                }
            },
        )
    }

    @AfterEach
    fun tearDown() {
        session.close()
        vm.close()
    }

    /** A candidate that counts its releases, so the `adb forward` clean-up can be checked. */
    protected class TrackingCandidate(private val uri: VmServiceUri, ddsLikely: Boolean) :
        VmServiceCandidate(VmServiceSource.LOGCAT, ddsLikely) {
        @Volatile
        var released = 0
        override val description: String get() = uri.redacted()
        override fun open(): VmServiceUri = uri
        override fun release() {
            released++
        }
    }

    protected fun pasted() = PastedUriDiscovery(vm.uri).discover().single()

    protected fun tracking(ddsLikely: Boolean = true) = TrackingCandidate(VmServiceUri.parse(vm.uri), ddsLikely)

    protected fun extensionCalls(method: String, isolateId: String? = null) =
        vm.requestsFor(method).map { it.getAsJsonObject("params") }
            .filter { isolateId == null || it.get("isolateId").asString == isolateId }

    protected fun writes(method: String, isolateId: String? = null) =
        extensionCalls(method, isolateId).filter { it.has("enabled") }.map { it.get("enabled").asString }

    protected fun FakeVmService.methods() = requests.map { it.get("method").asString }

    protected fun stateChanged(value: String, timestamp: Long, isolateId: String = UI_ISOLATE) =
        isolateEvent("Extension", isolateId, timestamp) {
            addProperty("extensionKind", "Flutter.ServiceExtensionStateChanged")
            add(
                "extensionData",
                JsonObject().apply {
                    addProperty("extension", STRUCTURED_ERRORS)
                    addProperty("value", value)
                },
            )
        }

    /** Events are handled in order: once a fresh live one is seen, those pushed before it were handled. */
    protected fun awaitEventsHandled() {
        val marker = System.nanoTime()
        vm.pushEvent(
            "Extension",
            fixture("event-frame.json").apply {
                addProperty("timestamp", connectedAt + 10_000_000)
                getAsJsonObject("extensionData").addProperty("marker", marker)
            },
        )
        eventually(message = "the marker event") {
            events.any { it.event.event.getAsJsonObject("extensionData")?.get("marker")?.asLong == marker }
        }
    }
}
