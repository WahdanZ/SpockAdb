package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A widget's file, as Flutter reports it, is opened from the app's pubspec root, also when the IDE
 * opened only the app's `android/` folder — and never from a root that is not the file's.
 */
class FlutterSourcesTest {

    @TempDir
    lateinit var temp: Path

    private val app: Path get() = temp.resolve("flutter_app")

    /** A Flutter app at [dir]: its pubspec, an `android/app` Gradle project, and [files] under it. */
    private fun flutterApp(dir: Path = app, pubspec: String = FLUTTER_PUBSPEC, vararg files: String = arrayOf(FRAMES)) {
        Files.createDirectories(dir.resolve("android/app"))
        Files.writeString(dir.resolve(Pubspec.FILE_NAME), pubspec)
        files.forEach { file ->
            Files.createDirectories(dir.resolve(file).parent)
            Files.writeString(dir.resolve(file), "// $file\n")
        }
    }

    /** As Flutter reports it: a `file:` URI of the absolute path. */
    private fun reported(file: Path): String = file.toUri().toString()

    @Test
    fun `with the Flutter project open, the reported file resolves under it`() {
        flutterApp()

        assertEquals(app.resolve(FRAMES), FlutterSources.resolve(reported(app.resolve(FRAMES)), listOf(app)))
    }

    @Test
    fun `with only android open, the reported file resolves under its parent's pubspec`() {
        flutterApp()
        val android = app.resolve("android")
        val frames = reported(app.resolve(FRAMES))

        assertEquals(app.resolve(FRAMES), FlutterSources.resolve(frames, listOf(android)))
        // The Gradle modules' content roots: android/ itself leads to the app, android/app does not.
        assertEquals(app.resolve(FRAMES), FlutterSources.resolve(frames, listOf(android.resolve("app"), android)))
    }

    @Test
    fun `an app built in another checkout resolves by the root's folder name before its lib`() {
        flutterApp()

        val elsewhere = "file:///home/ci/work/flutter_app/lib/fixtures/frames.dart"
        assertEquals(app.resolve(FRAMES), FlutterSources.resolve(elsewhere, listOf(app.resolve("android"))))
        assertNull(FlutterSources.resolve("file:///home/ci/work/other_app/lib/fixtures/frames.dart", listOf(app)))
    }

    @Test
    fun `with no pubspec next to android, nothing resolves and the panel keeps its message`() {
        flutterApp()
        Files.delete(app.resolve(Pubspec.FILE_NAME))

        assertNull(FlutterSources.resolve(reported(app.resolve(FRAMES)), listOf(app.resolve("android"), app)))
    }

    @Test
    fun `a pubspec without a flutter section is a Dart package, not the app`() {
        flutterApp(pubspec = "name: tooling\nenvironment:\n  sdk: ^3.4.0\n")

        assertNull(FlutterSources.resolve(reported(app.resolve(FRAMES)), listOf(app)))
    }

    @Test
    fun `only a folder named android looks at its parent`() {
        flutterApp()
        val other = Files.createDirectories(app.resolve("tool"))

        assertNull(FlutterSources.resolve(reported(app.resolve(FRAMES)), listOf(other)))
        assertNull(FlutterSources.resolve(reported(app.resolve(FRAMES)), listOf(app.resolve("android/app"))))
    }

    @Test
    fun `of two Flutter apps open, the file opens in the one it was reported in`() {
        val one = temp.resolve("one")
        val two = temp.resolve("two")
        flutterApp(one, files = arrayOf(MAIN))
        flutterApp(two, files = arrayOf(MAIN))

        assertEquals(two.resolve(MAIN), FlutterSources.resolve(reported(two.resolve(MAIN)), listOf(one, two)))
    }

    @Test
    fun `a path dependency's file never opens as the app's file of the same name`() {
        val mono = temp.resolve("mono")
        val appDir = mono.resolve("apps/shop")
        flutterApp(appDir, files = arrayOf(BUTTON))
        val dependency = mono.resolve("packages/ui")
        Files.createDirectories(dependency.resolve("lib/widgets"))
        Files.writeString(dependency.resolve(BUTTON), "// the package's\n")

        assertNull(FlutterSources.resolve(reported(dependency.resolve(BUTTON)), listOf(appDir.resolve("android"))))
    }

    @Test
    fun `a lib inside lib keeps its whole path`() {
        flutterApp(files = arrayOf("lib/src/lib/foo.dart", "lib/foo.dart"))

        val inner = app.resolve("lib/src/lib/foo.dart")
        assertEquals(inner, FlutterSources.resolve(reported(inner), listOf(app)))
        val elsewhere = "file:///home/ci/flutter_app/lib/src/lib/foo.dart"
        assertEquals(inner, FlutterSources.resolve(elsewhere, listOf(app)))
    }

    @Test
    fun `only files that exist, in lib, from a file path`() {
        flutterApp()
        Files.writeString(app.resolve("secret.txt"), "x")

        assertNull(FlutterSources.resolve(reported(app.resolve("lib/fixtures/missing.dart")), listOf(app)))
        assertNull(FlutterSources.resolve(reported(app.resolve("lib/../secret.txt")), listOf(app)))
        assertNull(FlutterSources.resolve("package:spock_flutter_sample/main.dart", listOf(app)))
        assertNull(FlutterSources.resolve(FRAMES, listOf(app)))
    }

    private companion object {
        const val FRAMES = "lib/fixtures/frames.dart"
        const val MAIN = "lib/main.dart"
        const val BUTTON = "lib/widgets/button.dart"
        const val FLUTTER_PUBSPEC =
            "name: spock_flutter_sample\nenvironment:\n  sdk: ^3.4.0\n\nflutter:\n  uses-material-design: true\n"
    }
}
