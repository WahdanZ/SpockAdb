package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.AppSettingService
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
 *
 * `host` and `port` default to the proxy last set in the tool window, so an agent can turn the
 * developer's usual proxy on without being told its address. This tool reads that value but
 * never saves one: what the panel remembers is the developer's choice, not an agent's.
 *
 * @param rememberedProxy the panel's remembered `host:port`, empty when never set. Injected so
 *   the tool can be tested without an IntelliJ Application, and only called when an argument
 *   is missing — no other tool touches [AppSettingService], and a call that names both should
 *   not start to.
 */
class SetHttpProxyTool(
    private val rememberedProxy: () -> String = { AppSettingService.getInstance().lastHttpProxy() },
) : AdbTool {
    override val name = "android_set_http_proxy"
    override val description =
        "Point the device's global HTTP proxy at a host, so its traffic can be inspected " +
            "(Charles, Proxyman, mitmproxy). The setting is global and survives a reboot — " +
            "clear it with android_clear_http_proxy when finished, or the device keeps " +
            "routing through a proxy that may no longer be listening. Apps that use their " +
            "own HTTP stack or pin certificates will not be captured."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        string(
            "host",
            "Host the proxy listens on, for example 192.168.1.10. Defaults to the host of the " +
                "proxy last set in the SpockAdb tool window.",
        )
        integer(
            "port",
            "Port the proxy listens on, for example 8888. Defaults to the port of the proxy " +
                "last set in the SpockAdb tool window.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // Resolved before the device is looked up or the developer is asked, so a call with
        // nothing to fall back on fails on its own terms rather than after a confirmation.
        val requested = try {
            resolveProxy(arguments)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Could not work out which proxy to set.")
        }
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))

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

    /**
     * Explicit arguments win; each one left out comes from the remembered proxy.
     *
     * @throws IllegalArgumentException naming what to pass, when an argument is missing and
     *   there is no usable remembered proxy, or when the resolved host or port is invalid.
     */
    private fun resolveProxy(arguments: JsonObject): HttpProxy {
        val host = arguments.optionalString("host")
        val port = arguments.optionalInt("port")
        if (host != null && port != null) return HttpProxy.of(host, port)

        val missing = listOfNotNull("host".takeIf { host == null }, "port".takeIf { port == null })
            .joinToString(" and ")
        val raw = rememberedProxy().trim()
        require(raw.isNotEmpty()) {
            "No $missing given, and no proxy has been set in the SpockAdb tool window to fall " +
                "back on. Pass host and port, for example host 192.168.1.10 and port 8888, or " +
                "set a proxy once in the tool window's Network section."
        }
        val remembered = try {
            HttpProxy.fromInput(raw)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "No $missing given, and the proxy remembered by the SpockAdb tool window, " +
                    "'$raw', is not usable: ${e.message} Pass host and port, or set the proxy " +
                    "again in the tool window.",
                e,
            )
        }
        return HttpProxy.of(host ?: remembered.host, port ?: remembered.port)
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
