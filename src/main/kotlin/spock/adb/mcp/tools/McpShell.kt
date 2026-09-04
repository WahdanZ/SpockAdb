package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import java.util.Base64
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

    /**
     * Runs a command whose output is binary, and returns the raw bytes.
     *
     * ddmlib's shell channel decodes everything it receives as text, so raw bytes coming
     * back from something like `screencap -p` are corrupted before a caller ever sees them.
     * Encoding on the device and decoding here keeps the payload ASCII for the whole trip.
     *
     * The output is deliberately not truncated: half a PNG is not a smaller PNG.
     *
     * @throws IllegalStateException when the device did not return decodable base64, carrying
     *   the raw output so the caller can report what the device actually said.
     */
    fun runBinary(
        device: IDevice,
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): ByteArray {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand("$command | base64", receiver, timeoutSeconds, TimeUnit.SECONDS)

        // The device wraps base64 at 76 columns, and a failing command prints its diagnostics
        // to the same stream in plain text.
        val output = receiver.toString()
        return try {
            Base64.getDecoder().decode(output.filterNot { it.isWhitespace() })
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "`$command` did not return binary output. The device said:\n" +
                    output.truncateForAgent(DEFAULT_MAX_CHARS),
                e,
            )
        }
    }

    fun String.truncateForAgent(maxChars: Int): String = when {
        length <= maxChars -> this
        else -> take(maxChars) +
            "\n\n[truncated: ${length - maxChars} more characters. " +
            "Narrow the request — filter by package, level or line count — to see the rest.]"
    }
}
