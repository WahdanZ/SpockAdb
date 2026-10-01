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

    /**
     * The package name in [dir]'s pubspec — what `flutter run` reports as `Package:` to the Dart
     * Tooling Daemon, not the Android applicationId. Null when there is no readable pubspec or it
     * names nothing. Blocking (file read); never throws.
     */
    fun name(dir: String): String? {
        val file = try {
            Path.of(dir, FILE_NAME)
        } catch (_: InvalidPathException) {
            return null
        }
        return try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) null else parseName(Files.readString(file))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** The top-level `name:` in pubspec text; null when there is none. */
    fun parseName(text: String): String? = NAME.find(text)?.groupValues?.get(2)
}
