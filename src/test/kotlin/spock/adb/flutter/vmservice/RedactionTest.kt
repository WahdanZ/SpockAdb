package spock.adb.flutter.vmservice

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class RedactionTest {

    private fun fixture(name: String): JsonObject =
        JsonParser.parseString(javaClass.getResource("/vmservice/$name")!!.readText()).asJsonObject

    @Test
    fun `tokens are scrubbed from every address form`() {
        assertEquals("http://127.0.0.1:50300/<redacted>/", Redaction.scrub("http://127.0.0.1:50300/HXKQJZK_Rkw=/"))
        assertEquals("http://127.0.0.1:50300/<redacted>", Redaction.scrub("http://127.0.0.1:50300/HXKQJZK_Rkw="))
        assertEquals("ws://[::1]:50300/<redacted>/ws", Redaction.scrub("ws://[::1]:50300/HXKQJZK_Rkw=/ws"))
        assertEquals(
            "http://127.0.0.1:9100?uri=http://127.0.0.1:50300/<redacted>/",
            Redaction.scrub("http://127.0.0.1:9100?uri=http://127.0.0.1:50300/HXKQJZK_Rkw=/"),
        )
        assertEquals(
            "http://127.0.0.1:9100/#/?uri=ws%3A%2F%2F127.0.0.1%3A50300%2F<redacted>%2Fws",
            Redaction.scrub("http://127.0.0.1:9100/#/?uri=ws%3A%2F%2F127.0.0.1%3A50300%2FHXKQJZK_Rkw%3D%2Fws"),
        )
    }

    @Test
    fun `tokens without padding, without a scheme, and encoded in every way DevTools does are scrubbed`() {
        assertEquals("http://127.0.0.1:50300/<redacted>/", Redaction.scrub("http://127.0.0.1:50300/HXKQJZK_Rkw/"))
        assertEquals("http://localhost:50300/<redacted>", Redaction.scrub("http://localhost:50300/HXKQJZK_Rkw"))
        assertEquals(
            "listening on 127.0.0.1:50300/<redacted>/",
            Redaction.scrub("listening on 127.0.0.1:50300/HXKQJZK_Rkw=/"),
        )
        assertEquals("[::1]:50300/<redacted>/ws", Redaction.scrub("[::1]:50300/HXKQJZK_Rkw=/ws"))
        assertEquals(
            "http://[0:0:0:0:0:0:0:1]:50300/<redacted>/",
            Redaction.scrub("http://[0:0:0:0:0:0:0:1]:50300/HXKQJZK_Rkw=/"),
        )
        assertEquals(
            "ws%3A%2F%2F%5B%3A%3A1%5D%3A50300%2F<redacted>%2Fws",
            Redaction.scrub("ws%3A%2F%2F%5B%3A%3A1%5D%3A50300%2FHXKQJZK_Rkw%3D%2Fws"),
        )
        assertEquals(
            "?uri=ws%253A%252F%252F127.0.0.1%253A50300%252F<redacted>%252Fws",
            Redaction.scrub("?uri=ws%253A%252F%252F127.0.0.1%253A50300%252FHXKQJZK_Rkw%253D%252Fws"),
        )
        assertEquals("ws://127.0.0.1:50300/<redacted>/ws", Redaction.scrub("ws://127.0.0.1:50300/HXKQJZK_Rkw%3D/ws"))
        assertEquals("http://0.0.0.0:50300/<redacted>/", Redaction.scrub("http://0.0.0.0:50300/HXKQJZK_Rkw=/"))
    }

    @Test
    fun `a uri parameter the pattern cannot read encoded is read again decoded`() {
        // A token character itself percent-encoded (%4A is J): only the decoded form shows the token.
        val link = "http://127.0.0.1:9100/#/?uri=ws%3A%2F%2F127.0.0.1%3A50300%2FHXKQ%4AZK_Rkw%3D%2Fws"
        val scrubbed = Redaction.scrub(link)
        assertFalse(scrubbed.contains("HXKQ"), scrubbed)
        assertEquals("http://127.0.0.1:9100/#/?uri=ws://127.0.0.1:50300/<redacted>/ws", scrubbed)
    }

    @Test
    fun `paths off loopback, and loopback paths that are not tokens, are left alone`() {
        listOf(
            "https://api.x.com/Zm9v=",
            "https://api.example.com:8443/Zm9vYmFyYmF6=/items",
            "https://pub.dev/packages/vm_service",
            "http://10.0.2.2:8080/AbCdEfGh123=",
            "http://127.0.0.1:9100/inspector?theme=dark",
            "http://127.0.0.1:9100/devtools/",
            "http://127.0.0.1:9100/cpu-profiler",
            "see build/127.0.0.1:80/x",
        ).forEach { text -> assertEquals(text, Redaction.scrub(text)) }
    }

    @Test
    fun `json is scrubbed in a copy, strings at any depth`() {
        val data = JsonParser.parseString(
            """{"details":"at http://127.0.0.1:50300/HXKQJZK_Rkw=/",""" +
                """"nested":[{"uri":"ws://[::1]:1/HXKQJZK_Rkw=/ws"}],"n":3}""",
        )
        val scrubbed = Redaction.scrubJson(data).toString()
        assertFalse(scrubbed.contains("HXKQJZK"), scrubbed)
        assertTrue(data.toString().contains("HXKQJZK"), "the original is left as it was")
        val answer = JsonObject().apply { addProperty("value", "http://127.0.0.1:50300/HXKQJZK_Rkw=/") }
        val redacted = Redaction.scrubExtensionResult("ext.flutter.connectedVmServiceUri", answer)
        assertEquals("<redacted>", redacted.get("value").asString)
    }

    @Test
    fun `tokens inside a sentence are scrubbed and the rest is left alone`() {
        val line = "I flutter : The Dart VM service is listening on http://127.0.0.1:43181/AbCdEfGh123=/ and " +
            "Observatory listening on http://127.0.0.1:38211/oLdToKeN1Ab=/"
        val scrubbed = Redaction.scrub(line)
        assertEquals(
            "I flutter : The Dart VM service is listening on http://127.0.0.1:43181/<redacted>/ and " +
                "Observatory listening on http://127.0.0.1:38211/<redacted>/",
            scrubbed,
        )
        assertEquals("https://pub.dev/packages/vm_service", Redaction.scrub("https://pub.dev/packages/vm_service"))
        assertEquals("no addresses here", Redaction.scrub("no addresses here"))
    }

    @Test
    fun `the connected VM Service address is replaced in its state-changed event`() {
        val event = Redaction.scrubEvent(fixture("event-connectedVmServiceUri.json"))
        assertEquals("<redacted>", event.getAsJsonObject("extensionData").get("value").asString)
        val extension = event.getAsJsonObject("extensionData").get("extension").asString
        assertEquals("ext.flutter.connectedVmServiceUri", extension)
    }

    @Test
    fun `the DevTools server address is replaced in its state-changed event, even with no token in it`() {
        val event = Redaction.scrubEvent(fixture("event-activeDevToolsServerAddress.json"))
        assertEquals("<redacted>", event.getAsJsonObject("extensionData").get("value").asString)
    }

    @Test
    fun `other state changes and other events keep their values`() {
        val structured = fixture("event-connectedVmServiceUri.json").apply {
            getAsJsonObject("extensionData").addProperty("extension", "ext.flutter.inspector.structuredErrors")
            getAsJsonObject("extensionData").addProperty("value", "true")
        }
        assertEquals("true", Redaction.scrubEvent(structured).getAsJsonObject("extensionData").get("value").asString)
        val frame = fixture("event-frame.json")
        assertEquals(fixture("event-frame.json"), Redaction.scrubEvent(frame))
    }

    @Test
    fun `an address anywhere in an event is scrubbed, including stdout's base64 bytes`() {
        val logging = JsonObject().apply {
            addProperty("kind", "Logging")
            add(
                "logRecord",
                JsonObject().apply {
                    add(
                        "message",
                        JsonObject().apply {
                            addProperty("valueAsString", "DDS at http://127.0.0.1:50300/HXKQJZK_Rkw=/")
                        },
                    )
                },
            )
        }
        assertEquals(
            "DDS at http://127.0.0.1:50300/<redacted>/",
            Redaction.scrubEvent(logging).getAsJsonObject("logRecord").getAsJsonObject("message")
                .get("valueAsString").asString,
        )

        val text = "The Dart VM service is listening on http://127.0.0.1:43181/AbCdEfGh123=/\n"
        val stdout = JsonObject().apply {
            addProperty("kind", "WriteEvent")
            addProperty("bytes", Base64.getEncoder().encodeToString(text.toByteArray()))
        }
        val decoded = String(Base64.getDecoder().decode(Redaction.scrubEvent(stdout).get("bytes").asString))
        assertEquals("The Dart VM service is listening on http://127.0.0.1:43181/<redacted>/\n", decoded)
        assertFalse(decoded.contains("AbCdEfGh123"))
    }
}
