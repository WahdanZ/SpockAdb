package spock.adb.flutter

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Reads the little Spock needs from a Dart package's `pubspec.yaml`, without a YAML parser. */
object Pubspec {

    const val FILE_NAME = "pubspec.yaml"

    /** A pubspec is a few KB; anything far bigger is not worth reading for its name. */
    private const val MAX_BYTES = 1024 * 1024L

    /** The top-level `name:`, unquoted, with any trailing comment dropped. */
    private val NAME = Regex("""(?m)^name:\s*(['"]?)([A-Za-z0-9_]+)\1\s*(?:#.*)?$""")

    /** A top-level `workspace:` key: the root of a pub workspace (melos 7 builds on it). */
    private val WORKSPACE = Regex("""(?m)^workspace\s*:""")

    /** A top-level `flutter:` section: a Flutter app or package, not a plain Dart one. */
    private val FLUTTER = Regex("""(?m)^flutter\s*:""")

    /**
     * The package name in [dir]'s pubspec — what `flutter run` reports as `Package:` to the Dart
     * Tooling Daemon, not the Android applicationId. Null when there is no readable pubspec or it
     * names nothing. Blocking (file read); never throws.
     */
    fun name(dir: String): String? = read(dir)?.let(::parseName)

    /**
     * [name], unless [dir]'s pubspec is a pub workspace root: that one names the workspace, never
     * an app `flutter run` started, so it says nothing about which app is which.
     */
    fun appName(dir: String): String? = read(dir)?.takeUnless(::declaresWorkspace)?.let(::parseName)

    /** Whether [dir] holds a `pubspec.yaml` at all. Blocking; never throws. */
    fun exists(dir: Path): Boolean = try {
        Files.isRegularFile(dir.resolve(FILE_NAME))
    } catch (_: SecurityException) {
        false
    }

    /** The top-level `name:` in pubspec text; null when there is none. */
    fun parseName(text: String): String? = NAME.find(text)?.groupValues?.get(2)

    /** Whether pubspec text has a top-level `workspace:` key. */
    fun declaresWorkspace(text: String): Boolean = WORKSPACE.containsMatchIn(text)

    /** Whether [dir]'s pubspec has a top-level `flutter:` section. Blocking (file read); never throws. */
    fun isFlutter(dir: Path): Boolean = read(dir.toString())?.let(::declaresFlutter) == true

    /** Whether pubspec text has a top-level `flutter:` section. */
    fun declaresFlutter(text: String): Boolean = FLUTTER.containsMatchIn(text)

    private fun read(dir: String): String? {
        val file = try {
            Path.of(dir, FILE_NAME)
        } catch (_: InvalidPathException) {
            return null
        }
        return try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) null else Files.readString(file)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
