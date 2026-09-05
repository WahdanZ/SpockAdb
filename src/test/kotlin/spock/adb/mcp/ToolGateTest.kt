package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolGateTest {

    @Test
    fun `a tool not on the disabled list runs`() {
        assertTrue(ToolGate.isEnabled("android_list_devices", setOf("android_run_adb_command")))
    }

    @Test
    fun `a disabled tool does not`() {
        assertFalse(ToolGate.isEnabled("android_run_adb_command", setOf("android_run_adb_command")))
    }

    @Test
    fun `nothing disabled means everything runs`() {
        assertTrue(ToolGate.isEnabled("android_run_adb_command", emptySet()))
    }

    @Test
    fun `the refusal names the tool, says it did not run, and says where the switch is`() {
        // All three matter: the model has to tell "you turned this off" from "this failed",
        // be sure nothing happened, and be able to tell the developer where to change it.
        val message = ToolGate.refusal("android_push_file")

        assertTrue(message.contains("android_push_file"), message)
        assertTrue(message.contains("not run"), message)
        assertTrue(message.contains("Settings"), message)
    }
}
