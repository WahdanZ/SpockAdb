package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PubspecTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the top-level name, plain, quoted or followed by a comment`() {
        assertEquals("dtd_spike", Pubspec.parseName("name: dtd_spike\ndescription: x\n"))
        assertEquals("dtd_spike", Pubspec.parseName("# app\nname: 'dtd_spike'\n"))
        assertEquals("dtd_spike", Pubspec.parseName("name: \"dtd_spike\"  # the package\n"))
        assertEquals("dtd_spike", Pubspec.parseName("name: dtd_spike\r\nversion: 1.0.0\r\n"))
    }

    @Test
    fun `only a top-level flutter section makes a Flutter pubspec`() {
        assertTrue(Pubspec.declaresFlutter("name: app\n\nflutter:\n  uses-material-design: true\n"))
        assertFalse(Pubspec.declaresFlutter("name: app\ndependencies:\n  flutter:\n    sdk: flutter\n"))
        assertFalse(Pubspec.declaresFlutter("name: tooling\n"))
        assertFalse(Pubspec.isFlutter(temp.resolve("missing")))
    }

    @Test
    fun `a nested name is not the package's`() {
        assertNull(Pubspec.parseName("dependencies:\n  name: other\n"))
        assertNull(Pubspec.parseName("description: no name here\n"))
    }

    @Test
    fun `read from a directory, null when it has no pubspec`() {
        Files.writeString(temp.resolve(Pubspec.FILE_NAME), "name: spock_flutter_sample\nversion: 1.0.0\n")
        assertEquals("spock_flutter_sample", Pubspec.name(temp.toString()))
        assertNull(Pubspec.name(temp.resolve("missing").toString()))
    }

    @Test
    fun `a pub workspace root names no app, a member does`() {
        val root = "name: _\nenvironment:\n  sdk: ^3.6.0\nworkspace:\n  - packages/app\n"
        assertTrue(Pubspec.declaresWorkspace(root))
        assertFalse(Pubspec.declaresWorkspace("name: app\nresolution: workspace\n"))
        assertFalse(Pubspec.declaresWorkspace("name: app\nflutter:\n  workspace: nested\n"))
        Files.writeString(temp.resolve(Pubspec.FILE_NAME), root)
        assertEquals("_", Pubspec.name(temp.toString()))
        assertNull(Pubspec.appName(temp.toString()))
        assertTrue(Pubspec.exists(temp))
        assertFalse(Pubspec.exists(temp.resolve("missing")))
    }
}
