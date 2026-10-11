package spock.adb.flutter.vmservice

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Reads the shapes service extensions answer with. */
object ExtensionResults {

    /**
     * A bool an extension reported, or null when [key] is absent or not a bool.
     *
     * The two SDKs disagree on the type: dart:io's `httpEnableTimelineLogging` answers
     * `"enabled": false`, a JSON boolean, while Flutter's bool extensions (`structuredErrors`,
     * `debugPaint`, …) answer `"enabled": "true"`, a string.
     */
    fun bool(result: JsonObject, key: String = "enabled"): Boolean? {
        val value = result.get(key) as? JsonPrimitive ?: return null
        return when {
            value.isBoolean -> value.asBoolean
            value.isString && value.asString.equals("true", ignoreCase = true) -> true
            value.isString && value.asString.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }
}
