package spock.adb.mcp

import com.google.gson.JsonObject
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.RunAdbCommandTool
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import java.util.concurrent.TimeUnit

/**
 * The safety model is the part of the MCP layer that must not be wrong: it is what stands
 * between an autonomous agent and a developer's device.
 */
class ToolSafetyTest {

    @Test
    fun `tool names are unique and namespaced`() {
        val names = ToolRegistry.all().map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate tool names")
        names.forEach { assertTrue(it.startsWith("android_"), "$it must be namespaced") }
    }

    @Test
    fun `exactly the state-destroying tools are marked destructive`() {
        // Pinned deliberately. Adding a tool that wipes state without adding it here should
        // fail this test and force the decision to be explicit.
        assertEquals(
            setOf(
                "android_clear_app_data",
                "android_uninstall_app",
                "android_revoke_permission",
                // Not state-destroying in the literal sense, but it redirects all device
                // traffic through a host and survives a reboot, so it asks first.
                "android_set_http_proxy",
                // They replace app state nothing else restores, and stop the app to do it.
                "android_set_app_preference",
                "android_delete_app_preference",
                "android_run_adb_command",
                // Changes how every app on the device behaves until reset, and survives the session.
                "android_force_doze",
            ),
            ToolRegistry.bySafety(ToolSafety.DESTRUCTIVE).map { it.name }.toSet(),
        )
    }

    @Test
    fun `read-only tools never mutate device state`() {
        assertEquals(
            setOf(
                "android_list_devices",
                "android_get_device_info",
                "android_list_packages",
                "android_get_package_info",
                "android_get_current_activity",
                "android_get_activity_stack",
                "android_get_current_fragments",
                "android_get_logcat",
                "android_get_processes",
                "android_get_battery_info",
                "android_get_network_info",
                "android_get_debug_context",
                "android_take_screenshot",
                "android_get_ui_tree",
                "android_find_ui_element",
                "android_accessibility_audit",
                "android_assert_visible",
                "android_assert_enabled",
                "android_assert_text",
                "android_get_http_proxy",
                "android_list_app_storage",
                "android_read_app_storage",
                "android_get_scheduled_jobs",
                "android_get_pending_alarms",
                "android_get_device_conditions",
            ),
            ToolRegistry.bySafety(ToolSafety.READ_ONLY).map { it.name }.toSet(),
        )
    }

    @Test
    fun `a declined destructive call does nothing and says so`() {
        val context = FakeToolContext(confirmationAnswer = false)
        val tool = ToolRegistry.find("android_run_adb_command")!!

        val result = tool.execute(
            JsonObject().apply {
                addProperty("command", "pm clear com.example.app")
                addProperty("reason", "testing")
            },
            context,
        )

        assertTrue(result.isError)
        assertEquals(listOf("android_run_adb_command"), context.confirmations)
    }

    @Test
    fun `no destructive tool can report success without a confirmation`() {
        // The invariant that matters. A tool may still fail early — clearing data for a
        // package that is not installed short-circuits before asking, which is right — but
        // it must never do the destructive thing and report success unasked.
        ToolRegistry.bySafety(ToolSafety.DESTRUCTIVE).forEach { tool ->
            val context = FakeToolContext(confirmationAnswer = false)
            val arguments = JsonObject().apply {
                addProperty("command", "echo hi")
                addProperty("reason", "testing")
                addProperty("permission", "android.permission.CAMERA")
                addProperty("packageName", "com.example.app")
                addProperty("host", "192.168.1.10")
                addProperty("port", 8888)
                addProperty("file", "shared_prefs/settings.xml")
                addProperty("key", "onboarding_seen")
                addProperty("type", "boolean")
                addProperty("value", "true")
            }

            // A throw means these arguments never got the tool as far as its decision, so
            // nothing was checked. This used to be swallowed, which is how set_http_proxy
            // passed while never asking at all.
            val result = runCatching { tool.execute(arguments, context) }.getOrElse { error ->
                fail("${tool.name} threw before reaching its confirmation, so it went unchecked: $error")
            }

            if (tool.name in context.confirmations) {
                assertTrue(result.isError, "${tool.name} carried on after the developer declined")
            } else {
                assertTrue(result.isError, "${tool.name} succeeded without asking the developer")
                // An error without asking is only acceptable as a known precondition failure;
                // anything else is a tool that skipped its confirmation and failed for some
                // other reason.
                assertTrue(
                    tool.name in SHORT_CIRCUIT_BEFORE_ASKING,
                    "${tool.name} returned an error without asking: ${result.text()}",
                )
            }
        }
    }

