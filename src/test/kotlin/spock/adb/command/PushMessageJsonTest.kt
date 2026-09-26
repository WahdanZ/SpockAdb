package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Paste JSON reads the payloads developers already have, in the shapes FCM and backends use. */
class PushMessageJsonTest {

    @Test
    fun `an HTTP v1 request body gives the title, body and data`() {
        val message = PushMessageJson.parse(
            """
            {"message": {"token": "abc", "notification": {"title": "Shipped", "body": "Order 42"},
             "data": {"orderId": "42"}}}
            """,
        )

        assertEquals("Shipped", message.title)
        assertEquals("Order 42", message.body)
        assertEquals(mapOf("orderId" to "42"), message.data)
    }

    @Test
    fun `a legacy body without the message wrapper reads the same`() {
        val message = PushMessageJson.parse(
            """{"to": "abc", "notification": {"title": "Hi"}, "data": {"chatId": "7"}}""",
        )

        assertEquals("Hi", message.title)
        assertNull(message.body)
        assertEquals(mapOf("chatId" to "7"), message.data)
    }

    @Test
    fun `android notification is the fallback for title and body`() {
        val message = PushMessageJson.parse(
            """{"message": {"android": {"notification": {"title": "A", "body": "B"}}}}""",
        )

        assertEquals("A", message.title)
        assertEquals("B", message.body)
    }

    @Test
    fun `a plain object is a data payload`() {
        val message = PushMessageJson.parse("""{"type": "chat", "unread": 3, "muted": false}""")

        assertNull(message.title)
        assertEquals(mapOf("type" to "chat", "unread" to "3", "muted" to "false"), message.data)
    }

    @Test
    fun `a nested value is sent as its JSON, and order is kept`() {
        val message = PushMessageJson.parse("""{"data": {"b": "1", "order": {"id": 42, "items": ["x"]}, "a": null}}""")

        assertEquals(listOf("b", "order"), message.data.keys.toList())
        assertEquals("""{"id":42,"items":["x"]}""", message.data["order"])
    }

    @Test
    fun `broken or empty JSON is refused with a reason`() {
        val broken = assertThrows<IllegalArgumentException> { PushMessageJson.parse("""{"a": }""") }
        val array = assertThrows<IllegalArgumentException> { PushMessageJson.parse("""["a"]""") }
        val empty = assertThrows<IllegalArgumentException> { PushMessageJson.parse("""{"data": {}}""") }

        assertTrue(broken.message!!.contains("not valid JSON"), broken.message)
        assertTrue(array.message!!.contains("JSON object"), array.message)
        assertTrue(empty.message!!.contains("no title, body or data"), empty.message)
    }
}
