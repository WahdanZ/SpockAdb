package spock.adb.uitree

import com.android.ddmlib.IDevice
import spock.adb.CancellationSignal
import spock.adb.uitree.UiCaptureException.Kind

/**
 * The default display as `wm` reports it: effective density, and size in its natural
 * orientation. `wm size` does not rotate with the screen; the dump's rotation says how to.
 *
 * A field is null when it could not be read. None is ever guessed: a density assumed to be
 * 160 would quietly turn every dp estimate made from it into a wrong one.
 */
data class DisplayMetrics(
    val densityDpi: Int?,
    val naturalWidthPx: Int?,
    val naturalHeightPx: Int?,
) {
    companion object {
        val UNKNOWN = DisplayMetrics(null, null, null)
    }
}

/**
 * Reads [DisplayMetrics] as part of a capture.
 *
 * Best effort, unlike the dump it accompanies: a device that will not say its size still has
 * a screen worth reading, so a command that fails or answers something unparseable leaves its
 * fields null, and the observation lists that as a limit. A cancel and a lost device are the
 * exceptions — they end the whole capture, as they would have ended the dump.
 */
object DisplayMetricsReader {

    /**
     * Sends `wm size` and `wm density` as two commands, not one joined by `;`: each answer is
     * parsed on its own, and one command failing must not cost the other its answer.
     *
     * @throws UiCaptureException of kind [Kind.CANCELLED] or [Kind.DEVICE_UNAVAILABLE] only.
     */
    fun read(
        device: IDevice,
        cancellation: CancellationSignal = CancellationSignal.currentThread(),
        serial: String = device.serialNumber,
        timeoutSeconds: Long = READ_TIMEOUT_SECONDS,
    ): DisplayMetrics {
        val shell = UiCaptureShell(device, timeoutSeconds, serial, cancellation)
        val size = bestEffort(shell, "wm size")?.let(::parseSize)
        val density = bestEffort(shell, "wm density")?.let(DisplayDensity::parse)
        return DisplayMetrics(density, size?.first, size?.second)
    }

    /**
     * `Physical size: 1080x2400`, then `Override size: 720x1600` when `wm size` has been set.
     * The override is what apps are laid out against, so it wins, as it does for density.
     */
    fun parseSize(output: String): Pair<Int, Int>? = size(output, "Override") ?: size(output, "Physical")

    private fun size(output: String, kind: String): Pair<Int, Int>? {
        val match = Regex("(?m)^\\s*$kind size: ([0-9]+)x([0-9]+)\\s*$").find(output) ?: return null
        val width = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val height = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return width to height
    }

    private fun bestEffort(shell: UiCaptureShell, command: String): String? = try {
        shell.run(command)
    } catch (e: UiCaptureException) {
        if (e.kind == Kind.CANCELLED || e.kind == Kind.DEVICE_UNAVAILABLE) throw e
        null
    }

    /** `wm` answers from memory; a device slower than this is not going to be measured. */
    const val READ_TIMEOUT_SECONDS = 5L
}
