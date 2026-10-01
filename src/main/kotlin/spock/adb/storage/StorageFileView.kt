package spock.adb.storage

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * How the storage tree shows a file it cannot edit: decoded when the format is known, as text
 * when it is text, and as a hex dump otherwise — never as binary pushed through a text decoder.
 *
 * Everything here may take a while on a large file, so the panel calls it off the EDT.
 */
object StorageFileView {

    enum class Kind { HIVE, SQLITE, TEXT, BINARY }

    fun kindOf(path: String, bytes: ByteArray): Kind = when {
        isSqlite(bytes) -> Kind.SQLITE
        HiveBox.looksLikeHive(path) -> Kind.HIVE
        isText(bytes) -> Kind.TEXT
        else -> Kind.BINARY
    }

    /** The text to show for every kind except [Kind.SQLITE], which needs the device to read it. */
    fun render(path: String, bytes: ByteArray): String = when (kindOf(path, bytes)) {
        Kind.HIVE -> hive(HiveBox.read(bytes))
        Kind.TEXT -> printable(bytes.decodeToString())
        Kind.SQLITE, Kind.BINARY -> hexDump(bytes)
    }

    fun hive(box: HiveBox.Box): String = buildString {
        append("Hive box · ${box.entries.size} key${if (box.entries.size == 1) "" else "s"}")
        val records = "${box.frames} record${if (box.frames == 1) "" else "s"}"
        append(" ($records in the file; the newest per key wins) · read-only\n")
        box.stoppedEarly?.let { append("Stopped reading at $it.\n") }
        if (box.entries.isEmpty()) return@buildString
        val keyWidth = maxOf(KEY.length, box.entries.maxOf { it.key.length })
        val typeWidth = maxOf(TYPE.length, box.entries.maxOf { it.type.length })
        append("\n").append(KEY.padEnd(keyWidth)).append("  ").append(TYPE.padEnd(typeWidth)).append("  value\n")
        box.entries.forEach {
            append(it.key.padEnd(keyWidth)).append("  ")
            append(it.type.padEnd(typeWidth)).append("  ")
            append(it.value).append("\n")
        }
    }

    /** `od`-style: offset, sixteen bytes in hex, and the printable ones. */
    fun hexDump(bytes: ByteArray, limit: Int = HEX_LIMIT): String = buildString {
        append("Binary file · ${bytes.size} bytes")
        if (bytes.size > limit) append(" (first $limit shown)")
        append("\n\n")
        bytes.take(limit).chunked(HEX_ROW).forEachIndexed { row, chunk ->
            append("%08x  ".format(row * HEX_ROW))
            append(chunk.joinToString(" ") { "%02x".format(it) }.padEnd(HEX_COLUMN_WIDTH))
            append("  ")
            chunk.forEach { append(if (it in PRINTABLE) it.toInt().toChar() else '.') }
            append("\n")
        }
    }

    fun isSqlite(bytes: ByteArray): Boolean =
        bytes.size >= SQLITE_MAGIC.size && SQLITE_MAGIC.indices.all { bytes[it] == SQLITE_MAGIC[it] }

    /**
     * Text with every control character but a tab or a line break replaced, so a stray escape
     * sequence cannot rearrange what is on screen. Windows line ends become plain ones.
     */
    fun printable(text: String): String = buildString(text.length) {
        text.replace("\r\n", "\n").forEach { append(if (it.isISOControl() && it != '\n' && it != '\t') '�' else it) }
    }

    /**
     * What a text editor would open without complaint, judged on the first block only: valid
     * UTF-8, and no control character but a tab or a line break. A character the block's end cuts
     * in half is not held against it.
     */
    private fun isText(bytes: ByteArray): Boolean {
        val head = bytes.copyOf(minOf(bytes.size, TEXT_SNIFF))
        if (head.any { it in C0 && it !in TEXT_CONTROLS }) return false
        val result = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.size), head.size == bytes.size)
        return !result.isError
    }

    private const val KEY = "key"
    private const val TYPE = "type"
    private const val HEX_LIMIT = 4096
    private const val HEX_ROW = 16

    /** Sixteen two-digit bytes and the fifteen spaces between them. */
    private const val HEX_COLUMN_WIDTH = HEX_ROW * 3 - 1
    private const val TEXT_SNIFF = 4096
    private val PRINTABLE = 0x20.toByte()..0x7e.toByte()
    private val C0 = 0x00.toByte()..0x1f.toByte()
    private val TEXT_CONTROLS = setOf('\t'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte())
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
