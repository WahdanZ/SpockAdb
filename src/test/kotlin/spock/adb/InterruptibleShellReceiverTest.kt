package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InterruptibleShellReceiverTest {

    private fun receiver() = InterruptibleShellReceiver { false }

    @Test
    fun `a character split across two chunks survives`() {
        // ddmlib chunks by buffer size, so a multi-byte character can straddle two reads. On an
        // Arabic, CJK or emoji screen that turned a text= selector's target into U+FFFD.
        val text = """text="مرحبا 你好 👋""""
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Inside the first Arabic letter, which is two bytes long.
        val split = """text="""".length + 1
        val receiver = receiver()

        receiver.addOutput(bytes, 0, split)
        receiver.addOutput(bytes, split, bytes.size - split)

        assertEquals(text, receiver.toString())
    }

    @Test
    fun `every split of a four-byte character survives`() {
        val bytes = "👋".toByteArray(Charsets.UTF_8)
        for (split in 1 until bytes.size) {
            val receiver = receiver()
            receiver.addOutput(bytes, 0, split)
            receiver.addOutput(bytes, split, bytes.size - split)

            assertEquals("👋", receiver.toString(), "split after byte $split")
        }
    }

    @Test
    fun `reads only the slice of the buffer it is handed`() {
        val bytes = "xxhelloxx".toByteArray(Charsets.UTF_8)
        val receiver = receiver()

        receiver.addOutput(bytes, 2, "hello".length)

        assertEquals("hello", receiver.toString())
    }

    @Test
    fun `strips trailing line endings but keeps interior ones`() {
        val bytes = "first\r\nsecond\r\n\r\n".toByteArray(Charsets.UTF_8)
        val receiver = receiver()

        receiver.addOutput(bytes, 0, bytes.size)

        assertEquals("first\r\nsecond", receiver.toString())
    }

    @Test
    fun `asks the signal whether to stop`() {
        var cancelled = false
        val receiver = InterruptibleShellReceiver { cancelled }

        assertFalse(receiver.isCancelled())
        cancelled = true
        assertTrue(receiver.isCancelled())
    }
}
