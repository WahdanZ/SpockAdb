package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatBufferTest {

    private fun entry(message: String, level: LogLevel = LogLevel.INFO) =
        LogcatEntry("t", 1, 1, level, "Tag", message, message)

    @Test
    fun `drops the oldest entries once full`() {
        // A busy device would otherwise grow the heap without limit.
        val buffer = LogcatBuffer(capacity = 3)
        listOf("1", "2", "3", "4").forEach { buffer.add(entry(it)) }

        assertEquals(listOf("2", "3", "4"), buffer.snapshot().map { it.message })
        assertEquals(3, buffer.size())
    }

    @Test
    fun `filtering applies to everything received, not just new lines`() {
        val buffer = LogcatBuffer()
        buffer.add(entry("keep", LogLevel.ERROR))
        buffer.add(entry("drop", LogLevel.DEBUG))

        val filtered = buffer.filtered(LogcatFilter(minLevel = LogLevel.ERROR))

        assertEquals(listOf("keep"), filtered.map { it.message })
    }

    @Test
    fun `clearing empties the buffer`() {
        val buffer = LogcatBuffer()
        buffer.add(entry("x"))
        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
    }
}
