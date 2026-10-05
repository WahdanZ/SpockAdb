package spock.adb.flutter.navigation

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.FlutterSessionFixture
import spock.adb.flutter.ReadOnlyCalls
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.ReadOnlyConnectionException
import spock.adb.flutter.vmservice.VmServiceException

class SessionObjectsTest : FlutterSessionFixture() {
    @Test
    fun `object reads use the guarded session and redact tokens while evaluate and invoke stay refused`() {
        vm.dds = false
        for (method in listOf("getObject", "getInstances")) {
            vm.on(method) {
                FakeVmService.Reply.Result(
                    JsonObject().apply {
                        addProperty("text", "http://127.0.0.1:1234/FAKEtoken_0000/")
                    },
                )
            }
        }
        session.connect(tracking(ddsLikely = false), allowDirect = true)
        val objects = SessionObjects(session, FakeVmService.UI_ISOLATE, 3_000)
        objects.isolate()
        assertFalse(objects.get("objects/1").toString().contains("FAKEtoken_0000"))
        objects.instances("classes/1", 20)
        val params = vm.requestsFor("getInstances").single().getAsJsonObject("params")
        assertTrue(params.get("includeSubclasses").asBoolean)
        assertEquals(20, params.get("limit").asInt)
        SessionObjects.METHODS.forEach { assertFalse(ReadOnlyCalls.isVmMutator(it)) }
        for (method in listOf("evaluate", "evaluateInFrame", "invoke")) {
            assertThrows<ReadOnlyConnectionException> { session.callVm(method) }
            assertTrue(vm.requestsFor(method).isEmpty())
        }
    }

    @Test
    fun `the shared read deadline refuses another call before sending it`() {
        var now = 0L
        val objects = SessionObjects(session, "isolates/1", 3_000) { now }
        now = 3_001
        assertThrows<VmServiceException> { objects.get("objects/1") }
        assertTrue(vm.requestsFor("getObject").isEmpty())
    }
}
