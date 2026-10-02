package spock.adb.flutter.dtd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceTest {

    @TempDir
    lateinit var temp: Path

    private fun mkdirs(vararg parts: String): Path = Files.createDirectories(temp.resolve(parts.joinToString("/")))

    @Test
    fun `the same directory, with or without a trailing slash or a dot segment`() {
        val app = mkdirs("repo", "app").toString()
        assertEquals(WorkspaceMatch.EXACT, Workspace.match(app, app))
        assertEquals(WorkspaceMatch.EXACT, Workspace.match("$app/", app))
        assertEquals(WorkspaceMatch.EXACT, Workspace.match(app, "$app/./"))
        assertEquals(WorkspaceMatch.EXACT, Workspace.match(app, "$app/../app"))
    }

    @Test
    fun `a Flutter app inside the opened project, and a project inside the daemon's workspace`() {
        val repo = mkdirs("repo").toString()
        val app = mkdirs("repo", "sample", "flutter_app").toString()
        assertEquals(WorkspaceMatch.INSIDE_PROJECT, Workspace.match(repo, app))
        assertEquals(WorkspaceMatch.ENCLOSES_PROJECT, Workspace.match(app, repo))
        assertEquals(2, Workspace.relate(app, repo)!!.distance)
        assertEquals(2, Workspace.relate(repo, app)!!.distance)
        assertEquals(0, Workspace.relate(app, app)!!.distance)
    }

    @Test
    fun `siblings and name prefixes do not match`() {
        val app = mkdirs("repo", "app").toString()
        val app2 = mkdirs("repo", "app2").toString()
        assertNull(Workspace.match(app, app2))
        assertNull(Workspace.match(app, mkdirs("other").toString()))
    }

    @Test
    fun `a symlinked spelling matches the real one, as macOS's tmp and private tmp`() {
        val real = mkdirs("real", "app")
        val link = Files.createSymbolicLink(temp.resolve("link"), temp.resolve("real"))
        assertEquals(WorkspaceMatch.EXACT, Workspace.match(link.resolve("app").toString(), real.toString()))
        assertEquals(WorkspaceMatch.INSIDE_PROJECT, Workspace.match(link.toString(), real.toString()))
    }

    @Test
    fun `macOS tmp, when there is one`() {
        val tmp = Path.of("/tmp")
        if (!Files.isSymbolicLink(tmp)) return
        val dir = Files.createTempDirectory(tmp, "spock-dtd")
        try {
            val viaPrivate = dir.toRealPath().toString()
            assertEquals(WorkspaceMatch.EXACT, Workspace.match(dir.toString(), viaPrivate))
        } finally {
            Files.delete(dir)
        }
    }

    @Test
    fun `a directory deleted since compares by its real parent`() {
        val real = mkdirs("real")
        val link = Files.createSymbolicLink(temp.resolve("link"), real)
        val viaLink = link.resolve("gone").toString()
        assertEquals(WorkspaceMatch.EXACT, Workspace.match(viaLink, real.resolve("gone").toString()))
    }

    @Test
    fun `a file URI is read, a relative path is not`() {
        val app = mkdirs("app")
        assertEquals(WorkspaceMatch.EXACT, Workspace.match(app.toString(), app.toUri().toString()))
        assertNull(Workspace.match(app.toString(), "relative/app"))
        assertNull(Workspace.match(app.toString(), "file:not a uri"))
    }
}
