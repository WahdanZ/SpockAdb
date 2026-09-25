package spock.adb.device.ops

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Capturing the screen as a PNG.
 *
 * `android_take_screenshot` had the only copy. The Diagnose tab needs the same picture, and a
 * second copy would be the one that forgot why capture goes through the shell: ddmlib's
 * `IDevice.getScreenshot()` is a stub in Android Studio that fails with "This method is not used
 * in Android Studio", and its shell channel decodes everything as text, so raw PNG bytes are
 * corrupted before a caller sees them. The device base64-encodes; this decodes.
 *
 * One instance is one capture, on one thread.
 */
class ScreenshotOperations(
    private val device: IDevice,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    /**
     * The screen as PNG bytes.
     *
     * @throws IllegalStateException saying what the device returned instead — most often a
     *   window protected by `FLAG_SECURE`, which blocks capture.
     */
    fun capture(): ByteArray {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand("screencap -p | base64", receiver, timeoutSeconds, TimeUnit.SECONDS)

        // The device wraps base64 at 76 columns, and a failing command prints its diagnostics
        // to the same stream in plain text.
        val output = receiver.toString()
        val png = try {
            Base64.getDecoder().decode(output.filterNot { it.isWhitespace() })
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "`screencap -p` did not return an image. The device said: ${output.take(MAX_ERROR_CHARS)}",
                e,
            )
        }
        check(png.looksLikePng()) {
            "The device returned ${png.size} bytes that are not a PNG. The screen may be " +
                "protected by FLAG_SECURE, which blocks capture."
        }
        return png
    }

    private fun ByteArray.looksLikePng() =
        size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15L
        private const val MAX_ERROR_CHARS = 2_000

        /** The eight bytes every PNG starts with. */
        private val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
