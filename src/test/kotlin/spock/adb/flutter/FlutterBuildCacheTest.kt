package spock.adb.flutter

import com.android.ddmlib.IDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class FlutterBuildCacheTest {

    private val dumpsys = """
        Packages:
          Package [com.example.app] (a1b2):
            versionCode=7 minSdk=24 targetSdk=36
            flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
            lastUpdateTime=2026-10-01 05:31:02
    """.trimIndent()

    private val aotListing = """
          6619200  1981-01-01 01:01   lib/arm64-v8a/libapp.so
         11206032  1981-01-01 01:01   lib/arm64-v8a/libflutter.so
    """.trimIndent()

    private val cache = FlutterBuildCache()
    private var listings = 0

    private fun detect(
        serial: String = "emulator-5554",
        packageDump: String = dumpsys,
        list: () -> String = { aotListing },
    ) = cache.detect(serial, "com.example.app", packageDump) {
        listings++
        list()
    }

    @Test
    fun `an install is listed once`() {
        assertEquals(FlutterBuild.RELEASE, detect())
        assertEquals(FlutterBuild.RELEASE, detect())
        assertEquals(1, listings)
    }

    @Test
    fun `a native app is remembered as one too`() {
        assertNull(detect(list = { "" }))
        assertNull(detect(list = { aotListing }))
        assertEquals(1, listings)
    }

    @Test
    fun `an update, a new version or another device is listed again`() {
        detect()
        detect(packageDump = dumpsys.replace("05:31:02", "06:00:00"))
        detect(packageDump = dumpsys.replace("versionCode=7", "versionCode=8"))
        detect(serial = "emulator-5556")
        assertEquals(4, listings)
    }

    @Test
    fun `a failed listing is not an answer, and is tried again`() {
        assertNull(detect(list = { throw IOException("adb went away") }))
        assertEquals(FlutterBuild.RELEASE, detect())
        assertEquals(2, listings)
    }

    @Test
    fun `without a version and an update time nothing is remembered`() {
        val bare = dumpsys.lines().filterNot { it.contains("lastUpdateTime") }.joinToString("\n")
        detect(packageDump = bare)
        detect(packageDump = bare)
        assertEquals(2, listings)
    }

    @Test
    fun `the oldest install is forgotten past the capacity`() {
        val small = FlutterBuildCache(capacity = 1)
        val list = {
            listings++
            aotListing
        }
        small.detect("a", "com.example.app", dumpsys, list)
        small.detect("b", "com.example.app", dumpsys, list)
        small.detect("a", "com.example.app", dumpsys, list)
        assertEquals(3, listings)
    }

    @Test
    fun `adb failing is told from an app that is not Flutter`() {
        val device = mockk<IDevice>()
        every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } throws IOException("adb went away")

        assertEquals(FlutterBuildCache.Detection.AdbFailed, cache.readOn(device, "emulator-5554", "com.example.app"))
        assertNull(cache.detectOn(device, "emulator-5554", "com.example.app"))
        val listingFailed = cache.read("emulator-5554", "com.example.app", dumpsys) { throw IOException("gone") }
        assertEquals(FlutterBuildCache.Detection.AdbFailed, listingFailed)
    }

    @Test
    fun `a package that is not installed is read as not Flutter`() {
        // `dumpsys package` of a package that is not installed answers, with no versionCode;
        // `pm path` lists nothing, so neither does the listing.
        val device = mockk<IDevice>(relaxed = true)

        assertEquals(
            FlutterBuildCache.Detection.Listed(null),
            cache.readOn(device, "emulator-5554", "com.example.gone"),
        )
    }

    @Test
    fun `one report asking twice reads the package once`() {
        val device = mockk<IDevice>(relaxed = true)
        val commands = mutableListOf<String>()
        val command = slot<String>()
        every { device.executeShellCommand(capture(command), any(), any(), any<TimeUnit>()) } answers {
            commands += command.captured
        }
        var now = 0L
        val timed = FlutterBuildCache(clock = { now })

        timed.detectOn(device, "emulator-5554", "com.example.app")
        timed.detectOn(device, "emulator-5554", "com.example.app")
        now += 10_000
        timed.detectOn(device, "emulator-5554", "com.example.app")

        assertEquals(2, commands.count { it.startsWith("dumpsys package") }, "$commands")
    }
}
