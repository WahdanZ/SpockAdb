package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.progress.ProcessCanceledException
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.scaleText
import java.util.concurrent.TimeUnit

/** The three animation scales in Developer options, by the global setting that holds each. */
enum class AnimationScale(val key: String, val label: String) {
    WINDOW("window_animation_scale", "Window animation scale"),
    TRANSITION("transition_animation_scale", "Transition animation scale"),
    DURATION("animator_duration_scale", "Animator duration scale"),
}

/**
 * What an animation-scale write asked for, against what the device held before and after it.
 *
 * `settings put` exits 0 whether or not the value is kept, so the tool window and the MCP tool
 * both report from the read-back rather than from what they sent, as [HttpProxyWrite] does.
 *
 * @param requested the value sent, e.g. "0.0".
 * @param before the device's answer before the write, trimmed; "null" where it was never set.
 * @param after the device's answer after the write, trimmed.
 */
data class AnimationScaleWrite(
    val scale: AnimationScale,
    val requested: String,
    val before: String?,
    val after: String?,
) {
    /**
     * Compared as numbers: a device that stores "0" for "0.0" has taken it. Float `==` is safe
     * here because both sides parse the same short decimal literals, so equal text is equal bits.
     */
    val took: Boolean
        get() = requested.toFloatOrNull()?.let { it == after?.toFloatOrNull() } == true

    val message: String
        get() = if (took) {
            "${scale.label} set to ${describeAnimationScale(requested)}"
        } else {
            "${scale.label} is still ${describeAnimationScale(after)}: " +
                "the device did not accept ${describeAnimationScale(requested)}."
        }
}

/**
 * A device's answer as the settings screen would say it. A setting that was never written reads
 * as the literal "null", and Android then animates at 1×, so that is what it is called.
 */
internal fun describeAnimationScale(raw: String?): String =
    animationScaleValue(raw)?.let { scaleText(it.toString()) } ?: "not set (1×)"

/** The number a device's answer names, or null for "null", blank or anything not a number. */
internal fun animationScaleValue(raw: String?): Float? = raw?.trim()?.toFloatOrNull()

private const val SETTINGS_TIMEOUT_SECONDS = 15L

/** One blocking `settings get`. Null when the device answered nothing. */
internal fun IDevice.readAnimationScale(scale: AnimationScale): String? {
    val receiver = ShellOutputReceiver()
    executeShellCommand("settings get global ${scale.key}", receiver, SETTINGS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    // The receiver strips the trailing "\r\n"; trim covers a stray "\r" inside it too.
    return receiver.toString().trim().takeIf { it.isNotEmpty() }
}

/**
 * Writes [value] to [scale] and reads it back. One read-back is enough: `settings put` returns
 * once the value is stored, so there is nothing to wait for.
 */
internal fun IDevice.setAnimationScale(scale: AnimationScale, value: String): AnimationScaleWrite {
    val before = readAnimationScale(scale)
    executeShellCommand(
        "settings put global ${scale.key} ${ShellQuote.quote(value)}",
        ShellOutputReceiver(),
        SETTINGS_TIMEOUT_SECONDS,
        TimeUnit.SECONDS,
    )
    return AnimationScaleWrite(scale, value, before, readAnimationScale(scale))
}

/**
 * What [setAllAnimationScales] did before it finished or stopped.
 *
 * A failure part-way through must not discard the writes already made: the first scale can be
 * Off while the second threw, and a caller told only about the exception would believe nothing
 * changed — and lose the value to restore.
 *
 * @param writes the scales written and read back, in order.
 * @param failed the scale whose shell call threw, after which nothing more was tried.
 */
data class AnimationScaleWrites(
    val writes: List<AnimationScaleWrite>,
    val failed: AnimationScale? = null,
    val failure: Exception? = null,
) {
    val tookAll: Boolean get() = failed == null && writes.all { it.took }

    /**
     * Why [failed] is unknown. The shell call that threw may have been the write or either read,
     * so the value it holds is not claimed either way.
     */
    val failureMessage: String?
        get() = failed?.let {
            val reason = failure?.message?.takeIf { message -> message.isNotBlank() }
                ?: failure?.javaClass?.simpleName
            "${it.label} could not be set or read back ($reason); what it holds now is unknown, " +
                "and the scales after it were not tried."
        }
}

/**
 * All three scales set to [value], in the order the settings screen lists them. Stops at the
 * first scale whose shell call throws, and returns what was done before it.
 *
 * Catches every exception, not the four ddmlib declares: whatever ends the run — including a
 * runtime exception from a device that has gone — must not discard the writes already made.
 */
@Suppress("TooGenericExceptionCaught")
internal fun IDevice.setAllAnimationScales(value: String): AnimationScaleWrites {
    val writes = mutableListOf<AnimationScaleWrite>()
    for (scale in AnimationScale.entries) {
        try {
            writes += setAnimationScale(scale, value)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            return AnimationScaleWrites(writes, scale, e)
        }
    }
    return AnimationScaleWrites(writes)
}
