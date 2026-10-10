package spock.adb.flutter

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a Flutter app's own `lib/…` sources are when the IDE opened only part of the app. Android
 * Studio is often pointed at `<app>/android`, which leaves `lib/` out of the project and its
 * index, so a widget location such as `lib/fixtures/frames.dart` is found nowhere by name.
 *
 * Flutter reports the file as built, `file:///…/my_app/lib/fixtures/frames.dart`. A Flutter
 * project root is a directory whose `pubspec.yaml` has a `flutter:` section, looked for only where
 * it can be: each of the given directories — the project's own, the module content roots — and
 * the parent of one named `android`. The reported file is taken under the root that holds it; else
 * under the root whose folder name the reported path has right before its `lib/` (the app built
 * from another checkout). Anything else — a path dependency's `packages/ui/lib/…`, a second app's
 * file — matches no root, and the caller falls back to the index rather than open the wrong file.
 * Blocking (file reads): off the EDT.
 */
object FlutterSources {

    private const val ANDROID_DIR = "android"
    private const val LIB = "lib"

    /** The Flutter project roots among [dirs] and the parents of those named `android`, in their order. */
    fun roots(dirs: List<Path>): List<Path> = dirs
        .flatMap { dir -> listOfNotNull(dir, dir.parent?.takeIf { dir.fileName?.toString() == ANDROID_DIR }) }
        .map { it.toAbsolutePath().normalize() }
        .distinct()
        .filter(Pubspec::isFlutter)

    /**
     * The file Flutter [reported] (a `file:` URI or an absolute path) under one of the Flutter
     * project roots of [dirs], when it is in that root's `lib/` and exists; null otherwise.
     */
    fun resolve(reported: String, dirs: List<Path>): Path? {
        val path = pathOf(reported) ?: return null
        val roots = roots(dirs)
        return roots.firstNotNullOfOrNull { under(it, path) } ?: roots.firstNotNullOfOrNull { byName(it, path) }
    }

    /** [path] itself, when it lies in [root]'s `lib/`. */
    private fun under(root: Path, path: Path): Path? {
        if (!path.startsWith(root) || path.nameCount <= root.nameCount) return null
        return path.takeIf { it.getName(root.nameCount).toString() == LIB && isFile(it) }
    }

    /** `<root>/lib/…` for a [path] built elsewhere: the first `<root's name>/lib/` in it, and what follows. */
    private fun byName(root: Path, path: Path): Path? {
        val name = root.fileName?.toString() ?: return null
        val at = (0 until path.nameCount - 2).firstOrNull { i ->
            path.getName(i).toString() == name && path.getName(i + 1).toString() == LIB
        } ?: return null
        val file = root.resolve(path.subpath(at + 1, path.nameCount).toString()).normalize()
        return file.takeIf { it.startsWith(root.resolve(LIB)) && isFile(it) }
    }

    /** A `file:` URI or an absolute path, normalised; null for `package:` URIs and anything else. */
    private fun pathOf(reported: String): Path? = try {
        val path = if (reported.startsWith("file:")) Path.of(URI(reported)) else Path.of(reported)
        path.takeIf { it.isAbsolute }?.normalize()
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        // Not a path on this machine: InvalidPathException is one.
        null
    } catch (_: FileSystemNotFoundException) {
        null
    }

    private fun isFile(path: Path): Boolean = try {
        Files.isRegularFile(path)
    } catch (_: SecurityException) {
        false
    }
}
