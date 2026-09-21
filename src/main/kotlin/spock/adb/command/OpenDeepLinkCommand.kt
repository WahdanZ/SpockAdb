package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

/**
 * Opens [uri] with an ACTION_VIEW intent and reads the verdict out of what the device said.
 *
 * One implementation for both entry points — the panel field and the MCP tool — so the two
 * cannot drift on what counts as a link that opened.
 *
 * A device that goes quiet is not a device that refused. `-W` makes `am` print nothing until
 * the launch has finished, and ddmlib's timeout is the longest gap it will wait *between*
 * chunks of output, so a slow cold start raises [ShellCommandUnresponsiveException] with no
 * message for a link that did open. Whatever arrived before that is still the best evidence
 * there is, and with no `Status:` line among it the result is "sent, not confirmed" rather
 * than an error the device never reported.
 */
internal fun IDevice.openDeepLinkWithAmStart(uri: String, packageName: String? = null): AmStartResult {
    val receiver = ShellOutputReceiver()
    try {
        executeShellCommand(
            AmStart.command(uri, packageName),
            receiver,
            AmStart.TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
    } catch (ignored: ShellCommandUnresponsiveException) {
        // Deliberately not rethrown — see above. The partial output is parsed below.
    }
    return AmStartResult.parse(uri, receiver.toString(), packageName)
}

class OpenDeepLinkCommand : Command<String, AmStartResult> {

    override fun execute(p: String, project: Project, device: IDevice): AmStartResult {
        require(p.isNotBlank()) { "Enter a deep link URI first." }

        return device.openDeepLinkWithAmStart(p)
    }
}
