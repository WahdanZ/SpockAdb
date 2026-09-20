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

    @Test
    fun `ends the file with a newline`() {
        val outcome = McpConfigInstaller.install(projectDir, entry)

        assertTrue(Files.readString(outcome.file).endsWith("\n"))
    }
}
