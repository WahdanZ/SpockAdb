package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ShellOutputReceiverTest {

    private fun ShellOutputReceiver.feed(text: String) =
        text.toByteArray().let { addOutput(it, 0, it.size) }

    @Test
    fun `joins output arriving in multiple chunks`() {
        val receiver = ShellOutputReceiver()
        receiver.feed("package:com.example")
        receiver.feed(".app")

        assertEquals("package:com.example.app", receiver.toString())
    }

    @Test
    fun `strips the DOS line endings adb appends`() {
        val receiver = ShellOutputReceiver()
        receiver.feed("1\r\n")

        assertEquals("1", receiver.toString())
    }

    @Test
    fun `strips repeated trailing newlines but keeps interior ones`() {
        val receiver = ShellOutputReceiver()
        receiver.feed("first\r\nsecond\r\n\r\n")

        assertEquals("first\r\nsecond", receiver.toString())
    }

    @Test
    fun `is empty when the device produced no output`() {
        assertEquals("", ShellOutputReceiver().toString())
    }

    @Test
    fun `reports itself as not cancelled`() {
        // ddmlib polls this between chunks. It is always false today, which is why long
        // running shell commands cannot currently be interrupted.
        assertFalse(ShellOutputReceiver().isCancelled)
    }

    @Test
    fun `honours the offset and length ddmlib passes`() {
        val receiver = ShellOutputReceiver()
        val buffer = "XXhelloYY".toByteArray()
        receiver.addOutput(buffer, 2, 5)

        assertEquals("hello", receiver.toString())
    }
}
