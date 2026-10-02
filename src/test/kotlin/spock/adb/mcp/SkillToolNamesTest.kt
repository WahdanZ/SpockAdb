package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import spock.adb.timeline.TimelineCategory
import java.io.File

/**
 * Keeps the Agent Skill and the MCP docs naming tools that exist.
 *
 * An agent follows `skills/spock-adb/SKILL.md` literally: a playbook step naming a renamed tool
 * is a call that fails mid-investigation, and a destructive tool missing from the skill's list is
 * one the agent was never told to explain before calling. Both are declarative, so they are read
 * off disk like [ReadmeToolCountTest] rather than exercised.
 */
class SkillToolNamesTest {

    private val registered = ToolRegistry.all().map { it.name }.toSet()

    /**
     * `android_get_debug_timeline`'s category values that share the `flutter_` prefix, documented
     * next to it: argument values, not tools. Named one by one, so a future `flutter_` *tool*
     * missing from the registry is still caught.
     */
    private val categoryValues = setOf("flutter_error", "flutter_frame")

    @Test
    fun `the allowed category values are timeline categories`() {
        val names = TimelineCategory.entries.map { it.name.lowercase() }
        assertTrue(names.containsAll(categoryValues), "$categoryValues are not all in $names")
    }

    @Test
    fun `every documented tool name is a registered tool`() {
        val stale = DOCUMENTS.flatMap { file ->
            TOOL_NAME.findAll(file.readText())
                .map { it.value }
                .filter { it !in registered && it !in categoryValues }
                .distinct()
                .map { "${file.path}: $it" }
                .toList()
        }

        assertTrue(stale.isEmpty()) {
            "These documents name tools ToolRegistry does not have — rename or remove them:\n" +
                stale.joinToString("\n")
        }
    }

    @Test
    fun `the skill names tools at all`() {
        // A scan that finds nothing passes every check above while checking none of them.
        val named = TOOL_NAME.findAll(SKILL.readText()).map { it.value }.toSet()
        assertTrue(named.size >= MIN_TOOLS_IN_SKILL) {
            "SKILL.md names ${named.size} tools; the pattern `${TOOL_NAME.pattern}` probably no longer matches"
        }
    }

    @Test
    fun `the skill lists exactly the destructive tools`() {
        val text = SKILL.readText()
        assertTrue(DESTRUCTIVE_START in text && DESTRUCTIVE_END in text) {
            "SKILL.md lost its $DESTRUCTIVE_START / $DESTRUCTIVE_END markers"
        }
        val listed = TOOL_NAME
            .findAll(text.substringAfter(DESTRUCTIVE_START).substringBefore(DESTRUCTIVE_END))
            .map { it.value }
            .toSet()

        assertEquals(
            ToolRegistry.bySafety(ToolSafety.DESTRUCTIVE).map { it.name }.toSet(),
            listed,
            "SKILL.md's destructive list differs from the registry. An agent asks before " +
                "calling what that list names, so update it with the change.",
        )
    }

    private companion object {
        val SKILL = File("skills/spock-adb/SKILL.md")

        val DOCUMENTS = listOf(
            SKILL,
            File("skills/spock-adb/README.md"),
            File("README.md"),
            File("docs/MCP.md"),
        )

        /**
         * Ends on a letter so `android_get_` in prose about a prefix is not taken for a name. Takes
         * the `ios_` and `flutter_` prefixes too, so those tools are checked the day they are named.
         */
        val TOOL_NAME = Regex("""\b(?:android|ios|flutter)_[a-z_]*[a-z]\b""")

        const val MIN_TOOLS_IN_SKILL = 20
        const val DESTRUCTIVE_START = "<!-- destructive-tools -->"
        const val DESTRUCTIVE_END = "<!-- /destructive-tools -->"
    }
}
