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
    fun `a long binary file shows its first block and says so`() {
        val dump = StorageFileView.hexDump(ByteArray(5000), limit = 32)
        assertTrue(dump.startsWith("Binary file · 5000 bytes (first 32 shown)"), dump)
        assertEquals(2, dump.lines().count { it.startsWith("000000") })
    }
}
