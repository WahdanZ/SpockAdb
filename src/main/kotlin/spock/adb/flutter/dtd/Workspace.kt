package spock.adb.flutter.dtd

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** How a daemon's workspace root relates to the open project, best first. */
enum class WorkspaceMatch {
    /** The same directory: `flutter run` in the project itself. */
    EXACT,

    /** The root is inside the project: a Flutter app in a subdirectory of the opened repository. */
    INSIDE_PROJECT,

    /** The project is inside the root: a daemon for the whole repository, the project one module of it. */
    ENCLOSES_PROJECT,
}

/** Path comparison that survives what differs between how the IDE and `dart` spell one directory. */
object Workspace {

    /**
     * How [workspaceRoot] relates to [projectPath], or null when neither holds the other. Both
     * are compared absolute, normalised, with symlinks resolved — macOS's `/tmp` is
     * `/private/tmp` — and a trailing slash ignored. Comparison is by path component, so
     * `/a/app` does not hold `/a/app2`. Blocking (file system); never throws.
     */
    fun match(projectPath: String, workspaceRoot: String): WorkspaceMatch? {
        val project = canonical(projectPath) ?: return null
        val root = canonical(workspaceRoot) ?: return null
        return when {
            project == root -> WorkspaceMatch.EXACT
            root.startsWith(project) -> WorkspaceMatch.INSIDE_PROJECT
            project.startsWith(root) -> WorkspaceMatch.ENCLOSES_PROJECT
            else -> null
        }
    }

    /** An absolute path or a `file:` URI as a real path; null for anything else. */
    fun canonical(path: String): Path? {
        val parsed = try {
            if (path.startsWith("file:")) Path.of(URI(path)) else Path.of(path)
        } catch (_: InvalidPathException) {
            null
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: FileSystemNotFoundException) {
            null
        } ?: return null
        if (!parsed.isAbsolute) return null
        return real(parsed.normalize())
    }

    /**
     * The longest part of [path] that exists, with its symlinks resolved, and the rest as
     * written: a project directory deleted since its daemon started still compares by its
     * real parent.
     */
    private fun real(path: Path): Path {
        val missing = ArrayDeque<Path>()
        var current: Path? = path
        while (current != null) {
            val resolved = resolveOrNull(current)
            if (resolved != null) return missing.fold(resolved) { acc, part -> acc.resolve(part) }
            current.fileName?.let(missing::addFirst)
            current = current.parent
        }
        return path
    }

    private fun resolveOrNull(path: Path): Path? = try {
        if (Files.exists(path)) path.toRealPath() else null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
