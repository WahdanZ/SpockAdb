package spock.adb.flutter.analysis

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The VM Service payloads the lead captured from `sample/flutter_app` (Flutter 3.22.2, debug,
 * Android 14 emulator), tokens scrubbed. Event files are arrays of `{timestamp, isolate, data}`:
 * the `Extension` event's timestamp, isolate id and `extensionData`.
 */
object FlutterFixtures {

    private const val DIR = "/flutter/vm-3.22.2/"

    fun json(name: String): JsonObject = JsonParser.parseString(text(name)).asJsonObject

    fun events(kind: String, history: Boolean = false): List<FlutterExtensionEvent> =
        (JsonParser.parseString(text("event.$kind.json")) as JsonArray).map { element ->
            val event = element.asJsonObject
            FlutterExtensionEvent(
                kind = kind,
                timestampMs = event.get("timestamp").asLong,
                isolateId = event.get("isolate").asString,
                data = event.getAsJsonObject("data"),
                history = history,
            )
        }

    fun event(kind: String, timestampMs: Long, data: String, history: Boolean = false) =
        FlutterExtensionEvent(kind, timestampMs, "isolates/1", JsonParser.parseString(data).asJsonObject, history)

    private fun text(name: String): String =
        requireNotNull(FlutterFixtures::class.java.getResource(DIR + name)) { "missing fixture $name" }.readText()
}
