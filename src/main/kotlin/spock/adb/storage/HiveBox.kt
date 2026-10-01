package spock.adb.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.zip.CRC32

/**
 * A Hive box file (`<name>.hive`), as the Hive 2 package writes it: an append-only log of frames.
 *
 * ```
 * u32 length · key · value? · u32 crc32(length, key and value)
 * key   = 0x00 u32 (int key) | 0x01 u8 byte count + UTF-8 (string key)
 * value = type byte + payload; absent in a deletion frame
 * ```
 *
 * Checked against hive 2.2.3's own reader and writer (`lib/src/binary/binary_reader_impl.dart`,
 * `binary_writer_impl.dart`, `frame.dart`, and `lib/src/adapters/`):
 *  - Everything is little-endian. A frame's length counts itself and the checksum; Hive stops at
 *    a length under 8 or one that runs past the file, and so does this.
 *  - An int, alone or in an int list, is stored as a float64 and read back with `toInt()`, so
 *    ints past 2^53 have already lost precision on disk. Strings are a u32 byte count and UTF-8.
 *  - Value types 0–12 are Hive's own; 12 is a `HiveList` (a box name and keys into it). The
 *    built-in adapters are registered internally at 16 (`DateTime` as read back by Hive), 17
 *    (`BigInt`) and 18 (any other `DateTime`, with its UTC flag). An app's own `TypeAdapter`
 *    with id n is written as n + 32. Anything else is reserved and has no layout to read.
 *  - The checksum is CRC-32 over the frame up to the checksum, seeded with 0. An encrypted box
 *    seeds it with the CRC of the key's SHA-256 and encrypts the value (the key stays clear),
 *    so without the key no frame of it checks out — Hive itself treats a mismatch as the end of
 *    the box, and so does this.
 *
 * A key's newest frame wins and a deletion frame removes it, which is what the app sees when it
 * opens the box. Read-only: writing means rewriting the log the way Hive compacts it, and an app
 * holding the box open would overwrite the edit.
 *
 * Values a custom `TypeAdapter` wrote have no self-describing layout, so they are shown as
 * opaque; inside a list or map nothing after one can be found, so the rest of it is counted
 * rather than decoded. [read] never throws: a frame whose checksum holds but whose content does
 * not parse is listed as undecodable, and reading carries on with the next frame.
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
        val live = linkedMapOf<HiveKey, Entry>()
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
            decodeFrame(HiveReader(frame), offset, live)
            frames++
            offset += length
        }
        val ordered = live.entries.sortedWith(compareBy(HiveKey.ORDER) { it.key }).map { it.value }
        return Box(ordered, frames, stopped)
    }

    /**
     * A string as one line of the table: backslashes doubled and control characters written as
     * escapes, so a newline in a value cannot pass for the next row.
     */
    internal fun escaped(value: String): String = buildString {
        value.forEach { c ->
            when {
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.isISOControl() -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    /** A string in quotes, so a string key or item reads apart from an int one. */
    internal fun quoted(value: String): String = "\"" + escaped(clipped(value)).replace("\"", "\\\"") + "\""

    /** At most [VALUE_LIMIT] characters, never splitting a surrogate pair. */
    internal fun clipped(value: String): String {
        if (value.length <= VALUE_LIMIT) return value
        val end = if (value[VALUE_LIMIT - 1].isHighSurrogate()) VALUE_LIMIT - 1 else VALUE_LIMIT
        return value.take(end) + "…"
    }

    /**
     * Applies one intact frame to [live]. The checksum held, so the frame's extent is known even
     * when its content is nonsense: a failure is recorded against the frame, not the file.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever one frame holds, the next is still readable.
    private fun decodeFrame(reader: HiveReader, offset: Int, live: MutableMap<HiveKey, Entry>) {
        var key: HiveKey = HiveKey.Unreadable(offset)
        try {
            key = reader.key()
            if (reader.hasRemaining) {
                val value = reader.value()
                // A string is raw text; everything else was built from escaped pieces.
                val text = if (value.type == STRING_TYPE) escaped(clipped(value.text)) else clipped(value.text)
                live[key] = Entry(key.label, value.type, text)
            } else {
                live.remove(key)
            }
        } catch (e: RuntimeException) {
            live[key] = Entry(key.label, "?", "undecodable frame at byte $offset: ${HiveReader.reason(e)}")
        }
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

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and UINT_MASK

    private const val LENGTH_BYTES = 4
    private const val CRC_BYTES = 4

    /** Hive's own floor: the length and the checksum, with nothing between them. */
    private const val MIN_FRAME = LENGTH_BYTES + CRC_BYTES
    private const val UINT_MASK = 0xFFFF_FFFFL

    /** Long enough for a token or a URL; a value longer than this is cut and marked. */
    private const val VALUE_LIMIT = 200
    internal const val STRING_TYPE = "string"
}

/** A frame's key, kept typed so int 5 and string "5" stay two keys, as they are to Hive. */
private sealed interface HiveKey {
    /** As the table shows it: an int bare, a string quoted. */
    val label: String

    data class Numeric(val value: Long) : HiveKey {
        override val label get() = value.toString()
    }

    data class Text(val value: String) : HiveKey {
        override val label get() = HiveBox.quoted(value)
    }

    /** A frame whose key could not be read; it replaces nothing, so it is keyed by where it is. */
    data class Unreadable(val offset: Int) : HiveKey {
        override val label get() = "?"
    }

    companion object {
        /** Hive's `defaultKeyComparator`: ints by value, then strings; unreadable frames last. */
        val ORDER: Comparator<HiveKey> = compareBy<HiveKey> {
            when (it) {
                is Numeric -> 0
                is Text -> 1
                is Unreadable -> 2
            }
        }.thenComparator { a, b ->
            when {
                a is Numeric && b is Numeric -> a.value.compareTo(b.value)
                a is Text && b is Text -> a.value.compareTo(b.value)
                a is Unreadable && b is Unreadable -> a.offset.compareTo(b.offset)
                else -> 0
            }
        }
    }
}

/**
 * One value as the table shows it.
 *
 * [complete] is false when the payload was not consumed — an [opaque] value from a custom
 * adapter or a reserved type, or a list or map that stopped at one — so whatever holds it cannot
 * find what follows.
 */
private data class HiveValue(
    val type: String,
    val text: String,
    val complete: Boolean = true,
    val opaque: Boolean = false,
)

/** Content that cannot be what Hive wrote: a length past the frame, an unknown key type. */
private class MalformedHive(message: String) : RuntimeException(message)

/**
 * Reads keys and values out of one frame's bytes, the way hive 2.2.3's `BinaryReaderImpl` does.
 *
 * Every length and count is checked against what is left of the frame before anything is
 * allocated or iterated, so a hostile count cannot ask for gigabytes or loop for long, and
 * nesting stops at [MAX_DEPTH] rather than at the end of the stack.
 */
private class HiveReader(private val frame: ByteBuffer) {

    val hasRemaining: Boolean get() = frame.hasRemaining()

    fun key(): HiveKey = when (val kind = byte()) {
        KEY_INT -> HiveKey.Numeric(u32())
        KEY_STRING -> HiveKey.Text(utf8(byte().toLong()))
        else -> throw MalformedHive("unknown key type $kind")
    }

    fun value(depth: Int = 0): HiveValue {
        val type = byte()
        return scalar(type) ?: collection(type, depth) ?: adapter(type)
    }

    private fun scalar(type: Int): HiveValue? = when (type) {
        NULL -> HiveValue("null", "null")
        INT -> HiveValue("int", int())
        DOUBLE -> HiveValue("double", frame.double.toString())
        BOOL -> HiveValue("bool", bool())
        STRING -> HiveValue(HiveBox.STRING_TYPE, utf8(u32()))
        BYTE_LIST -> u32().let { size ->
            skip(size)
            HiveValue("bytes", "$size bytes")
        }
        else -> null
    }

    private fun collection(type: Int, depth: Int): HiveValue? = when (type) {
        INT_LIST -> HiveValue("int list", list(count(DOUBLE_BYTES)) { int() })
        DOUBLE_LIST -> HiveValue("double list", list(count(DOUBLE_BYTES)) { frame.double.toString() })
        BOOL_LIST -> HiveValue("bool list", list(count(1)) { bool() })
        STRING_LIST -> HiveValue("string list", list(count(LENGTH_BYTES)) { HiveBox.quoted(utf8(u32())) })
        LIST -> nested("list", depth, "[", "]") { items(count(1), depth) }
        MAP -> nested("map", depth, "{", "}") { pairs(count(2), depth) }
        HIVE_LIST -> hiveList()
        else -> null
    }

    private fun adapter(type: Int): HiveValue = when {
        type == DATE_TIME -> HiveValue("DateTime", instant())
        type == DATE_TIME_TZ -> {
            val at = instant()
            HiveValue(if (bool() == "true") "DateTime (UTC)" else "DateTime", at)
        }
        type == BIG_INT -> HiveValue("BigInt", HiveBox.escaped(utf8(byte().toLong())))
        type >= CUSTOM_TYPES -> opaque("custom (type ${type - CUSTOM_TYPES})", "written by a TypeAdapter")
        else -> opaque("type $type", "a type Hive reserves")
    }

    private fun opaque(type: String, why: String) =
        HiveValue(type, "$why; not decoded", complete = false, opaque = true)

    /** A list or map, unless it is nested deeper than anything worth reading. */
    private fun nested(type: String, depth: Int, open: String, close: String, body: () -> Joined): HiveValue {
        if (depth >= MAX_DEPTH) return HiveValue(type, "$open… nested too deeply$close", complete = false)
        val joined = body()
        return HiveValue(type, open + joined.text + close, joined.complete)
    }

    private fun items(count: Long, depth: Int): Joined {
        val shown = mutableListOf<String>()
        for (index in 0 until count) {
            val item = value(depth + 1)
            if (shown.size < MAX_LIST_ITEMS) shown += inner(item)
            if (!item.complete) return Joined.stopped(shown, count - index - 1)
        }
        return Joined.whole(shown, count)
    }

    private fun pairs(count: Long, depth: Int): Joined {
        val shown = mutableListOf<String>()
        for (index in 0 until count) {
            val key = value(depth + 1)
            val value = if (key.complete) value(depth + 1) else null
            if (shown.size < MAX_LIST_ITEMS) shown += inner(key) + ": " + (value?.let(::inner) ?: "…")
            if (value?.complete != true) return Joined.stopped(shown, count - index - 1)
        }
        return Joined.whole(shown, count)
    }

    /** A value inside a list or map: strings quoted so they read apart from numbers and markers. */
    private fun inner(item: HiveValue): String = when {
        item.opaque -> "<${item.type}, not decoded>"
        item.type == HiveBox.STRING_TYPE -> HiveBox.quoted(item.text)
        else -> item.text
    }

    /** Hive objects held by key in another box: `u32 count · u8 name length · name · keys`. */
    private fun hiveList(): HiveValue {
        val count = count(2)
        val box = HiveBox.escaped(latin1(byte()))
        return HiveValue("HiveList", "box $box: " + list(count) { key().label })
    }

    /** Every item is read, so the frame is consumed; the first [MAX_LIST_ITEMS] are shown. */
    private fun list(count: Long, item: () -> String): String {
        val shown = mutableListOf<String>()
        for (index in 0 until count) {
            val text = item()
            if (shown.size < MAX_LIST_ITEMS) shown += text
        }
        return "[" + Joined.whole(shown, count).text + "]"
    }

    /** Hive's `readInt`: a float64 truncated toward zero. */
    private fun int(): String = frame.double.let { if (it.isFinite()) it.toLong().toString() else it.toString() }

    private fun bool(): String = (byte() > 0).toString()

    /** Milliseconds since the epoch, stored the way an int is: as a float64. */
    private fun instant(): String = frame.double.let { millis ->
        if (millis.isFinite()) Instant.ofEpochMilli(millis.toLong()).toString() else millis.toString()
    }

    private fun byte(): Int = frame.get().toInt() and BYTE_MASK

    private fun u32(): Long = frame.int.toLong() and UINT_MASK

    /** A u32 count of items at least [bytesEach] long, refused when the frame cannot hold them. */
    private fun count(bytesEach: Int): Long = u32().also { fits(it * bytesEach, "$it items") }

    private fun skip(size: Long) {
        fits(size, "$size bytes")
        frame.position(frame.position() + size.toInt())
    }

    private fun utf8(size: Long): String {
        fits(size, "a string of $size bytes")
        return String(ByteArray(size.toInt()).also { frame.get(it) }, Charsets.UTF_8)
    }

    private fun latin1(size: Int): String {
        fits(size.toLong(), "a name of $size bytes")
        return String(ByteArray(size).also { frame.get(it) }, Charsets.ISO_8859_1)
    }

    private fun fits(size: Long, what: String) {
        if (size > frame.remaining()) throw MalformedHive("$what would run past the frame")
    }

    /** The items of a list or map, joined, and whether the container was read to its end. */
    private class Joined(val text: String, val complete: Boolean) {
        companion object {
            fun whole(shown: List<String>, count: Long): Joined {
                val more = if (count > shown.size) ", … ${count - shown.size} more" else ""
                return Joined(shown.joinToString(", ") + more, true)
            }

            /** Stopped at an item whose end is unknown: the [notDecoded] items after it are not read. */
            fun stopped(shown: List<String>, notDecoded: Long): Joined {
                val rest = if (notDecoded > 0) "… ($notDecoded more, not decoded)" else null
                return Joined((shown + listOfNotNull(rest)).joinToString(", "), false)
            }
        }
    }

    companion object {
        const val MAX_DEPTH = 8

        /** Why a frame did not parse, as one line for the table. */
        fun reason(e: RuntimeException): String = when (e) {
            is MalformedHive -> e.message.orEmpty()
            is java.nio.BufferUnderflowException -> "it ends in the middle of a value"
            else -> e.javaClass.simpleName
        }

        private const val UINT_MASK = 0xFFFF_FFFFL
        private const val BYTE_MASK = 0xFF
        private const val LENGTH_BYTES = 4
        private const val DOUBLE_BYTES = 8
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
        private const val HIVE_LIST = 12

        /** Hive's internal adapters: `DateTimeAdapter`, `BigIntAdapter`, `DateTimeWithTimezoneAdapter`. */
        private const val DATE_TIME = 16
        private const val BIG_INT = 17
        private const val DATE_TIME_TZ = 18

        /** Type ids from here up belong to the app's own adapters, as `typeId + 32`. */
        private const val CUSTOM_TYPES = 32
    }
}
