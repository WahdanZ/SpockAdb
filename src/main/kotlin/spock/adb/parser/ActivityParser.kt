package spock.adb.parser

/**
 * Extracts the fully qualified class name of the resumed activity from
 * `dumpsys activity activities | grep mResumedActivity` (or `topResumedActivity` on
 * Android 13+, where the former was removed).
 */
object ActivityParser {

    fun parseResumedActivity(output: String): String? =
        output
            .split(" ")
            .find { it.contains("/") }
            ?.replace("/.", ".")
            ?.replace("}", "")
            ?.replace(Regex(".+/"), "")
            ?.takeIf { it.isNotBlank() }
}
