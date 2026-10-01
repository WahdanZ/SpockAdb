package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random

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

    @Test
    fun `hostile frames are listed as undecodable and reading carries on`() {
        val after = frame(stringKey("after"), byteArrayOf(3, 1))
        val hostile = mapOf(
            "negative string length" to frame(stringKey("s"), byteArrayOf(4) + int(-1)),
            "huge string length" to frame(stringKey("s"), byteArrayOf(4) + int(Int.MAX_VALUE)),
            "negative byte list length" to frame(stringKey("s"), byteArrayOf(5) + int(-5)),
            "huge list count" to frame(stringKey("s"), byteArrayOf(10) + int(-1)),
            "min-size frame with an int key" to frame(byteArrayOf(0, 1)),
            "frame with no key at all" to frame(ByteArray(0)),
            "unknown key type" to frame(byteArrayOf(7, 1, 2)),
        )
        hostile.forEach { (case, bytes) ->
            val box = HiveBox.read(bytes + after)
            assertEquals(2, box.frames, case)
            assertNull(box.stoppedEarly, case)
            assertTrue(box.entries.any { it.value.startsWith("undecodable frame at byte 0") }, "$case: ${box.entries}")
            assertTrue(box.entries.contains(HiveBox.Entry("after", "bool", "true")), "$case: ${box.entries}")
        }
    }

    @Test
    fun `nesting deeper than the cap is described, not recursed into`() {
        val levels = 100_000
        val deep = ByteArrayOutputStream().apply {
            repeat(levels) { write(byteArrayOf(10) + int(1)) }
            write(0)
        }.toByteArray()
        val box = HiveBox.read(frame(stringKey("deep"), deep))

        val entry = box.entries.single()
        assertEquals("list", entry.type)
        assertTrue(entry.value.contains("nested too deeply"), entry.value)
    }

    @Test
    fun `random frames with valid lengths and checksums never throw`() {
        val random = Random(SEED)
        repeat(FUZZ_ROUNDS) { round ->
            val frames = 1 + random.nextInt(4)
            val bytes = ByteArrayOutputStream().apply {
                repeat(frames) { write(frame(fuzzBody(random))) }
            }.toByteArray()
            val box = runCatching { HiveBox.read(bytes) }
                .getOrElse { throw AssertionError("round $round threw on ${bytes.toList()}", it) }
            assertEquals(frames, box.frames, "round $round")
        }
    }

    @Test
    fun `a list stops at a custom value instead of reading its bytes as the next item`() {
        val list = byteArrayOf(10) + int(2) + byteArrayOf(40, 1, 2, 3) + byteArrayOf(4) + str("x")
        val box = HiveBox.read(frame(stringKey("l"), list) + frame(stringKey("z"), byteArrayOf(3, 1)))

        assertEquals(
            listOf(
                HiveBox.Entry("l", "list", "[<custom (type 8), not decoded>, … (1 more, not decoded)]"),
                HiveBox.Entry("z", "bool", "true"),
            ),
            box.entries,
        )
    }

    @Test
    fun `a long list inside a map is read to its end, so the next pair still decodes`() {
        val ints = byteArrayOf(6) + int(25) + (1..25).fold(ByteArray(0)) { acc, i -> acc + double(i.toDouble()) }
        val map = byteArrayOf(11) + int(2) +
            byteArrayOf(4) + str("a") + ints +
            byteArrayOf(4) + str("b") + byteArrayOf(3, 1)
        val value = HiveBox.read(frame(stringKey("m"), map)).entries.single().value

        assertTrue(value.startsWith("{\"a\": [1, 2, 3"), value)
        assertTrue(value.endsWith(", … 5 more], \"b\": true}"), value)
    }

    @Test
    fun `string keys are UTF-8 and Hive's own adapters are decoded`() {
        val millis = 1_790_000_000_000L
        // Two keys into box "users": int 3 and string "k".
        val hiveList = byteArrayOf(12) + int(2) + byteArrayOf(5) + "users".toByteArray() + intKey(3) + stringKey("k")
        val bytes = frame(utf8Key("größe"), byteArrayOf(4) + str("ü")) +
            frame(stringKey("seen"), byteArrayOf(18) + double(millis.toDouble()) + byteArrayOf(1)) +
            frame(stringKey("read"), byteArrayOf(16) + double(millis.toDouble())) +
            frame(stringKey("big"), byteArrayOf(17, 20) + "12345678901234567890".toByteArray()) +
            frame(stringKey("refs"), hiveList)
        val entries = HiveBox.read(bytes).entries.associateBy { it.key }

        assertEquals(HiveBox.Entry("größe", "string", "ü"), entries["größe"])
        assertEquals(HiveBox.Entry("seen", "DateTime (UTC)", "2026-09-21T14:13:20Z"), entries["seen"])
        assertEquals(HiveBox.Entry("read", "DateTime", "2026-09-21T14:13:20Z"), entries["read"])
        assertEquals(HiveBox.Entry("big", "BigInt", "12345678901234567890"), entries["big"])
        assertEquals(HiveBox.Entry("refs", "HiveList", "box users: [3, k]"), entries["refs"])
    }

    /** A key byte, then a mix of plausible type ids, small counts and noise. */
    private fun fuzzBody(random: Random): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(if (random.nextInt(8) == 0) random.nextInt(256) else random.nextInt(2))
        repeat(random.nextInt(48)) {
            when (random.nextInt(4)) {
                0 -> body.write(random.nextInt(20))
                1 -> body.write(int(random.nextInt(6)))
                2 -> body.write(int(random.nextInt()))
                else -> body.write(random.nextInt(256))
            }
        }
        return body.toByteArray()
    }

    private fun stringKey(key: String) = byteArrayOf(1, key.length.toByte()) + key.toByteArray()
    private fun utf8Key(key: String) = key.toByteArray().let { byteArrayOf(1, it.size.toByte()) + it }
    private fun intKey(key: Int) = byteArrayOf(0) + int(key)
    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun double(value: Double) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun str(value: String) = value.toByteArray().let { int(it.size) + it }

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

    private companion object {
        const val SEED = 20261001L
        const val FUZZ_ROUNDS = 5_000
    }
}
