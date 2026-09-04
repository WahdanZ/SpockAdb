package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.PullFileTool
import spock.adb.mcp.tools.PushFileTool
import java.nio.file.Files
import java.nio.file.Path

/**
 * File transfer is the one pair of tools that reaches off the device and onto the developer's
 * machine, so the tests are mostly about what it refuses to do.
 */
class FileToolsTest {

    @TempDir
    lateinit var pullDirectory: Path

    @TempDir
    lateinit var workDirectory: Path

    private val device = mockk<IDevice>(relaxed = true)
    private val connected: ConnectedDevice = FakeToolContext.device("emulator-5554").copy(device = device)
    private val context get() = FakeToolContext(available = listOf(connected))

    private fun pullTool() = PullFileTool { pullDirectory }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject().apply {
        pairs.forEach { (key, value) -> addProperty(key, value) }
    }

    /** Makes the device "contain" a file: pullFile writes the bytes where it is told to. */
    private fun deviceHolds(bytes: ByteArray) {
        every { device.pullFile(any(), any()) } answers {
            Files.write(Path.of(secondArg<String>()), bytes)
            Unit
        }
    }

    @Test
    fun `pulling from another app's private storage is refused, and says what is allowed`() {
        // The exfiltration case. adb would allow this; this tool deliberately does not.
        val failure = assertThrows<IllegalArgumentException> {
            pullTool().execute(args("remotePath" to "/data/data/com.bank.app/databases/accounts.db"), context)
        }

        assertTrue(failure.message!!.contains("/sdcard/"), failure.message)
        assertTrue(
            failure.message!!.contains("android_run_adb_command"),
            "should point at the gated escape hatch: ${failure.message}",
        )
    }

    @Test
    fun `a path that climbs out with dot-dot is refused`() {
        assertThrows<IllegalArgumentException> {
            pullTool().execute(args("remotePath" to "/sdcard/../data/data/com.bank.app/f"), context)
        }
    }

    @Test
    fun `a relative device path is refused`() {
        assertThrows<IllegalArgumentException> {
            pullTool().execute(args("remotePath" to "sdcard/thing.txt"), context)
        }
    }

    @Test
    fun `pulling lands the file in the IDE's pull directory, not anywhere the caller chose`() {
        deviceHolds("hello".toByteArray())

        val result = pullTool().execute(args("remotePath" to "/sdcard/notes.txt"), context)

        assertFalse(result.isError)
        val landed = pullDirectory.resolve("notes.txt")
        assertTrue(Files.exists(landed), "expected the pulled file at $landed")
        assertEquals("hello", Files.readString(landed))
        assertTrue(result.text().contains(landed.toString()), result.text())
    }

    @Test
    fun `a small text file comes back inline so no second call is needed`() {
        deviceHolds("line one\nline two".toByteArray())

        val result = pullTool().execute(args("remotePath" to "/sdcard/log.txt"), context)

        assertTrue(result.text().contains("line two"), result.text())
    }

    @Test
    fun `a binary file is not inlined as mojibake`() {
        // A .db whose first bytes look like ASCII must not be decoded and reasoned about.
        deviceHolds(byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x00, 0xFF.toByte()))

        val result = pullTool().execute(args("remotePath" to "/sdcard/app.db"), context)

        assertFalse(result.text().contains("Contents:"), result.text())
    }

    @Test
    fun `a failed pull leaves no half-written file behind under the real name`() {
        every { device.pullFile(any(), any()) } answers { } // writes nothing

        val failure = runCatching { pullTool().execute(args("remotePath" to "/sdcard/gone.txt"), context) }

        assertTrue(failure.isFailure, "a pull that produced no file must not report success")
        assertFalse(Files.exists(pullDirectory.resolve("gone.txt")))
        assertTrue(
            Files.list(pullDirectory).use { it.toList() }.isEmpty(),
            "the staging file must be cleaned up",
        )
    }

    @Test
    fun `pushing sends the local file and reports its size`() {
        val local = workDirectory.resolve("fixture.json")
        Files.writeString(local, """{"seeded":true}""")

        val result = PushFileTool().execute(
            args("localPath" to local.toString(), "remotePath" to "/sdcard/fixture.json"),
            context,
        )

        assertFalse(result.isError)
        verify { device.pushFile(local.toAbsolutePath().toString(), "/sdcard/fixture.json") }
        assertTrue(result.text().contains("15 bytes"), result.text())
    }

    @Test
    fun `pushing a file that is not there says so rather than failing obscurely`() {
        val result = PushFileTool().execute(
            args("localPath" to workDirectory.resolve("absent").toString(), "remotePath" to "/sdcard/x"),
            context,
        )

        assertTrue(result.isError)
        assertTrue(result.text().contains("is not a file"), result.text())
    }

    @Test
    fun `pushing outside the allowed device paths is refused`() {
        val local = workDirectory.resolve("payload")
        Files.writeString(local, "x")

        assertThrows<IllegalArgumentException> {
            PushFileTool().execute(
                args("localPath" to local.toString(), "remotePath" to "/system/bin/payload"),
                context,
            )
        }
    }
}
