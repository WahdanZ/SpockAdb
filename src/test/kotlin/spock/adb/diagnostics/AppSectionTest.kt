package spock.adb.diagnostics

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.TimeoutException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterBuild
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The app section against a scripted device. Whether the app is Flutter is one detail of the
 * section: finding out must not cost the rest of it, nor list the APK on every report.
 */
class AppSectionTest {

    private val pkg = "spock.adb.spock_flutter_sample"

    private val dumpsys = """
        Packages:
          Package [$pkg] (a1b2):
            versionCode=1 minSdk=21 targetSdk=34
            flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
            lastUpdateTime=2026-10-01 05:31:02
    """.trimIndent()

    private val debugListing = """
         39525904  1981-01-01 01:01   assets/flutter_assets/kernel_blob.bin
         39599552  1981-01-01 01:01   lib/arm64-v8a/libflutter.so
    """.trimIndent()

    private val commands = mutableListOf<String>()

    /** A probe whose APK listing answers with [listed], or times out when it is null. */
    private fun probe(listed: String?, packageDump: String = dumpsys): AndroidProbe {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val issued = command.captured
            commands += issued
            val reply = when {
                issued.startsWith("dumpsys package") -> packageDump
                issued.startsWith("pidof") -> "4242"
                issued == FlutterBuild.listingCommand(pkg) -> listed ?: throw TimeoutException()
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        // A serial of its own, so no other test's answer is remembered for this one.
        return AndroidProbe(device, "emulator-${UUID.randomUUID()}", pkg)
    }

    @Test
    fun `a listing that times out leaves the rest of the section`() {
        val data = AppSection.collect(probe(listed = null)).data

        assertEquals(pkg, data["packageName"].asString)
        assertTrue(data["running"].asBoolean)
        assertFalse(data.has("flutter"), "$data")
    }

    @Test
    fun `only a debug build is said to send its framework errors to the VM Service`() {
        val debug = AppSection.collect(probe(listed = debugListing)).data
        assertEquals(AppSection.FLUTTER_ERRORS_NOTE, debug["flutterNote"].asString)

        val aot = """
              6619200  1981-01-01 01:01   lib/arm64-v8a/libapp.so
             11206032  1981-01-01 01:01   lib/arm64-v8a/libflutter.so
        """.trimIndent()
        val profile = AppSection.collect(probe(listed = aot)).data
        val release = AppSection.collect(probe(listed = aot, packageDump = dumpsys.replace("DEBUGGABLE ", ""))).data
        assertEquals("profile", profile["flutter"].asString)
        assertEquals("release", release["flutter"].asString)
        assertFalse(profile.has("flutterNote"), "$profile")
        assertFalse(release.has("flutterNote"), "$release")
    }

    @Test
    fun `the APK is listed once per install, not on every report`() {
        val probe = probe(listed = debugListing)

        assertEquals("debug", AppSection.collect(probe).data["flutter"].asString)
        assertEquals("debug", AppSection.collect(probe).data["flutter"].asString)

        assertEquals(1, commands.count { it == FlutterBuild.listingCommand(pkg) }, "$commands")
    }
}
