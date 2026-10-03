package spock.adb.flutter.analysis

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import spock.adb.diagnostics.LogProblemExtractor
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
 * The [spock.adb.diagnostics.LikelyProblem] types the Flutter analyzers report, in one place so a
 * report can rank them. HTTP failures and channel handler failures reuse logcat's types: the same
 * failure seen from Dart and from logcat should rank, and read, the same.
 */
object FlutterProblemTypes {
    /** One incident observed in Flutter and the native Android layer in the same short window. */
    const val CROSS_LAYER = "crossLayer"

    /** A `Flutter.Error`: an overflow, an exception in `build()`, a gesture handler that threw. */
    const val FLUTTER_ERROR = "flutterError"

    /** Frame timings: a warning in profile and release builds, a note in debug. */
    const val JANK = "jank"

    /** A widget built in every frame of a sustained run: a hint, a warning only when extreme. */
    const val FREQUENT_REBUILDS = "frequentRebuilds"

    /** A failed `dart:io` request, typed like logcat's HTTP failures. */
    const val NETWORK = LogProblemExtractor.TYPE_NETWORK

    /** A missing plugin, or a platform channel handler that threw (read from logcat). */
    const val FLUTTER_PLUGIN = LogProblemExtractor.TYPE_FLUTTER_PLUGIN
}

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

/**
 * [text] with the query string and fragment cut from every URL in it, and from every route path
 * (`/item/42?ref=x`), as [LogProblemExtractor.stripQuery] cuts them from a request's URL.
 *
 * Free text the app writes — an HTTP error, an error's headline, a route name — carries URLs with
 * whatever their queries hold: session ids, e-mail addresses, signed tokens. The host and path are
 * what a developer needs. Text with no URL in it is returned as it is, a lone `?` included.
 *
 * A query runs to whitespace or a quote: brackets (`ids[]=1`, `filter[tag]=x`) and parentheses
 * are part of it. A `)` it ends with that it never opened is given back, so the route in
 * `MaterialPageRoute<dynamic>(/item/42?ref=x)` keeps its closing parenthesis.
 */
internal fun stripUrlQueries(text: String): String {
    if ('?' !in text && '#' !in text) return text
    val out = StringBuilder(text.length)
    var from = 0
    for (match in URL_QUERY_START.findAll(text)) {
        if (match.range.first < from) continue
        out.append(text, from, match.range.first).append(match.groupValues[1])
        var end = match.range.last + 1
        while (end < text.length && !text[end].isWhitespace() && text[end] !in QUOTES) end++
        val query = text.substring(match.range.last, end)
        var unbalanced = query.count { it == ')' } - query.count { it == '(' }
        while (unbalanced > 0 && text[end - 1] == ')') {
            end--
            unbalanced--
        }
        from = end
    }
    return out.append(text, from, text.length).toString()
}

private const val QUOTES = "'\"`"

/**
 * Up to and including the `?` or `#` that starts a query or fragment, with something after it: a
 * URL with a scheme; a host with a dot and no scheme (`api.example.com/me`); or a path starting
 * with `/` that is not inside a longer word. The part kept ends at whitespace, quotes, brackets
 * and parentheses, which wrap URLs in prose. Angle brackets do not end it: a token already
 * redacted reads `<redacted>`, inside the URL it came from.
 */
private val URL_QUERY_START = Regex(
    """((?:\b[A-Za-z][A-Za-z0-9+.\-]*://|(?<![\w/.:\-@])(?:[A-Za-z0-9\-]+\.)+[A-Za-z]{2,}(?::\d{1,5})?(?=[/?#])""" +
        """|(?<![\w/.:\-])/)[^\s?#'"`()\[\]]*)[?#](?=[^\s'"`])""",
)
