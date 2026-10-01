package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class HiveBoxTest {

    /** Written by hive 2.2.3 from sample/flutter_app's Storage screen, twice: six frames, three keys. */
    private val device = checkNotNull(javaClass.getResourceAsStream("/storage/settings.hive")).readBytes()

    @Test
    fun `reads the newest value of each key from a real box`() {
        val box = HiveBox.read(device)

        assertEquals(
            listOf(
                HiveBox.Entry("font_scale", "double", "1.2"),
                HiveBox.Entry("onboarded", "bool", "true"),
                HiveBox.Entry("theme", "string", "dark"),
            ),
            box.entries,
        )
        assertEquals(6, box.frames)
        assertNull(box.stoppedEarly)
    }

    @Test
    fun `a deletion frame removes the key`() {
        val box = HiveBox.read(device + frame(stringKey("theme")))
        assertEquals(listOf("font_scale", "onboarded"), box.entries.map { it.key })
    }

    @Test
    fun `int keys come first, and lists and custom adapters are described`() {
        val bytes = frame(intKey(7), byteArrayOf(9) + int(2) + str("a") + str("b")) +
            frame(stringKey("user"), byteArrayOf(40, 1, 2, 3)) +
            frame(intKey(2), byteArrayOf(1) + double(42.0))
        val box = HiveBox.read(bytes)

        assertEquals(listOf("2", "7", "user"), box.entries.map { it.key })
        assertEquals(HiveBox.Entry("2", "int", "42"), box.entries[0])
        assertEquals(HiveBox.Entry("7", "string list", "[\"a\", \"b\"]"), box.entries[1])
        assertEquals("custom (type 8)", box.entries[2].type)
    }

    @Test
    fun `a checksum mismatch stops reading and says why`() {
        val broken = device.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val box = HiveBox.read(broken)

        assertTrue(box.stoppedEarly!!.contains("checksum"), box.stoppedEarly)
        assertTrue(box.entries.isEmpty())
    }

    @Test
    fun `a truncated last frame keeps what came before it`() {
        val box = HiveBox.read(device.copyOf(device.size - 3))
        assertEquals(3, box.entries.size)
        assertEquals(5, box.frames)
        assertTrue(box.stoppedEarly!!.contains("length"), box.stoppedEarly)
    }

    @Test
    fun `the view lines the table up and is read-only`() {
        val text = StorageFileView.render("app_flutter/settings.hive", device)
        val heading = "Hive box · 3 keys (6 records in the file; the newest per key wins) · read-only"
        assertTrue(text.startsWith(heading), text)
        assertTrue(text.contains("font_scale  double  1.2"), text)
    }

    private fun stringKey(key: String) = byteArrayOf(1, key.length.toByte()) + key.toByteArray()
    private fun intKey(key: Int) = byteArrayOf(0) + int(key)
    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun double(value: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun str(value: String) = int(value.length) + value.toByteArray()

    /** A whole frame: length, key, value (absent for a deletion), and its CRC32. */
    private fun frame(key: ByteArray, value: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(int(4 + key.size + value.size + 4))
            write(key)
            write(value)
        }.toByteArray()
        val crc = CRC32().apply { update(body) }.value.toInt()
        return body + int(crc)
    }
}
