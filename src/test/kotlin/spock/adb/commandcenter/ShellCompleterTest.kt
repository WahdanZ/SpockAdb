package spock.adb.commandcenter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellCompleterTest {

    private val completer = ShellCompleter()
    private val packages = listOf("com.example.app", "com.example.other", "org.sample.debug")

    private fun texts(line: String, caret: Int = line.length) =
        completer.complete(line, caret, packages).suggestions.map { it.text }

    @Test
    fun `the bundled catalog parses and documents every entry`() {
        val roots = ShellCommandCatalog.roots
        assertTrue(roots.size > 20, "catalog has ${roots.size} commands")
        fun check(entries: List<ShellCommandDoc>) {
            entries.forEach {
                assertTrue(it.usage.isNotBlank() && it.summary.isNotBlank(), it.name)
                check(it.children)
            }
        }
        check(roots)
        assertEquals(roots.size, roots.map { it.name }.distinct().size, "duplicate top-level command")
    }

    @Test
    fun `a malformed catalog line fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) { ShellCommandCatalog.parse("pm | pm <command>") }
        val tooDeep = "pm | u | s\n      list | u | s"
        assertThrows(IllegalArgumentException::class.java) { ShellCommandCatalog.parse(tooDeep) }
    }

    @Test
    fun `top-level commands complete from their first letters`() {
        assertEquals(listOf("pm", "pidof", "ps"), texts("p").filter { it in setOf("pm", "ps", "pidof") })
        assertTrue("dumpsys" in texts("dum"))
    }

    @Test
    fun `subcommands follow their command, with docs`() {
        val result = completer.complete("pm cl")
        val clear = result.suggestions.single()
        assertEquals("clear", clear.text)
        assertEquals("pm clear <package>", clear.usage)
        assertEquals(3, result.replaceFrom)
        assertEquals("pm", result.context?.name)
    }

    @Test
    fun `installed packages are offered where a package goes, matching anywhere in the name`() {
        assertEquals(listOf("com.example.app", "com.example.other"), texts("pm clear com.ex"))
        assertEquals(listOf("org.sample.debug"), texts("am force-stop sample"))
        assertEquals(listOf("com.example.app"), texts("monkey -p com.example.a"))
    }

    @Test
    fun `after the package, the command's own subcommands are still offered`() {
        assertEquals(listOf("reset", "framestats"), texts("dumpsys gfxinfo com.example.app "))
    }

    @Test
    fun `a dash offers only the flags not yet used`() {
        val flags = texts("pm list packages -3 -")
        assertTrue("-f" in flags)
        assertFalse("-3" in flags)
        assertTrue(flags.all { it.startsWith("-") })
    }

    @Test
    fun `a free argument ends subcommand completion`() {
        val offered = texts("pm list packages google ").filterNot { it.startsWith("-") }
        assertEquals(emptyList<String>(), offered)
    }

    @Test
    fun `completion restarts after a pipe or separator`() {
        assertTrue("grep" !in texts("ps -A | gr"))
        assertEquals(listOf("getprop", "getenforce"), texts("ps -A; get"))
        assertTrue("battery" in texts("logcat -c && dumpsys bat"))
    }

    @Test
    fun `a word typed in full offers nothing, so Enter runs the command`() {
        assertEquals(emptyList<String>(), texts("dumpsys cpuinfo"))
    }

    @Test
    fun `accepting replaces the word at the caret and adds a space`() {
        val result = completer.complete("pm cl")
        val (text, caret) = completer.accept("pm cl", result, result.suggestions.single())
        assertEquals("pm clear ", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun `accepting in the middle of a line keeps what follows`() {
        val line = "dumpsys bat --reset"
        val result = completer.complete(line, caret = 11)
        val battery = result.suggestions.first { it.text == "battery" }
        val (text, caret) = completer.accept(line, result, battery, caret = 11)
        assertEquals("dumpsys battery --reset", text)
        assertEquals("dumpsys battery".length, caret)
    }

    @Test
    fun `keycodes complete case-insensitively`() {
        assertEquals(listOf("KEYCODE_HOME"), texts("input keyevent keycode_ho"))
    }
}
