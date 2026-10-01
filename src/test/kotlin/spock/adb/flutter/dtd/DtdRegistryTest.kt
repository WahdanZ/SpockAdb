package spock.adb.flutter.dtd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DtdRegistryTest {

    @TempDir
    lateinit var temp: Path

    private val recorded = DtdRegistryTest::class.java.getResource("/dtd/registry-entry.json")!!.readText()

    private fun dir(name: String): Path = Files.createDirectories(temp.resolve(name))

    private fun write(dir: Path, name: String, text: String): Path = Files.writeString(dir.resolve(name), text)

    private fun entry(pid: Long, port: Int, root: String, epoch: Long = 1L) =
        """{"wsUri":"ws://127.0.0.1:$port/S$pid=","epoch":$epoch,"pid":$pid,"workspaceRoot":"$root"}"""

    @Test
    fun `the recorded registry file, field by field`() {
        val dir = dir("dtd")
        write(dir, "72822", recorded)
        val entry = DtdRegistry(listOf(dir)) { true }.entries().single()
        assertEquals(72822L, entry.pid)
        assertEquals(1790845327630L, entry.epoch)
        assertEquals(listOf("/abs/path/to/flutter/project"), entry.workspaceRoots)
        assertTrue(entry.dartVersion!!.startsWith("3.13.4"))
        assertEquals("ws://127.0.0.1:53295/DtDsEcReT_xyz=", entry.uri.webSocketUri.toString())
    }

    @Test
    fun `an entry's toString never carries the secret`() {
        val entry = DtdRegistry.parse(recorded, temp.resolve("72822"))!!
        assertFalse(entry.toString().contains("DtDsEcReT_xyz"), entry.toString())
        assertTrue(entry.toString().contains("72822"))
    }

    @Test
    fun `a dead daemon's file is skipped`() {
        val dir = dir("dtd")
        write(dir, "1", entry(1, 1001, "/a"))
        write(dir, "2", entry(2, 1002, "/b"))
        val entries = DtdRegistry(listOf(dir)) { it == 2L }.entries()
        assertEquals(listOf(2L), entries.map { it.pid })
    }

    @Test
    fun `malformed JSON, a JSON array, an empty file and a foreign address are skipped`() {
        val dir = dir("dtd")
        write(dir, "1", "{not json")
        write(dir, "2", "[1, 2]")
        write(dir, "3", "")
        write(dir, "4", """{"wsUri":"ws://10.0.0.2:1/S=","pid":4,"workspaceRoot":"/a"}""")
        write(dir, "5", entry(5, 1005, "/ok"))
        val entries = DtdRegistry(listOf(dir)) { true }.entries()
        assertEquals(listOf(5L), entries.map { it.pid })
    }

    @Test
    fun `missing fields - no address is no entry, the pid falls back to the file name, the rest are optional`() {
        val dir = dir("dtd")
        write(dir, "10", """{"pid":10,"workspaceRoot":"/a"}""")
        write(dir, "11", """{"wsUri":"ws://127.0.0.1:1011/S="}""")
        write(dir, "not-a-pid", """{"wsUri":"ws://127.0.0.1:1012/S="}""")
        val entry = DtdRegistry(listOf(dir)) { true }.entries().single()
        assertEquals(11L, entry.pid)
        assertNull(entry.epoch)
        assertNull(entry.dartVersion)
        assertTrue(entry.workspaceRoots.isEmpty())
    }

    @Test
    fun `lenient types - a pid as a string, workspace roots as a list`() {
        val parsed = DtdRegistry.parse(
            """{"wsUri":"ws://127.0.0.1:1/S=","pid":"42","epoch":"7","workspaceRoot":["/a","/b",3,""]}""",
            temp.resolve("x"),
        )!!
        assertEquals(42L, parsed.pid)
        assertEquals(7L, parsed.epoch)
        assertEquals(listOf("/a", "/b"), parsed.workspaceRoots)
    }

    @Test
    fun `missing and unreadable directories find nothing and do not throw`() {
        val file = Files.writeString(temp.resolve("a-file"), "x")
        val unreadable = dir("locked").also { it.toFile().setReadable(false) }
        try {
            val entries = DtdRegistry(listOf(temp.resolve("missing"), file, unreadable)) { true }.entries()
            assertTrue(entries.isEmpty())
        } finally {
            unreadable.toFile().setReadable(true)
        }
    }

    @Test
    fun `an unreadable file is skipped, its neighbours are not`() {
        val dir = dir("dtd")
        val locked = write(dir, "1", entry(1, 1001, "/a")).also { it.toFile().setReadable(false) }
        write(dir, "2", entry(2, 1002, "/b"))
        try {
            val pids = DtdRegistry(listOf(dir)) { true }.entries().map { it.pid }
            // Root can read anything; elsewhere the locked file is skipped.
            assertTrue(2L in pids)
            if (!Files.isReadable(locked)) assertEquals(listOf(2L), pids)
        } finally {
            locked.toFile().setReadable(true)
        }
    }

    @Test
    fun `several directories are merged, newest daemon first, a directory listed twice read once`() {
        val first = dir("one")
        val second = dir("two")
        write(first, "1", entry(1, 1001, "/a", epoch = 100))
        write(second, "2", entry(2, 1002, "/b", epoch = 300))
        write(second, "3", entry(3, 1003, "/c", epoch = 200))
        val entries = DtdRegistry(listOf(first, second, first)) { true }.entries()
        assertEquals(listOf(2L, 3L, 1L), entries.map { it.pid })
    }

    @Test
    fun `macOS's directory is the one S12 saw`() {
        val dirs = DtdRegistry.defaultDirs("Mac OS X", "/Users/me") { null }
        assertEquals(listOf(Path.of("/Users/me/Library/Application Support/Dart/dtd")), dirs)
    }

    @Test
    fun `Linux guesses XDG first, then the home config and dart-tool directories`() {
        val dirs = DtdRegistry.defaultDirs("Linux", "/home/me") { if (it == "XDG_CONFIG_HOME") "/xdg" else null }
        val expected = listOf(
            "/xdg/Dart/dtd",
            "/xdg/dart/dtd",
            "/home/me/.config/Dart/dtd",
            "/home/me/.config/dart/dtd",
            "/home/me/.dart-tool/dtd",
        )
        assertEquals(expected.map(Path::of), dirs)
        assertEquals(3, DtdRegistry.defaultDirs("Linux", "/home/me") { null }.size)
    }

    @Test
    fun `Windows uses APPDATA, and nothing when it is unset`() {
        val appData = "C:\\Users\\me\\AppData\\Roaming"
        assertEquals(1, DtdRegistry.defaultDirs("Windows 11", "C:\\Users\\me") { appData }.size)
        assertTrue(DtdRegistry.defaultDirs("Windows 11", "C:\\Users\\me") { null }.isEmpty())
    }

    @Test
    fun `the default liveness check keeps this process and drops a pid nothing runs as`() {
        val dir = dir("dtd")
        val self = ProcessHandle.current().pid()
        val none = Int.MAX_VALUE.toLong()
        write(dir, "$self", entry(self, 1001, "/a"))
        write(dir, "$none", entry(none, 1002, "/b"))
        assertEquals(listOf(self), DtdRegistry(listOf(dir)).entries().map { it.pid })
    }
}
