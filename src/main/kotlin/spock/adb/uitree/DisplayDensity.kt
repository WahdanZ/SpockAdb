package spock.adb.uitree

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

/** Effective default-display density. Failure leaves size checks explicitly unavailable. */
internal object DisplayDensity {
    fun read(device: IDevice): Int? = runCatching {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand("wm density", receiver, READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        parse(receiver.toString())
    }.getOrNull()

    fun parse(output: String): Int? {
        val override = density(output, "Override")
        return override ?: density(output, "Physical")
    }

    private fun density(output: String, kind: String): Int? =
        Regex("(?m)^\\s*$kind density: ([0-9]+)\\s*$").find(output)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    private const val READ_TIMEOUT_SECONDS = 5L
}
