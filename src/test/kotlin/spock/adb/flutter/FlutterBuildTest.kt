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

    /**
     * An App Bundle install: base.apk holds the assets, and the engine and the AOT snapshot sit
     * in the ABI split. The listing runs over every APK, so what is in a split is seen too.
     */
    @Test
    fun `a split APK install is read across its splits`() {
        // `unzip -l` of each APK in turn, before the grep: the split's archive header included.
        val splitListing = """
            Archive:  /data/app/~~a1/spock.adb.spock_flutter_sample-b2/base.apk
                 2398  1981-01-01 01:01   assets/flutter_assets/AssetManifest.bin
                 8264  1981-01-01 01:01   classes.dex
            Archive:  /data/app/~~a1/spock.adb.spock_flutter_sample-b2/split_config.arm64_v8a.apk
              6619200  1981-01-01 01:01   lib/arm64-v8a/libapp.so
             11206032  1981-01-01 01:01   lib/arm64-v8a/libflutter.so
        """.trimIndent()
        assertEquals(FlutterBuild.RELEASE, FlutterBuild.of(splitListing, debuggable = false))
        // Forced debuggable, a release build cannot be told from a profile one.
        assertEquals(FlutterBuild.PROFILE, FlutterBuild.of(splitListing, debuggable = true))
        assertTrue(FlutterBuild.listingCommand("com.example.app").contains("pm path 'com.example.app'"))
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
        val info = AppInfo.parse("spock.adb.spock_flutter_sample", debuggableDumpsys, "4242", FlutterBuild.DEBUG)
        assertEquals(FlutterBuild.DEBUG, info.flutter)
        assertNull(AppInfo.parse("spock.adb.spock_flutter_sample", debuggableDumpsys, "4242").flutter)
    }
}
