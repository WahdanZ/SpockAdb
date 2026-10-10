package spock.adb.flutter

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Where a Flutter app's own `lib/…` sources are when the IDE opened only part of the app. Android
 * Studio is often pointed at `<app>/android`, which leaves `lib/` out of the project and its
 * index, so a widget location such as `lib/fixtures/frames.dart` is found nowhere by name.
 *
 * The Flutter project root is the nearest directory whose `pubspec.yaml` has a `flutter:` section,
 * looked for only where it can be: each of the given directories — the project's own, the module
 * content roots — and the parent of one named `android`. Nothing else is searched, so a `lib/`
 * path is never matched against an unrelated directory. Blocking (file reads): off the EDT.
 */
object FlutterSources {

    private const val ANDROID_DIR = "android"
    private const val LIB = "lib/"

    /** The Flutter project root for [dirs], in their order, each before its parent; null when none has one. */
    fun root(dirs: List<Path>): Path? = dirs.asSequence()
        .flatMap { dir -> listOfNotNull(dir, dir.parent?.takeIf { dir.fileName?.toString() == ANDROID_DIR }) }
        .map { it.toAbsolutePath().normalize() }
        .distinct()
        .firstOrNull(Pubspec::isFlutter)

    /**
     * [relative] — `lib/…`, as Flutter names a widget's file — under the Flutter project root of
     * [dirs], when that file exists; null for any other path, one that leaves `lib/`, or no root.
     */
    fun resolve(relative: String, dirs: List<Path>): Path? {
        if (!relative.startsWith(LIB)) return null
        val root = root(dirs) ?: return null
        val file = try {
            root.resolve(relative).normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        return file.takeIf { it.startsWith(root.resolve(LIB)) && isFile(it) }
    }

    private fun isFile(path: Path): Boolean = try {
        Files.isRegularFile(path)
    } catch (_: SecurityException) {
        false
    }
}
