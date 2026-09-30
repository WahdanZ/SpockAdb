package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.SCALES
import spock.adb.animationScaleEntry
import spock.adb.command.AnimationScaleWrite
import spock.adb.command.animationScaleValue
import spock.adb.command.describeAnimationScale
import spock.adb.command.setAllAnimationScales

/**
 * `android_set_animations` — the three animation scales at once, read back.
 *
 * A safe action rather than a destructive one: it is what the Developer options dropdowns do,
 * it is undone by calling it again, and the result says which value to call it with. Behind a
 * confirmation, agents reached for `settings put` through the shell tool instead.
 */
class SetAnimationsTool : AdbTool {
    override val name = "android_set_animations"
    override val description =
        "Set the window, transition and animator-duration animation scales together. Scale 0 turns " +
            "animations off, which makes UI automation with the element and wait tools reliable. " +
            "Each value is read back from the device, and the result gives the previous values so " +
            "they can be restored by calling this again. The same as Home › Developer options."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        enumeration(
            "scale",
            "The scale for all three: 0 is off, 1 is normal speed, larger is slower.",
            ARGUMENTS,
            required = true,
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // A JSON number arrives here as its string form, so 0.5 and "0.5" are the same request.
        val raw = arguments.requiredString("scale")
        val entry = animationScaleEntry(raw, SCALES)
            ?: return ToolResult.error("Unknown scale '$raw'. Use one of: ${ARGUMENTS.joinToString()}.")
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        // Straight to the device rather than through SetAllAnimationScalesCommand: commands take
        // a Project, which a tool context need not have, and the value is validated above.
        val result = device.setAllAnimationScales(entry)
        val writes = result.writes

        val lines = writes.map {
            "${it.scale.key}: ${describeAnimationScale(it.before)} → ${describeAnimationScale(it.after)}"
        }
        // A failure part-way still reports the scales already changed, and how to put them back.
        val hint = if (writes.isEmpty()) "Nothing was changed before it." else restoreHint(writes)
        val problems = listOfNotNull(result.failureMessage) + writes.filterNot { it.took }.map { it.message }
        val text = (problems + lines + hint).joinToString("\n")
        return if (result.tookAll) ToolResult.text(text) else ToolResult.error(text)
    }

    /** How to put back what was there, which one call can only do when all three matched. */
    private fun restoreHint(writes: List<AnimationScaleWrite>): String {
        // A setting never written animates at 1×, so it restores as 1.
        val previous = writes.map { animationScaleValue(it.before) ?: 1f }.distinct()
        val argument = previous.singleOrNull()
            ?.let { value -> ARGUMENTS.firstOrNull { it.toFloat() == value } }
        return when {
            argument != null -> "To restore: android_set_animations with scale $argument."
            previous.size == 1 ->
                "The previous value (${describeAnimationScale(previous.single().toString())}) is not one " +
                    "this tool sets, so restore it in Developer options."
            else ->
                "The previous values differed (" +
                    writes.joinToString { "${it.scale.key} ${describeAnimationScale(it.before)}" } +
                    "); this tool sets all three to one value, so restore them individually in " +
                    "Developer options."
        }
    }

    private companion object {
        /** [SCALES] as an agent writes them. */
        val ARGUMENTS = listOf("0", "0.5", "1", "1.5", "2", "5", "10")
    }
}
