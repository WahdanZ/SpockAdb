package spock.adb.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * A Hive box file (`<name>.hive`), as the Hive 2 package writes it: an append-only log of frames.
 *
 * ```
 * u32 length · key · value? · u32 crc32(everything before the crc)
 * key   = 0x00 u32 (int key) | 0x01 u8 length + ASCII (string key)
 * value = type byte + payload; absent in a deletion frame
 * ```
 *
 * A key's newest frame wins and a deletion frame removes it, which is what the app sees when it
 * opens the box. Read-only: writing means rewriting the log the way Hive compacts it, and an app
 * holding the box open would overwrite the edit.
 *
 * Values written by a custom `TypeAdapter` (type id 32 and up) have no self-describing layout,
 * so they are shown as opaque, and so is anything after a frame whose checksum does not match —
 * an encrypted box, or a write cut short.
 */
object HiveBox {

    data class Entry(val key: String, val type: String, val value: String)

    data class Box(
        /** Live keys, in Hive's order: int keys ascending, then string keys. */
        val entries: List<Entry>,
        val frames: Int,
        /** Why reading stopped before the end of the file, when it did. */
        val stoppedEarly: String? = null,
    )

    fun looksLikeHive(path: String): Boolean = path.endsWith(".hive")

    fun read(bytes: ByteArray): Box {
        val live = linkedMapOf<Any, Entry>()
        var frames = 0
        var offset = 0
        var stopped: String? = null
        while (offset < bytes.size) {
            val problem = frameProblem(bytes, offset)
            if (problem != null) {
                stopped = "$problem at byte $offset"
                break
            }
            val length = u32(bytes, offset).toInt()
            val frame = ByteBuffer.wrap(bytes, offset + LENGTH_BYTES, length - LENGTH_BYTES - CRC_BYTES)
                .slice()
                .order(ByteOrder.LITTLE_ENDIAN)
            val key = readKey(frame)
            if (frame.hasRemaining()) {
                val (type, value) = readValue(frame)
                live[key] = Entry(key.toString(), type, value)
            } else {
                live.remove(key)
            }
            frames++
            offset += length
        }
        val ordered = live.entries.sortedWith(compareBy({ it.key !is Long }, { it.key.toString() })).map { it.value }
        return Box(ordered, frames, stopped)
    }

    /** Null when a whole, intact frame starts at [offset]. */
    private fun frameProblem(bytes: ByteArray, offset: Int): String? {
        if (bytes.size - offset < LENGTH_BYTES) return "a truncated frame"
        val length = u32(bytes, offset)
        if (length < MIN_FRAME || length > bytes.size - offset) return "a frame with an impossible length"
        val crc = CRC32().apply { update(bytes, offset, length.toInt() - CRC_BYTES) }.value
        if (crc != u32(bytes, offset + length.toInt() - CRC_BYTES)) {
            return "a checksum mismatch (an encrypted box, or a write cut short)"
        }
        return null
    }

    private fun readKey(frame: ByteBuffer): Any = when (val kind = frame.get().toInt()) {
        KEY_INT -> frame.int.toLong() and UINT_MASK
        KEY_STRING -> String(ByteArray(frame.get().toInt() and BYTE_MASK).also { frame.get(it) }, Charsets.US_ASCII)
        else -> error("Unknown Hive key type $kind")
    }

    /** `(type, value)` as the table shows them. */
    private fun readValue(frame: ByteBuffer): Pair<String, String> {
        val type = frame.get().toInt() and BYTE_MASK
        return try {
            scalar(frame, type) ?: collection(frame, type)
        } catch (_: java.nio.BufferUnderflowException) {
            "?" to "value runs past its frame"
        }
    }

    private fun scalar(frame: ByteBuffer, type: Int): Pair<String, String>? = when (type) {
        NULL -> "null" to "null"
        INT -> "int" to frame.double.toLong().toString()
        DOUBLE -> "double" to frame.double.toString()
        BOOL -> "bool" to (frame.get().toInt() != 0).toString()
        STRING -> "string" to string(frame)
        BYTE_LIST -> "bytes" to frame.int.let { size ->
            frame.position(frame.position() + size)
            "$size bytes"
        }
        else -> null
    }

    private fun collection(frame: ByteBuffer, type: Int): Pair<String, String> = when (type) {
        INT_LIST -> "int list" to list(frame) { frame.double.toLong().toString() }
        DOUBLE_LIST -> "double list" to list(frame) { frame.double.toString() }
        BOOL_LIST -> "bool list" to list(frame) { (frame.get().toInt() != 0).toString() }
        STRING_LIST -> "string list" to list(frame) { quoted(string(frame)) }
        LIST -> "list" to list(frame) { readValue(frame).second }
        MAP -> "map" to map(frame)
        else -> opaque(type)
    }

    private fun opaque(type: Int): Pair<String, String> = when {
        type >= CUSTOM_TYPES -> "custom (type ${type - CUSTOM_TYPES})" to "written by a TypeAdapter; not decoded"
        else -> "type $type" to "not decoded"
    }

    private fun string(frame: ByteBuffer): String =
        String(ByteArray(frame.int).also { frame.get(it) }, Charsets.UTF_8)

    private fun list(frame: ByteBuffer, item: () -> String): String {
        val count = frame.int
        val shown = (0 until minOf(count, MAX_LIST_ITEMS)).map { item() }
        return shown.joinToString(", ", "[", if (count > shown.size) ", … ${count - shown.size} more]" else "]")
    }

    private fun map(frame: ByteBuffer): String {
        val count = frame.int
        val shown = (0 until minOf(count, MAX_LIST_ITEMS)).map {
            val key = readValue(frame).second
            "$key: ${readValue(frame).second}"
        }
        return shown.joinToString(", ", "{", if (count > shown.size) ", … ${count - shown.size} more}" else "}")
    }

    private fun quoted(value: String) = "\"$value\""

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and UINT_MASK

    private const val LENGTH_BYTES = 4
    private const val CRC_BYTES = 4

    /** Length, a one-byte key type with an empty string key, and the checksum. */
    private const val MIN_FRAME = LENGTH_BYTES + 2 + CRC_BYTES
    private const val UINT_MASK = 0xFFFF_FFFFL
    private const val BYTE_MASK = 0xFF
    private const val MAX_LIST_ITEMS = 20

    private const val KEY_INT = 0
    private const val KEY_STRING = 1

    private const val NULL = 0
    private const val INT = 1
    private const val DOUBLE = 2
    private const val BOOL = 3
    private const val STRING = 4
    private const val BYTE_LIST = 5
    private const val INT_LIST = 6
    private const val DOUBLE_LIST = 7
    private const val BOOL_LIST = 8
    private const val STRING_LIST = 9
    private const val LIST = 10
    private const val MAP = 11

    /** Type ids from here up belong to the app's own adapters. */
    private const val CUSTOM_TYPES = 32
}