    @Test
    fun `a declined proxy change never writes to the device`() {
        val target = FakeToolContext.device("emulator-5554")
        val context = FakeToolContext(available = listOf(target), confirmationAnswer = false)

        val result = ToolRegistry.find("android_set_http_proxy")!!.execute(
            JsonObject().apply {
                addProperty("host", "192.168.1.10")
                addProperty("port", 8888)
            },
            context,
        )

        assertTrue(result.isError)
        assertEquals(listOf("android_set_http_proxy"), context.confirmations)
        verify(exactly = 0) {
            target.device.executeShellCommand(match { it.startsWith("settings put") }, any(), any(), any<TimeUnit>())
        }
    }

    @Test
    fun `a destructive tool that reaches its action asks first`() {
        // revoke_permission has no precondition to short-circuit on, so it must always ask.
        val context = FakeToolContext(confirmationAnswer = false)
        val result = ToolRegistry.find("android_revoke_permission")!!.execute(
            JsonObject().apply {
                addProperty("permission", "android.permission.CAMERA")
                addProperty("packageName", "com.example.app")
            },
            context,
        )

        assertTrue(result.isError)
        assertEquals(listOf("android_revoke_permission"), context.confirmations)
    }

    @Test
    fun `catastrophic commands are refused before the developer is even asked`() {
        val context = FakeToolContext(confirmationAnswer = true)
        val tool = RunAdbCommandTool()

        listOf("rm -rf /", "recovery --wipe_data", "mkfs.ext4 /dev/block/x", "dd if=/dev/zero of=/dev/block/x")
            .forEach { dangerous ->
                val result = tool.execute(
                    JsonObject().apply {
                        addProperty("command", dangerous)
                        addProperty("reason", "testing")
                    },
                    context,
                )
                assertTrue(result.isError, "'$dangerous' should be refused")
            }

        assertTrue(
            context.confirmations.isEmpty(),
            "a refused command must not reach the confirmation dialog at all",
        )
    }

    @Test
    fun `the arbitrary command tool requires a stated reason`() {
        val tool = RunAdbCommandTool()
        val result = runCatching {
            tool.execute(
                JsonObject().apply { addProperty("command", "ls") },
                FakeToolContext(confirmationAnswer = true),
            )
        }
        assertTrue(result.isFailure, "a missing reason must be rejected")
    }

    @Test
    fun `every tool declares a description and an object schema`() {
        ToolRegistry.all().forEach { tool ->
            assertTrue(tool.description.length > 20, "${tool.name} needs a real description")
            assertEquals("object", tool.inputSchema.get("type").asString, tool.name)
            assertTrue(tool.inputSchema.has("properties"), tool.name)
        }
    }

    @Test
    fun `every safety level has tools, so the panel grouping is never empty`() {
        // The MCP panel groups the catalogue under three headings; an empty group would
        // render a heading with nothing under it.
        ToolSafety.entries.forEach { safety ->
            assertTrue(
                ToolRegistry.bySafety(safety).isNotEmpty(),
                "no tools registered with safety $safety",
            )
        }
        assertEquals(
            ToolRegistry.all().size,
            ToolSafety.entries.sumOf { ToolRegistry.bySafety(it).size },
            "every tool must fall into exactly one safety group",
        )
    }

    @Test
    fun `safe actions are not silently treated as read-only`() {
        ToolRegistry.bySafety(ToolSafety.SAFE_ACTION).forEach { tool ->
            assertFalse(tool.safety == ToolSafety.READ_ONLY, tool.name)
        }
    }

    private companion object {
        /**
         * Destructive tools that, against the fake device, stop at a precondition before
         * asking. The fake reports no packages installed, so there is nothing to clear or
         * uninstall. Pinned so a tool cannot join this list by accident.
         */
        val SHORT_CIRCUIT_BEFORE_ASKING = setOf(
            "android_clear_app_data",
            "android_uninstall_app",
            "android_set_app_preference",
            "android_delete_app_preference",
        )
    }
}
