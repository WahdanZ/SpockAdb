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

    /**
     * The package of the resumed activity — the part of its component before the `/`, e.g.
     * `com.example` from `com.example/.MainActivity`. Not derivable from [parseResumedActivity],
     * whose class name may sit in a different package from the app.
     */
    fun parseResumedPackage(output: String): String? =
        output
            .split(" ")
            .find { it.contains("/") }
            ?.substringBefore("/")
            ?.takeIf { it.isNotBlank() }
}
