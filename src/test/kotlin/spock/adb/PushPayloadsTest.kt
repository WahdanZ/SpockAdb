package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.command.PushMessage

class PushPayloadsTest {

    private val shipped = PushMessage(data = mapOf("orderId" to "42"), name = "order shipped")
    private val chat = PushMessage(title = "Hi", name = "chat message")

    @Test
    fun `a new name is added at the end`() {
        assertEquals(listOf(shipped, chat), payloadsWith(listOf(shipped), chat))
    }

    @Test
    fun `saving under a used name replaces that payload in place`() {
        val edited = shipped.copy(data = mapOf("orderId" to "43"))

        assertEquals(listOf(edited, chat), payloadsWith(listOf(shipped, chat), edited))
    }

    @Test
    fun `a payload needs a name to be saved`() {
        assertThrows<IllegalArgumentException> { payloadsWith(emptyList(), shipped.copy(name = "  ")) }
    }
}
