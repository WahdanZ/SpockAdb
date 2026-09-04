package spock.adb.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the Action/Keymap contract by reading plugin.xml directly.
 *
 * Instantiating actions needs a running IDE, but the things that actually break users are
 * declarative: an action class that does not exist, an ID renamed out from under someone's
 * keymap binding, or a default shortcut sneaking in and stealing a combination.
 */
class ActionRegistrationTest {

    private val rawPluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    /** Comments are stripped: the descriptor explains the no-shortcuts policy in prose that
     *  mentions the very element this test asserts is absent. */
    private val pluginXml = rawPluginXml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private val declaredActions: List<Pair<String, String>> =
        Regex("""<action\s+id="([^"]+)"\s+class="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(pluginXml)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private companion object {
        const val EXPECTED_MINIMUM = 15
    }

    @Test
    fun `actions are declared`() {
        assertTrue(declaredActions.size >= EXPECTED_MINIMUM, "found ${declaredActions.size} actions")
    }

    @Test
    fun `every declared action class exists on the classpath`() {
        // A typo here fails at runtime with the action silently missing from Find Action.
        declaredActions.forEach { (id, className) ->
            val exists = runCatching { Class.forName(className) }.isSuccess
            assertTrue(exists, "$id declares missing class $className")
        }
    }

    @Test
    fun `action ids are unique`() {
        val ids = declaredActions.map { it.first }
        assertEquals(ids.size, ids.toSet().size, "duplicate action ids")
    }

    @Test
    fun `no default keyboard shortcuts are declared`() {
        // Deliberate: a binding free in one keymap is taken in another, and a plugin that
        // silently claims a combination the developer already uses is worse than none.
        assertFalse(
            pluginXml.contains("<keyboard-shortcut"),
            "Spock ADB must not ship default shortcuts; users assign them in Keymap",
        )
    }

    @Test
    fun `actions live in one group so they appear together in the Keymap`() {
        assertTrue(pluginXml.contains("""<group id="SpockAdb.ActionGroup""""))
        assertTrue(pluginXml.contains("""text="Spock ADB""""))
    }

    @Test
    fun `the operations a user reaches for are all exposed as actions`() {
        val ids = declaredActions.map { it.first }.toSet()
        listOf(
            "spock.adb.actions.RestartAppAction",
            "spock.adb.actions.ForceStopAppAction",
            "spock.adb.actions.ClearAppDataAction",
            "spock.adb.actions.UninstallAppAction",
            "spock.adb.actions.GetCurrentActivityAction",
            "spock.adb.actions.GetCurrentFragmentAction",
            "spock.adb.actions.OpenLogcatAction",
            "spock.adb.actions.OpenCommandCenterAction",
            "spock.adb.actions.OpenMcpPanelAction",
            "spock.adb.mcp.ToggleMcpServerAction",
            "spock.adb.mcp.RestartMcpServerAction",
        ).forEach { assertTrue(it in ids, "missing action $it") }
    }

    @Test
    fun `destructive actions are marked with an ellipsis so they read as confirmable`() {
        listOf("ClearAppDataAction", "ClearAppDataAndRestartAction", "UninstallAppAction").forEach { name ->
            val declaration = Regex("""<action id="[^"]*$name"[^>]*text="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .find(pluginXml)
                ?.groupValues
                ?.get(1)
            assertTrue(declaration?.endsWith("...") == true, "$name should read as confirmable, was '$declaration'")
        }
    }

    @Test
    fun `the settings page listing shortcuts only references real action ids`() {
        val listed = Regex("""^\s+"(spock\.adb\.[^"]+)",""", RegexOption.MULTILINE)
            .findAll(File("src/main/kotlin/spock/adb/mcp/SpockAdbConfigurable.kt").readText())
            .map { it.groupValues[1] }
            .toList()

        assertTrue(listed.isNotEmpty(), "the settings overview should list some actions")
        val ids = declaredActions.map { it.first }.toSet()
        listed.forEach { assertTrue(it in ids, "settings lists unknown action id $it") }
    }
}
