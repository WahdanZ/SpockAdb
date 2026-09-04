package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry
import java.io.File

/**
 * Keeps the tool count in the README true.
 *
 * It has already been wrong twice. One of the two places it appears sits inside the
 * `<!-- Plugin description -->` block, which the Gradle build extracts into the JetBrains
 * Marketplace listing — so a stale number is not an internal documentation nit, it is on the
 * public plugin page until somebody happens to notice.
 *
 * Read off disk in the same spirit as [spock.adb.actions.ActionRegistrationTest]: what breaks
 * users here is declarative, and checking it needs no running IDE.
 */
class ReadmeToolCountTest {

    private val readme = File("README.md").readText()

    @Test
    fun `every stated tool count matches the registry`() {
        val stated = COUNT.findAll(readme).map { it.groupValues[1].toInt() }.toList()

        // A reworded README must fail here rather than quietly disable the check: a scan that
        // finds nothing and passes is not a guard, it is a guard-shaped hole.
        assertTrue(stated.isNotEmpty()) {
            "No tool count found in README.md — the wording changed. Update the pattern " +
                "`${COUNT.pattern}` so this stays a real check."
        }

        val registered = ToolRegistry.all().size
        stated.forEach { count ->
            assertEquals(
                registered,
                count,
                "README.md says $count tools; ToolRegistry has $registered",
            )
        }
    }

    @Test
    fun `the Marketplace description is one of the places that states it`() {
        // buildPlugin lifts this block into the Marketplace description, so it is the copy
        // read by people who have not installed the plugin yet — the one worth pinning.
        val description = readme
            .substringAfter(DESCRIPTION_START, "")
            .substringBefore(DESCRIPTION_END, "")

        assertTrue(description.isNotBlank(), "the plugin description markers are missing")
        assertTrue(
            COUNT.containsMatchIn(description),
            "the Marketplace description no longer states a tool count, so the count that " +
                "reaches the plugin page is no longer checked",
        )
    }

    private companion object {
        val COUNT = Regex("""(\d+) strongly typed tools""")
        const val DESCRIPTION_START = "<!-- Plugin description -->"
        const val DESCRIPTION_END = "<!-- Plugin description end -->"
    }
}
