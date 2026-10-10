package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A widget's `lib/…` file is opened from the app's pubspec root, also when the IDE opened only
 * the app's `android/` folder — and never from a directory that is not the app's.
 */
class FlutterSourcesTest {

    @TempDir
    lateinit var temp: Path

    private val app: Path get() = temp.resolve("flutter_app")

    /** The sample's layout: a Flutter pubspec, `lib/fixtures/frames.dart`, and the Gradle project in `android/`. */
    private fun flutterApp(pubspec: String = FLUTTER_PUBSPEC): Path {
        Files.createDirectories(app.resolve("lib/fixtures"))
        Files.createDirectories(app.resolve("android/app"))
        Files.writeString(app.resolve(Pubspec.FILE_NAME), pubspec)
        Files.writeString(app.resolve(FRAMES), "class StormTile {}\n")
        return app.resolve(FRAMES)
    }

    @Test
    fun `with the Flutter project open, lib paths resolve under it`() {
        val frames = flutterApp()

        assertEquals(frames, FlutterSources.resolve(FRAMES, listOf(app)))
    }

    @Test
    fun `with only android open, lib paths resolve under its parent's pubspec`() {
        val frames = flutterApp()
        val android = app.resolve("android")

        assertEquals(frames, FlutterSources.resolve(FRAMES, listOf(android)))
        // The Gradle modules' content roots: android/ itself leads to the app, android/app does not.
        assertEquals(frames, FlutterSources.resolve(FRAMES, listOf(android.resolve("app"), android)))
        assertEquals(app.toAbsolutePath().normalize(), FlutterSources.root(listOf(android)))
    }

    @Test
    fun `with no pubspec next to android, nothing resolves and the panel keeps its message`() {
        flutterApp()
        Files.delete(app.resolve(Pubspec.FILE_NAME))

        assertNull(FlutterSources.resolve(FRAMES, listOf(app.resolve("android"), app)))
    }

    @Test
    fun `a pubspec without a flutter section is a Dart package, not the app`() {
        flutterApp(pubspec = "name: tooling\nenvironment:\n  sdk: ^3.4.0\n")

        assertNull(FlutterSources.resolve(FRAMES, listOf(app)))
    }

    @Test
    fun `only a folder named android looks at its parent`() {
        flutterApp()
        val other = Files.createDirectories(app.resolve("tool"))

        assertNull(FlutterSources.resolve(FRAMES, listOf(other)))
        assertNull(FlutterSources.resolve(FRAMES, listOf(app.resolve("android/app"))))
    }

    @Test
    fun `only lib files that exist, and never outside lib`() {
        flutterApp()
        Files.writeString(app.resolve("secret.txt"), "x")

        assertNull(FlutterSources.resolve("lib/fixtures/missing.dart", listOf(app)))
        assertNull(FlutterSources.resolve("lib/../secret.txt", listOf(app)))
        assertNull(FlutterSources.resolve("package:spock_flutter_sample/main.dart", listOf(app)))
        assertNull(FlutterSources.resolve("frames.dart", listOf(app)))
    }

    private companion object {
        const val FRAMES = "lib/fixtures/frames.dart"
        const val FLUTTER_PUBSPEC =
            "name: spock_flutter_sample\nenvironment:\n  sdk: ^3.4.0\n\nflutter:\n  uses-material-design: true\n"
    }
}
