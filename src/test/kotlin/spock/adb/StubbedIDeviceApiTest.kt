package spock.adb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against calling `IDevice` methods that Android Studio does not implement.
 *
 * Android Studio does not supply ddmlib's own `DeviceImpl`. It supplies
 * `com.android.adblib.ddmlibcompatibility.debugging.AdblibIDeviceWrapper`, which leaves
 * nineteen `IDevice` methods unimplemented: each one throws
 * `"This method is not used in Android Studio"`.
 *
 * Nothing catches this at compile time — the methods are on the interface and are perfectly
 * real in stock ddmlib — and no unit test catches it either, because a test that builds its
 * own `AndroidDebugBridge` gets ddmlib's real implementation, where they all work. The
 * failure appears only in the IDE, which is the one place the plugin ever runs.
 *
 * `android_take_screenshot` shipped broken for exactly this reason: it called
 * `IDevice.getScreenshot()` and returned that sentence to every caller instead of an image.
 *
 * So this reads the compiled bytecode rather than the source. Bytecode is exact: it sees
 * through Kotlin's property syntax, where `device.screenshot` is a call to `getScreenshot()`
 * that no text search for `getScreenshot(` would ever find.
 *
 * The fix, in every case, is to go through `executeShellCommand` — which Android Studio does
 * implement — the way [spock.adb.mcp.tools.McpShell] does.
 */
class StubbedIDeviceApiTest {

    @Test
    fun `no code calls an IDevice method Android Studio leaves unimplemented`() {
        val classes = compiledPluginClasses()
        assertTrue(classes.isNotEmpty(), "found no compiled classes to scan in $CLASS_DIR")

        val offenders = mutableMapOf<String, MutableSet<String>>()
        classes.chunked(BATCH_SIZE).forEach { batch ->
            disassemble(batch).lineSequence().forEach { line ->
                val method = IDEVICE_CALL.find(line)?.groupValues?.get(1) ?: return@forEach
                if (method in UNIMPLEMENTED_IN_ANDROID_STUDIO) {
                    offenders.getOrPut(method) { mutableSetOf() } += line.substringAfter("//").trim()
                }
            }
        }

        assertTrue(offenders.isEmpty()) {
            buildString {
                appendLine("Called IDevice methods that Android Studio does not implement.")
                appendLine("Each one throws \"This method is not used in Android Studio\" at runtime,")
                appendLine("so the feature is broken in the IDE even though it compiles and unit-tests fine.")
                appendLine("Use executeShellCommand instead — see McpShell.run and McpShell.runBinary.")
                appendLine()
                offenders.toSortedMap().forEach { (method, sites) ->
                    appendLine("  IDevice.$method")
                    sites.sorted().forEach { appendLine("      $it") }
                }
            }
        }
    }

    @Test
    fun `the scan actually sees IDevice calls`() {
        // Without this, a broken class path or a changed javap format would turn the guard
        // above into a test that passes because it inspected nothing.
        val disassembled = disassemble(compiledPluginClasses().take(BATCH_SIZE))
        assertTrue(
            disassembled.contains("com/android/ddmlib/IDevice.executeShellCommand"),
            "the scan found no IDevice.executeShellCommand call, so it is not reading the plugin's bytecode",
        )
    }

    private fun compiledPluginClasses(): List<File> =
        projectRoot().resolve(CLASS_DIR).walkTopDown().filter { it.extension == "class" }.toList()

    private fun disassemble(classes: List<File>): String {
        if (classes.isEmpty()) return ""
        val javap = File(System.getProperty("java.home"), "bin/javap")
        val process = ProcessBuilder(listOf(javap.absolutePath, "-p", "-c") + classes.map { it.absolutePath })
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun projectRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (candidate.resolve("settings.gradle.kts").exists()) return candidate
            candidate = candidate.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val CLASS_DIR = "build/classes/kotlin/main"

        /** javap renders these as `invokeinterface ... // InterfaceMethod com/android/ddmlib/IDevice.name:(...)`. */
        val IDEVICE_CALL = Regex("""(?:InterfaceMethod|Method) com/android/ddmlib/IDevice\.([a-zA-Z]+)""")

        /** Long command lines fail; javap is happy to be called repeatedly. */
        const val BATCH_SIZE = 150

        /**
         * Every method AdblibIDeviceWrapper routes to its `unsupportedMethod()` helper, read
         * out of `plugins/android/lib/sdk-tools.jar` in Android Studio 2025.1.
         *
         * Overloads collapse to one name here: if one overload is unimplemented the other is
         * too, and none of them is safe to reach for.
         */
        val UNIMPLEMENTED_IN_ANDROID_STUDIO = setOf(
            "getBatteryLevel",
            "getFileListingService",
            "getLanguage",
            "getMountPoint",
            "getPropertyCacheOrSync",
            "getPropertySync",
            "getRegion",
            "getScreenshot",
            "getSyncService",
            "hasClients",
            "installRemotePackage",
            "rawExec",
            "reboot",
            "runEventLogService",
            "runLogService",
            "startScreenRecorder",
        )
    }
}
