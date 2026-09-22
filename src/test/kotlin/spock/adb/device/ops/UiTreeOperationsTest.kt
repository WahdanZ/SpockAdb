package spock.adb.device.ops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.uitree.UiNode
import java.io.File

/**
 * Capturing the screen, which the UI Inspector tab and the `android_get_ui_tree` family had a
 * copy of each.
 */
class UiTreeOperationsTest {

    @Test
    fun `a capture dumps, reads the dump back and removes it`() {
        val (device, commands) = scriptedDevice { command ->
            if (command.startsWith("cat")) screen(listOf("Continue")) else ""
        }

        UiTreeOperations(device).read()

        assertEquals(
            listOf(
                "uiautomator dump ${UiTreeOperations.DUMP_PATH}",
                "cat '${UiTreeOperations.DUMP_PATH}'",
                "rm -f '${UiTreeOperations.DUMP_PATH}'",
            ),
            commands,
        )
    }

    @Test
    fun `a refused dump is raised with what the device said`() {
        val (device, commands) = scriptedDevice { command ->
            if (command.startsWith("uiautomator")) "ERROR: could not get idle state." else ""
        }

        val thrown = assertThrows<IllegalStateException> { UiTreeOperations(device).read() }

        assertTrue(thrown.message!!.contains("could not get idle state"), thrown.message)
        assertTrue(commands.none { it.startsWith("cat") }, "nothing to read back: $commands")
    }

    @Test
    fun `an empty dump is refused rather than parsed into an empty screen`() {
        val (device, _) = scriptedDevice { "" }

        assertThrows<IllegalStateException> { UiTreeOperations(device).read() }
    }

    @Test
    fun `a screen too large for the agent's old cap is still parsed whole`() {
        // The agent's copy read the XML through the 400,000-character cap meant for text
        // returned to an agent, so a busy screen reached the parser cut off mid-element. The
        // dump is never shown raw — it is parsed here — so there is nothing to cap.
        val labels = (1..LARGE_SCREEN_NODES).map { "Row $it" }
        val xml = screen(labels)
        assertTrue(xml.length > OLD_AGENT_CAP, "fixture is not large enough to prove anything")
        val (device, _) = scriptedDevice { command -> if (command.startsWith("cat")) xml else "" }

        val tree = UiTreeOperations(device).read()

        assertTrue(
            tree.root!!.flatten().any { it.text == "Row $LARGE_SCREEN_NODES" },
            "the last row of the screen did not survive the capture",
        )
    }

    @Test
    fun `only one place in the plugin dumps the UI`() {
        // The two copies dumped to different files under different names, so a failed cleanup
        // left litter neither side would recognise. This is the guard against a third.
        // The opening quote is what separates sending the command from naming it in a comment.
        val dumpers = File(MAIN_SOURCES).walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.readText().contains("\"uiautomator dump") }
            .map { it.name }
            .toList()

        assertEquals(listOf("UiTreeOperations.kt"), dumpers)
    }

    private fun UiNode.flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }

    /** A `uiautomator` dump of a screen showing [labels], one text node each. */
    private fun screen(labels: List<String>): String = buildString {
        appendLine("""<hierarchy rotation="0">""")
        appendLine(
            """  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" """ +
                """package="p" content-desc="" checkable="false" checked="false" clickable="false" """ +
                """enabled="true" focusable="false" focused="false" scrollable="false" """ +
                """long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2220]">""",
        )
        labels.forEachIndexed { index, label ->
            appendLine(
                """    <node index="$index" text="$label" resource-id="" """ +
                    """class="android.widget.TextView" package="p" content-desc="" checkable="false" """ +
                    """checked="false" clickable="false" enabled="true" focusable="false" """ +
                    """focused="false" scrollable="false" long-clickable="false" password="false" """ +
                    """selected="false" bounds="[0,${index * 2}][1080,${index * 2 + 2}]" />""",
            )
        }
        appendLine("  </node>")
        append("</hierarchy>")
    }

    private companion object {
        const val MAIN_SOURCES = "src/main/kotlin"

        /** What the agent path used to truncate the XML at. */
        const val OLD_AGENT_CAP = 400_000

        /** Enough rows to clear [OLD_AGENT_CAP] comfortably. */
        const val LARGE_SCREEN_NODES = 1_500
    }
}
