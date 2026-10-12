package spock.adb.mcp.tools

import com.google.gson.JsonObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * A smaller screenshot for an agent that asks for one.
 *
 * A full-size PNG of a 1080x2400 screen is about 1.2 MB of base64 in every call, which an agent
 * pays for even when it only wants to see which screen is up. Scaling and re-encoding happen here,
 * in the plugin, with ImageIO on a [BufferedImage]: neither needs a display, so it works in a
 * headless IDE and in tests. Full size and PNG stays the default, and is passed through unread.
 */
internal object ScreenshotEncoding {

    const val MIN_SCALE = 0.1
    const val MAX_SCALE = 1.0
    val SCALES = MIN_SCALE..MAX_SCALE

    enum class Format(val argument: String, val mimeType: String, val imageIoName: String) {
        PNG("png", "image/png", "png"),
        JPEG("jpeg", "image/jpeg", "jpg"),
        ;

        companion object {
            fun fromArgument(name: String): Format? = entries.firstOrNull { it.argument.equals(name.trim(), true) }
        }
    }

    /** What the call asked for. Full size and PNG is the capture itself, passed through unread. */
    class Request(val scale: Double, val format: Format) {
        val isPassThrough: Boolean get() = scale == MAX_SCALE && format == Format.PNG
    }

    /**
     * The `scale` and `format` arguments, or why they cannot be used — read before the device is
     * asked anything, so a bad argument costs no capture.
     *
     * A scale is read by [optionalNumber]: `"0.5"` sent as a string is refused rather than parsed,
     * and one outside [SCALES] is refused rather than clamped.
     */
    fun request(arguments: JsonObject): Result<Request> = runCatching {
        val scale = arguments.optionalNumber("scale", MAX_SCALE, SCALES)
        val formatName = arguments.optionalString("format") ?: Format.PNG.argument
        val format = requireNotNull(Format.fromArgument(formatName)) {
            "Unknown format '$formatName'. Use one of: ${Format.entries.joinToString { it.argument }}."
        }
        Request(scale, format)
    }

    class Encoded(
        val bytes: ByteArray,
        val format: Format,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    /**
     * [png] scaled by [scale] and written as [format].
     *
     * @throws IllegalStateException when [png] cannot be decoded or the format cannot be written.
     */
    fun encode(png: ByteArray, scale: Double, format: Format): Encoded {
        val source = ImageIO.read(ByteArrayInputStream(png))
            ?: error("The screenshot could not be decoded to scale it.")
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        // JPEG has no alpha channel, and ImageIO's JPEG writer refuses an image that has one.
        val type = if (format == Format.JPEG) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val scaled = BufferedImage(width, height, type)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        val out = ByteArrayOutputStream()
        check(ImageIO.write(scaled, format.imageIoName, out)) { "No ${format.argument} writer is available." }
        return Encoded(out.toByteArray(), format, width, height, source.width, source.height)
    }

    /**
     * What an agent needs to tap something it found in a scaled image: the factor back to device
     * pixels, which `android_tap` takes.
     */
    fun scaleNote(scale: Double, encoded: Encoded): String =
        "Screenshot scaled ${decimal(scale)} (${encoded.width}x${encoded.height} of " +
            "${encoded.sourceWidth}x${encoded.sourceHeight}) — multiply image coordinates by " +
            "${decimal(1 / scale)} for android_tap."

    /** `0.5`, `2`, `3.33`: two decimals at most, and none that are zero. */
    private fun decimal(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
