package spock.adb.device.ops

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.TimeUnit

/** The package every test here acts on. */
internal const val PACKAGE = "com.example.app"

/** What the device answers when everything is installed, modern and working. */
internal fun healthyDevice(command: String): String = when {
    command.startsWith("pm list packages") -> "package:$PACKAGE"
    // Read by getDefaultActivityForApplication to decide which resolve command to send.
    command.startsWith("getprop ro.build.version.sdk") -> "34"
    command.startsWith("cmd package resolve-activity") -> "$PACKAGE/.MainActivity"
    // A successful cache clear reports its own exit status — see AppCacheShell.
    command.startsWith("run-as") -> "rc=0"
    else -> ""
}

/**
 * A device whose shell answers each command with [reply], recording what it was sent.
 *
 * The recorded list is the assertion that matters for these tests: what the two entry paths
 * send to the device is what "the same operation" means.
 */
internal fun scriptedDevice(reply: (String) -> String = ::healthyDevice): Pair<IDevice, List<String>> {
    val device = mockk<IDevice>(relaxed = true)
    val commands = mutableListOf<String>()
    val command = slot<String>()
    val receiver = slot<IShellOutputReceiver>()
    every {
        device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
    } answers {
        commands += command.captured
        val bytes = reply(command.captured).toByteArray()
        receiver.captured.addOutput(bytes, 0, bytes.size)
        receiver.captured.flush()
    }
    return device to commands
}
