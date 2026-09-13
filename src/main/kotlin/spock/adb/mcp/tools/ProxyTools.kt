package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.clearHttpProxy
import spock.adb.command.HttpProxy
import spock.adb.command.HttpProxyWrite
import spock.adb.getHttpProxy
import spock.adb.setHttpProxy

/**
 * `android_get_http_proxy` — read the device's global HTTP proxy.
 *
 * Read-only deliberately, and it is what makes the mutating tools safe to reason about: an
 * agent can always check the state before and after without needing approval for the check
 * itself.
 */
class GetHttpProxyTool : AdbTool {
    override val name = "android_get_http_proxy"
    override val description =
        "Read the device's global HTTP proxy. Reports no proxy when the device connects directly."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val proxy = device.getHttpProxy()
        return ToolResult.text(
            proxy?.let { "HTTP proxy is $it" } ?: "No HTTP proxy is set; the device connects directly.",
        )
    }
}

/**
 * `android_set_http_proxy` — route device traffic through a debugging proxy.
 *
 * Classified [ToolSafety.DESTRUCTIVE] rather than SAFE_ACTION. By the letter of the enum
 * this is a SAFE_ACTION: a developer does it by hand routinely and undoes it by clearing.
 * What tips it is the failure mode — the setting is global, survives a reboot, and
 * redirects *all* device traffic through a host, so a device left pointing at a proxy that
 * is no longer listening fails every request with nothing on screen to explain why. That is
 * exactly the state an agent should not be able to leave behind without the developer
 * agreeing to that specific call.
 *
 * Relaxing this to SAFE_ACTION later is a one-line change; tightening it after clients have
 * come to rely on auto-approval is not.
 */
class SetHttpProxyTool : AdbTool {
    override val name = "android_set_http_proxy"
    override val description =
        "Point the device's global HTTP proxy at a host, so its traffic can be inspected " +
            "(Charles, Proxyman, mitmproxy). The setting is global and survives a reboot — " +
            "clear it with android_clear_http_proxy when finished, or the device keeps " +
            "routing through a proxy that may no longer be listening. Apps that use their " +
            "own HTTP stack or pin certificates will not be captured."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        string("host", "Host the proxy listens on, for example 192.168.1.10.", required = true)
        integer("port", "Port the proxy listens on, for example 8888.", required = true)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val requested = HttpProxy.of(
            arguments.requiredString("host"),
            arguments.requiredInt("port"),
        )

        val approved = context.confirmDestructive(
            name,
            "Route all of this device's traffic through $requested. The setting is global " +
                "and survives a reboot until it is cleared.",
            target,
        )
        if (!approved) {
            return ToolResult.error("The developer declined to set the HTTP proxy to $requested.")
        }
        return target.device.setHttpProxy(requested).toToolResult()
    }
}

/** `android_clear_http_proxy` — restore direct connections. */
class ClearHttpProxyTool : AdbTool {
    override val name = "android_clear_http_proxy"
    override val description =
        "Clear the device's global HTTP proxy so it connects directly again."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        context.requireIDevice(arguments.optionalString("deviceSerial")).clearHttpProxy().toToolResult()
}

/**
 * Reports what the device holds after a write, not what was asked for — the same read-back
 * the tool window reports from, so the two cannot disagree about whether a write took.
 */
private fun HttpProxyWrite.toToolResult(): ToolResult =
    if (took) ToolResult.text(message) else ToolResult.error(message)
