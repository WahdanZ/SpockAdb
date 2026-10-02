package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceRedirectException
import spock.adb.flutter.vmservice.VmServiceUri
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Not a unit test: the P4a device check, against a real running app. Skipped unless
 * `SPOCK_VM_SERVICE_URI` holds the address `flutter run` printed (or a DevTools link):
 *
 * ```
 * SPOCK_VM_SERVICE_URI='http://127.0.0.1:<port>/<token>=/' \
 *   ./gradlew test --tests spock.adb.flutter.FlutterSessionLiveCheck --rerun -i
 * ```
 *
 * It connects a [FlutterSession], prints what it found and the events it saw for
 * `SPOCK_VM_SERVICE_SECONDS` (default 10 — interact with the app meanwhile), closes, and checks
 * that HTTP timeline logging is back where it was. `flutter run`'s hot reload should keep working
 * throughout. Nothing printed carries the token.
 */
@EnabledIfEnvironmentVariable(named = FlutterSessionLiveCheck.URI_ENV, matches = ".+")
class FlutterSessionLiveCheck {

    @Test
    fun `connects to a running app, follows it, and leaves it as it found it`() {
        val pasted = System.getenv(URI_ENV)
        val seconds = System.getenv(SECONDS_ENV)?.toLongOrNull() ?: DEFAULT_SECONDS
        val before = httpLogging(VmServiceUri.parse(pasted))
        println("HTTP timeline logging before: $before")

        val session = FlutterSession()
        val events = CopyOnWriteArrayList<FlutterEvent>()
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) {
                    events += event
                }

                override fun onStateChanged(state: SessionState) = println("state: $state")
            },
        )
        val candidate = PastedUriDiscovery(pasted).discover().single()
        session.connect(candidate)
        try {
            println("connected to $candidate at ${session.connectedAt}")
            println("VM ${session.vm?.get("version")?.asString}")
            println("selection: ${session.selection?.let(::describe)}")
            println("UI isolate ${session.uiIsolateId}, build ${session.buildMode}")
            println("structuredErrors ${session.structuredErrorsEnabled}")
            Thread.sleep(seconds * MILLIS)
            events.groupingBy { "${if (it.history) "history" else "live"} ${it.streamId} ${it.label()}" }
                .eachCount()
                .toSortedMap()
                .forEach { (key, count) -> println("  $count × $key") }
            assertTrue(session.state is SessionState.Connected, "state ${session.state}")
        } finally {
            session.close()
        }

        val after = httpLogging(VmServiceUri.parse(pasted))
        println("HTTP timeline logging after: $after")
        assertEquals(before, after)
        val token = VmServiceUri.parse(pasted).webSocketUri.path.split('/')[1]
        if (token != "ws") {
            assertFalse(events.any { it.event.event.toString().contains(token) }, "a token reached an event")
        }
    }

    /**
     * Read with a separate client, so the check does not trust the session it is checking. A
     * direct device address hands its clients to DDS (spike S9), so this follows that once too.
     */
    private fun httpLogging(uri: VmServiceUri): Boolean? {
        val client = try {
            VmServiceClient.connect(uri)
        } catch (e: VmServiceRedirectException) {
            println("direct address handed to DDS: ${e.target}")
            VmServiceClient.connect(e.target)
        }
        return client.use {
            val selected = IsolateSelector(it).select() as? IsolateSelection.Selected ?: return null
            ExtensionResults.bool(it.callServiceExtension(FlutterSession.HTTP_LOGGING, selected.isolate.id))
        }
    }

    private fun FlutterEvent.label(): String? = extensionKind ?: kind

    private fun describe(selection: IsolateSelection): String = when (selection) {
        is IsolateSelection.Selected -> "selected ${selection.isolate.id} (${selection.isolate.name})"
        is IsolateSelection.Ambiguous -> "ambiguous ${selection.candidates.map { it.id }}"
        is IsolateSelection.Paused -> selection.message
        is IsolateSelection.NoFlutterIsolate -> selection.reason
    }

    companion object {
        const val URI_ENV = "SPOCK_VM_SERVICE_URI"
        private const val SECONDS_ENV = "SPOCK_VM_SERVICE_SECONDS"
        private const val DEFAULT_SECONDS = 10L
        private const val MILLIS = 1_000L
    }
}
