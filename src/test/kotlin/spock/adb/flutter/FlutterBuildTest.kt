package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.command.AppInfo

class FlutterBuildTest {

    /** `unzip -l` of sample/flutter_app's debug APK, through the listing command's grep. */
    private val debugListing = """
         10496162  1981-01-01 01:01   assets/flutter_assets/isolate_snapshot_data
         40635872  1981-01-01 01:01   lib/x86_64/libflutter.so
         39525904  1981-01-01 01:01   assets/flutter_assets/kernel_blob.bin
         39599552  1981-01-01 01:01   lib/arm64-v8a/libflutter.so
    """.trimIndent()

    private val aotListing = """
          6619200  1981-01-01 01:01   lib/arm64-v8a/libapp.so
         11206032  1981-01-01 01:01   lib/arm64-v8a/libflutter.so
    """.trimIndent()

    /** As Android 14 prints it; permission entries below carry their own `flags=[ … ]`. */
    private val debuggableDumpsys = """
        Packages:
          Package [spock.adb.spock_flutter_sample] (a1b2):
            appId=10190
            flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
            runtime permissions:
              android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
    """.trimIndent()

    private val releaseDumpsys = debuggableDumpsys.replace("DEBUGGABLE ", "")

    @Test
    fun `a kernel blob is a debug build`() {
        assertEquals(FlutterBuild.DEBUG, FlutterBuild.of(debugListing, debuggable = true))
    }

    @Test
    fun `AOT is profile when debuggable and release when not`() {
        assertEquals(FlutterBuild.PROFILE, FlutterBuild.of(aotListing, debuggable = true))
        assertEquals(FlutterBuild.RELEASE, FlutterBuild.of(aotListing, debuggable = false))
    }

    @Test
    fun `no engine is not a Flutter app, whatever else the listing says`() {
        assertNull(FlutterBuild.of("", debuggable = true))
        assertNull(FlutterBuild.of("   assets/flutter_assets/kernel_blob.bin", debuggable = true))
    }

    @Test
    fun `debuggable is read from the package flags, not a permission's flags`() {
        assertTrue(FlutterBuild.isDebuggable(debuggableDumpsys))
        assertFalse(FlutterBuild.isDebuggable(releaseDumpsys))
    }

    @Test
    fun `the listing command quotes the package and reads split APKs`() {
        val command = FlutterBuild.listingCommand("spock.adb.spock_flutter_sample")
        assertTrue(command.startsWith("for a in \$(pm path 'spock.adb.spock_flutter_sample' | sed"), command)
    }

    @Test
    fun `AppInfo carries the Flutter build`() {
        val info = AppInfo.parse("spock.adb.spock_flutter_sample", debuggableDumpsys, "4242", debugListing)
        assertEquals(FlutterBuild.DEBUG, info.flutter)
        assertNull(AppInfo.parse("spock.adb.spock_flutter_sample", debuggableDumpsys, "4242").flutter)
    }
}
