package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the shape of `[Unreleased]`, because CI turns it into the release notes.
 *
 * `./gradlew getChangelog --unreleased` is what `build.yml` puts in the GitHub release draft
 * and what ships as the JetBrains Marketplace change notes. It parses markdown, so two
 * ordinary-looking edits make entries vanish from the release without anything failing:
 *
 *  - a **repeated section heading**: sections are keyed by name, so a second `### Fixed`
 *    replaces the first rather than extending it
 *  - a **blank line between list items**: it ends the list, and everything after it in that
 *    section is dropped
 *
 * Both had happened. Ten of twenty entries were missing from the v4.0.3 draft, including the
 * release's headline feature, and the draft looked perfectly plausible — the notes read as a
 * complete list, just a shorter one.
 */
class ChangelogShapeTest {

    @Test
    fun `no section heading is repeated inside Unreleased`() {
        val duplicates = unreleased()
            .filter { it.startsWith("### ") }
            .groupingBy { it.trim() }
            .eachCount()
            .filterValues { it > 1 }

        assertTrue(duplicates.isEmpty()) {
            "Repeated in [Unreleased]: ${duplicates.keys.joinToString()}. A section is keyed by " +
                "name, so the later one replaces the earlier and its entries never reach the " +
                "release notes. Merge them into one section."
        }
    }

    @Test
    fun `no blank line interrupts the entries inside Unreleased`() {
        val lines = unreleased()
        val offenders = lines.indices.filter { i ->
            // A blank line is fine around a heading; between two list items it ends the list.
            // The item before it is found by walking back over the whole entry rather than one
            // line: every entry here wraps, so the line above a blank one is almost always a
            // continuation, and looking only at that missed the case this test exists for.
            lines[i].isBlank() &&
                lines.take(i).takeLastWhile { it.isNotBlank() }.any { it.startsWith("- ") } &&
                lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.startsWith("- ") == true
        }

        assertTrue(offenders.isEmpty()) {
            "A blank line separates list items inside [Unreleased], which ends the list: " +
                "everything after it is dropped from the release notes. Offending entries " +
                "follow: ${offenders.map { lines.getOrNull(it + 1)?.take(60) }}"
        }
    }

    @Test
    fun `Unreleased is the first section and appears once`() {
        val headings = changelog().readLines().filter { it.startsWith("## [") }

        assertEquals(
            1,
            headings.count { it.trim() == "## [Unreleased]" },
            "there must be exactly one [Unreleased] heading",
        )
        assertTrue(
            headings.first().trim() == "## [Unreleased]",
            "[Unreleased] must come first, or released notes get rewritten: ${headings.first()}",
        )
    }

    /** The lines between `## [Unreleased]` and the next version heading. */
    private fun unreleased(): List<String> {
        val lines = changelog().readLines()
        val start = lines.indexOfFirst { it.trim() == "## [Unreleased]" }
        assertTrue(start >= 0, "CHANGELOG.md has no [Unreleased] section")
        val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## [") }
        return if (end < 0) lines.drop(start + 1) else lines.subList(start + 1, start + 1 + end)
    }

    private fun changelog(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            candidate.resolve("CHANGELOG.md").takeIf { it.isFile }?.let { return it }
            candidate = candidate.parentFile
        }
        error("could not find CHANGELOG.md from ${System.getProperty("user.dir")}")
    }
}
