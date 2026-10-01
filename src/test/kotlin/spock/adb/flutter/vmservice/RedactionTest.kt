package spock.adb.flutter.vmservice

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
