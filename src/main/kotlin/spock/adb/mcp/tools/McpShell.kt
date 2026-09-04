package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

/**
 * Shell access for MCP tools.
 *
 * Output is capped. An agent pays for every token of a tool result, and an unbounded
 * `dumpsys` or `logcat` dump can run to megabytes — enough to blow a context window and
 * cost real money for information nobody asked for.
 */
internal object McpShell {

    const val DEFAULT_TIMEOUT_SECONDS = 20L
    const val DEFAULT_MAX_CHARS = 40_000

    fun run(
        device: IDevice,
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(command, receiver, timeoutSeconds, TimeUnit.SECONDS)
        return receiver.toString().truncateForAgent(maxChars)
    }

    fun String.truncateForAgent(maxChars: Int): String = when {
        length <= maxChars -> this
        else -> take(maxChars) +
            "\n\n[truncated: ${length - maxChars} more characters. " +
            "Narrow the request — filter by package, level or line count — to see the rest.]"
    }
}
