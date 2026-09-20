package spock.adb.logcat

/**
 * Replaces things that look like credentials before log text leaves the machine.
 *
 * Only ever applied to text on its way to a model or to the clipboard "for AI" — never to the
 * panel itself. Redacting the view would be worse than useless: the developer would lose the
 * token they are debugging, and would have no way to tell a redaction from an app that logged
 * an empty header.
 *
 * Conservative on purpose. A false positive costs the model one value it probably did not need;
 * a false negative puts a live session token in a third party's request log, which cannot be
 * undone. Every replacement is counted so the generated context can say it happened.
 */
object LogcatRedactor {

    const val PLACEHOLDER = "<redacted>"

    data class Result(val text: String, val count: Int)

    /**
     * Whole-value rules, most specific first.
     *
     * Header rules take the rest of the line because header values are not reliably delimited
     * in a log line — `Authorization: Bearer a.b.c; path=/` is one value followed by another —
     * and guessing the end of the value is how a token ends up half-redacted.
     */
    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token)(\s*[:=]\s*).+""")
            to "$1$2$PLACEHOLDER",
        Regex("""(?i)\bbearer\s+[A-Za-z0-9\-._~+/=]{8,}""") to "Bearer $PLACEHOLDER",
        // A JWT is recognisable on its own, wherever it was logged and whatever it was called.
        Regex("""\beyJ[A-Za-z0-9_\-]{4,}\.[A-Za-z0-9_\-]{4,}\.[A-Za-z0-9_\-]{4,}""") to PLACEHOLDER,
        Regex(
            """(?i)\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|""" +
                """client[_-]?secret|secret|password|passwd|pwd|session[_-]?id|sessionid|""" +
                """auth[_-]?token|token)("?\s*[:=]\s*"?)([^\s"',;}&]{4,})""",
        ) to "$1$2$PLACEHOLDER",
        // user:password@host in a URL.
        Regex("""://[^/\s:@]+:[^/\s@]+@""") to "://$PLACEHOLDER@",
    )

    fun redact(text: String): Result {
        var current = text
        var count = 0
        RULES.forEach { (pattern, replacement) ->
            count += pattern.findAll(current).count()
            current = pattern.replace(current, replacement)
        }
        return Result(current, count)
    }
}
