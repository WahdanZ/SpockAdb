package spock.adb.home

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterEvent
import spock.adb.flutter.vmservice.VmServiceEvent

class FlutterRouteRefreshTest {
    @Test
    fun `live navigation triggers a read but replay and unrelated events do not`() {
        fun event(kind: String, history: Boolean) = FlutterEvent(
            VmServiceEvent("Extension", JsonObject().apply { addProperty("extensionKind", kind) }),
            history,
        )
        assertTrue(FlutterRouteSource.rereadsOn(event("Flutter.Navigation", false)))
        assertFalse(FlutterRouteSource.rereadsOn(event("Flutter.Navigation", true)))
        assertFalse(FlutterRouteSource.rereadsOn(event("Flutter.Frame", false)))
    }
}
