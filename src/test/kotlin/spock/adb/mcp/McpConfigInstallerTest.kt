package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class McpConfigInstallerTest {

    @TempDir
    lateinit var projectDir: Path

    private val entry get() = McpClientConfig.httpServer(port = 4321)

    @Test
    fun `creates the file when the project has none`() {
        val outcome = McpConfigInstaller.install(projectDir, entry)

        assertTrue(outcome.created)
        assertFalse(outcome.replaced)
        assertTrue(Files.exists(outcome.file))
        assertTrue(McpClientConfig.contains(Files.readString(outcome.file)))
    }

    @Test
    fun `adds to an existing file without claiming to have created it`() {
        val file = projectDir.resolve(McpConfigInstaller.FILE_NAME)
        Files.writeString(file, """{ "mcpServers": { "other": { "command": "node" } } }""")

        val outcome = McpConfigInstaller.install(projectDir, entry)

        assertFalse(outcome.created)
        assertFalse(outcome.replaced)
        assertTrue(Files.readString(file).contains("other"))
    }

    @Test
    fun `reports replacing a previous entry`() {
        McpConfigInstaller.install(projectDir, entry)

        val outcome = McpConfigInstaller.install(projectDir, entry)

        assertFalse(outcome.created)
        assertTrue(outcome.replaced)
    }

    /** Overwriting a hand-written file would take the developer's other servers with it. */
    @Test
    fun `leaves a malformed file untouched`() {
        val file = projectDir.resolve(McpConfigInstaller.FILE_NAME)
        val broken = "{ oops"
        Files.writeString(file, broken)

        assertThrows<Exception> { McpConfigInstaller.install(projectDir, entry) }
        assertEquals(broken, Files.readString(file))
    }

    // ---------------------------------------------------------------- sharing

    /**
     * `.mcp.json` is a file teams commit, and the stdio entry in it names this machine's JDK,
     * plugin jar and IDE config by absolute path — useless to a teammate who checks it out.
     */
    @Test
    fun `recognises an entry already in gitignore`() {
        assertTrue(McpConfigInstaller.mentionsConfig(".mcp.json"))
        assertTrue(McpConfigInstaller.mentionsConfig("build/\n.mcp.json\n.idea/"))
        assertTrue(McpConfigInstaller.mentionsConfig("/.mcp.json"))
        assertTrue(McpConfigInstaller.mentionsConfig("  .mcp.json  "))
    }

    @Test
    fun `does not mistake a near miss for an entry`() {
        assertFalse(McpConfigInstaller.mentionsConfig(""))
        assertFalse(McpConfigInstaller.mentionsConfig("# .mcp.json"))
        assertFalse(McpConfigInstaller.mentionsConfig("!.mcp.json"))
        assertFalse(McpConfigInstaller.mentionsConfig("some.mcp.json"))
    }

    @Test
    fun `reports whether the project already ignores the file`() {
        assertFalse(McpConfigInstaller.isIgnored(projectDir))

        Files.writeString(projectDir.resolve(".gitignore"), "build/\n.mcp.json\n")

        assertTrue(McpConfigInstaller.isIgnored(projectDir))
    }

    @Test
    fun `appends to an existing gitignore without disturbing it`() {
        val gitignore = projectDir.resolve(".gitignore")
        Files.writeString(gitignore, "build/\n.idea/\n")

        McpConfigInstaller.ignoreConfig(projectDir)

        val text = Files.readString(gitignore)
        assertTrue(text.startsWith("build/\n.idea/\n"), "existing entries must survive: $text")
        assertTrue(McpConfigInstaller.mentionsConfig(text))
    }

    /** A .gitignore with no trailing newline must not end up with two entries on one line. */
    @Test
    fun `separates the entry from a file that does not end in a newline`() {
        val gitignore = projectDir.resolve(".gitignore")
        Files.writeString(gitignore, "build/")

        McpConfigInstaller.ignoreConfig(projectDir)

        assertTrue(Files.readString(gitignore).lineSequence().any { it == "build/" })
        assertTrue(McpConfigInstaller.mentionsConfig(Files.readString(gitignore)))
    }

    @Test
    fun `creates a gitignore when the project has none`() {
        val gitignore = McpConfigInstaller.ignoreConfig(projectDir)

        assertTrue(Files.exists(gitignore))
        assertTrue(McpConfigInstaller.mentionsConfig(Files.readString(gitignore)))
    }

    @Test
    fun `does not append the ignore block when the file already mentions mcp config`() {
        val gitignore = projectDir.resolve(".gitignore")
        val existing = "build/\n.mcp.json\n"
        Files.writeString(gitignore, existing)

        McpConfigInstaller.ignoreConfig(projectDir)

        assertEquals(existing, Files.readString(gitignore))
    }

    @Test
    fun `ends the file with a newline`() {
        val outcome = McpConfigInstaller.install(projectDir, entry)

        assertTrue(Files.readString(outcome.file).endsWith("\n"))
    }

    @Test
    fun `ignore config ends the file with a newline`() {
        val gitignore = McpConfigInstaller.ignoreConfig(projectDir)

        assertTrue(Files.readString(gitignore).endsWith("\n"))
    }
}
