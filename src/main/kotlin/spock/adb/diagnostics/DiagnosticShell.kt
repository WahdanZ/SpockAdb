package spock.adb.diagnostics

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

/** A blocking shell read for sections. Callers are already off the EDT. */
internal object DiagnosticShell {

    private const val TIMEOUT_SECONDS = 20L

    fun run(device: IDevice, command: String): String {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(command, receiver, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return receiver.toString()
    }

    /** Keeps one value from blowing the summary's size budget, and says that it was cut. */
    fun clip(value: String, max: Int = MAX_VALUE_CHARS): String =
        if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"

    const val MAX_VALUE_CHARS = 200
}
