package spock.adb.flutter.analysis

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Lenient readers for payloads the app writes. A field of the wrong type reads as absent rather
 * than throwing: a Flutter version that changes one field must cost that field, not the report.
 */
internal object FlutterJson {

    fun string(obj: JsonObject?, key: String): String? {
        val value = obj?.get(key) as? JsonPrimitive ?: return null
        return if (value.isString) value.asString else null
    }

    fun long(obj: JsonObject?, key: String): Long? = number(obj?.get(key))?.toLong()

    fun double(obj: JsonObject?, key: String): Double? = number(obj?.get(key))?.toDouble()

    fun obj(obj: JsonObject?, key: String): JsonObject? = obj?.get(key) as? JsonObject

    fun array(obj: JsonObject?, key: String): JsonArray? = obj?.get(key) as? JsonArray

    /** Integers in [array]; an entry that is not a number reads as null so positions are kept. */
    fun longs(array: JsonArray?): List<Long?> = array?.map { number(it)?.toLong() }.orEmpty()

    fun strings(array: JsonArray?): List<String?> =
        array?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.asString }.orEmpty()

    private fun number(element: JsonElement?): Number? {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isNumber) return null
        return try {
            primitive.asNumber.takeIf { it.toDouble().isFinite() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/** Where the problems these analyzers find are reported, in the Diagnose report. */
const val FLUTTER_SECTION = "flutter"

/**
 * `file:///…/my_app/lib/screens/home.dart` → `lib/screens/home.dart`: the part a developer
 * recognises, without the host path. Package URIs and paths with no `lib/` keep their file name.
 */
internal fun shortSourcePath(file: String): String {
    val path = file.removePrefix("file://")
    val lib = path.lastIndexOf("/lib/")
    return when {
        file.startsWith("package:") -> file
        lib >= 0 -> path.substring(lib + 1)
        else -> path.substringAfterLast('/')
    }
}

/**
 * Epoch milliseconds as logcat's `threadtime` prints them (`10-01 21:51:35.144`), so a Flutter
 * problem's `lastSeen` reads like a log problem's. [zone] should be the device's.
 */
internal fun logcatTime(epochMs: Long, zone: ZoneId): String =
    LOGCAT_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

private val LOGCAT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
