package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageFileViewTest {

    @Test
    fun `text stays text, binary becomes a hex dump`() {
        assertEquals(StorageFileView.Kind.TEXT, StorageFileView.kindOf("files/a.txt", "héllo\n".toByteArray()))
        val binary = byteArrayOf(0x18, 0, 0, 0, 0x41, 0x42)
        assertEquals(StorageFileView.Kind.BINARY, StorageFileView.kindOf("files/a.bin", binary))
        val dump = StorageFileView.render("files/a.bin", binary)
        assertTrue(dump.contains("00000000  18 00 00 00 41 42"), dump)
        assertTrue(dump.trimEnd().endsWith("....AB"), dump)
    }

    @Test
    fun `invalid UTF-8 without NUL is binary too`() {
        assertEquals(StorageFileView.Kind.BINARY, StorageFileView.kindOf("x", byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `a SQLite file is recognised by its header, whatever its name`() {
        val header = "SQLite format 3\u0000".toByteArray() + ByteArray(84)
        assertEquals(StorageFileView.Kind.SQLITE, StorageFileView.kindOf("databases/notes", header))
    }

    @Test
    fun `a control character other than a tab or line break makes it binary`() {
        val escape = "plain\u001b[2Jtext".toByteArray()
        assertEquals(StorageFileView.Kind.BINARY, StorageFileView.kindOf("files/log.txt", escape))
        assertEquals(StorageFileView.Kind.TEXT, StorageFileView.kindOf("files/a.txt", "a\tb\r\nc\n".toByteArray()))
    }

    @Test
    fun `text is sniffed on its first block and sanitised when shown`() {
        // Past the sniffed block: a control character and a byte that is not UTF-8.
        val bytes = "a".repeat(5000).toByteArray() + byteArrayOf(0x1b, 0xC3.toByte(), 0x28) + "\r\nend".toByteArray()
        assertEquals(StorageFileView.Kind.TEXT, StorageFileView.kindOf("files/a.txt", bytes))

        val shown = StorageFileView.render("files/a.txt", bytes)
        assertTrue(shown.endsWith("��(\nend"), shown.takeLast(10))
    }

    @Test
    fun `a character cut in half by the end of the sniffed block is still text`() {
        val bytes = ("a".repeat(4095) + "é").toByteArray()
        assertEquals(4097, bytes.size)
        assertEquals(StorageFileView.Kind.TEXT, StorageFileView.kindOf("files/a.txt", bytes))
        // The same cut at the end of the file is a truncated character, not text.
        assertEquals(StorageFileView.Kind.BINARY, StorageFileView.kindOf("files/b.txt", bytes.copyOf(4096)))
    }

    @Test
    fun `a hive file that is not a Hive box falls back to its bytes`() {
        val shown = StorageFileView.render("app_flutter/notes.hive", "not a box at all".toByteArray())
        val note = "Not readable as a Hive box: a frame with an impossible length at byte 0.\n"
        assertTrue(shown.startsWith(note), shown)
        assertTrue(shown.contains("Binary file · 16 bytes"), shown)
    }

    @Test
    fun `an empty hive file is an empty box`() {
        assertTrue(StorageFileView.render("app_flutter/empty.hive", ByteArray(0)).startsWith("Hive box · 0 keys"))
    }

    @Test
    fun `a long binary file shows its first block and says so`() {
        val dump = StorageFileView.hexDump(ByteArray(5000), limit = 32)
        assertTrue(dump.startsWith("Binary file · 5000 bytes (first 32 shown)"), dump)
        assertEquals(2, dump.lines().count { it.startsWith("000000") })
    }
}
