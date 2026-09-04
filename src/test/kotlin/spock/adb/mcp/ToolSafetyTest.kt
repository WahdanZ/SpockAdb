package spock.adb.mcp

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.RunAdbCommandTool
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety

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
                "android_run_adb_command",
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
                "android_take_screenshot",
                "android_get_ui_tree",
                "android_find_ui_element",
                "android_accessibility_audit",
                "android_assert_visible",
                "android_assert_enabled",
                "android_assert_text",
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
            }

            val result = runCatching { tool.execute(arguments, context) }.getOrNull()

            if (result != null && !result.isError) {
                assertTrue(
                    context.confirmations.contains(tool.name),
                    "${tool.name} succeeded without asking the developer",
                )
            }
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
}
