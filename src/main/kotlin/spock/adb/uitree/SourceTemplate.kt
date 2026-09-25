package spock.adb.uitree

/**
 * Matching what the device shows against source that only produces it at run time: a Kotlin string
 * template such as `"form_${form}_button"`, or a `strings.xml` value with format arguments such as
 * `"Tapped %1$d times"`.
 *
 * The device reports the rendered value, `form_a_button`, which no literal in the project equals. A
 * template's fixed fragments are still in the source, though, so it is turned into a pattern: the
 * fragments must appear as written, and each `$name`, `${expr}` or `%d` stands for at least one
 * character. A template with no letter or digit of its own — `"$label $it"` — would match nearly
 * anything, so it is not a pattern at all.
 */
internal object SourceTemplate {

    /** How many of a value's words are looked up in the index: see [searchWords]. */
    const val MAX_SEARCH_WORDS = 3

    /**
     * The pattern [source] — a Kotlin string literal, quotes included — renders to, or null when it
     * is not a template (a plain literal is compared whole instead), is malformed, or has no fixed
     * letter or digit.
     */
    fun pattern(source: String): Regex? {
        val raw = source.length >= RAW_QUOTES * 2 && source.startsWith(TRIPLE_QUOTE) && source.endsWith(TRIPLE_QUOTE)
        val quoted = !raw && source.length >= 2 && source.startsWith('"') && source.endsWith('"')
        val body = when {
            raw -> source.substring(RAW_QUOTES, source.length - RAW_QUOTES)
            quoted -> source.substring(1, source.length - 1)
            else -> return null
        }
        return parts(body, raw)?.let(::toRegex)
    }

    /** [source] without its quotes, as the Source line names the template it matched through. */
    fun body(source: String): String = when {
        source.length >= RAW_QUOTES * 2 && source.startsWith(TRIPLE_QUOTE) && source.endsWith(TRIPLE_QUOTE) ->
            source.substring(RAW_QUOTES, source.length - RAW_QUOTES)
        source.length >= 2 && source.startsWith('"') && source.endsWith('"') -> source.substring(1, source.length - 1)
        else -> source
    }

    /**
     * The pattern a `strings.xml` value — already read by [SourceMatching.androidStringValue] —
     * renders to through `getString(id, args)`: `%s`, `%1$d`, `%.2f` stand for a value, `%%` is a
     * percent sign and `%n` a line break. Null when it has no format argument, or no fixed letter or digit.
     */
    fun formatPattern(value: String): Regex? {
        val parts = ArrayList<String?>()
        var from = 0
        FORMAT_SPECIFIER.findAll(value).forEach { specifier ->
            parts += value.substring(from, specifier.range.first)
            parts += when (specifier.value) {
                "%%" -> "%"
                "%n" -> "\n"
                else -> null
            }
            from = specifier.range.last + 1
        }
        parts += value.substring(from)
        return toRegex(parts)
    }

    /**
     * The words to look up in the IDE's word index, best first: distinct runs of identifier
     * characters, those with a letter before bare numbers, longest first, at most [MAX_SEARCH_WORDS].
     * More than one, because the longest word of a rendered value may be the part a template filled
     * in — "Last tap: nothing yet" from `"Last tap: $event"` — which is nowhere in the source.
     */
    fun searchWords(value: String): List<String> =
        WORD.findAll(value).map { it.value }.distinct()
            .sortedWith(
                compareByDescending<String> { word -> word.any { it.isLetter() } }.thenByDescending { it.length },
            )
            .take(MAX_SEARCH_WORDS)
            .toList()

    /**
     * [after] is the source text right after the name `testTag`; the index in it of the opening
     * quote of a string literal passed as its argument — `("x")`, `(tag = "x")` — or null when the
     * argument is anything else: a variable, an expression, or a declaration's parameter list.
     */
    fun tagArgumentOffset(after: CharSequence): Int? =
        TAG_ARGUMENT.find(after)?.let { it.range.last }?.takeIf { after[it] == '"' }

    /** Fixed fragments and holes (null) to one anchored pattern, if there is a hole and a fixed letter or digit. */
    private fun toRegex(parts: List<String?>): Regex? {
        if (parts.none { it == null }) return null
        if (parts.filterNotNull().none { part -> part.any { it.isLetterOrDigit() } }) return null
        return Regex(parts.joinToString("") { it?.let(Regex::escape) ?: HOLE }, RegexOption.DOT_MATCHES_ALL)
    }

    /**
     * A template's body as fixed text and holes (null), escapes decoded unless it is [raw]. Null
     * when a `${` is never closed. `${'$'}`, the way to write a dollar sign in a raw string, is text.
     */
    private fun parts(body: String, raw: Boolean): List<String?>? {
        val parts = ArrayList<String?>()
        val text = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            i = when {
                c == '\\' && !raw && i + 1 < body.length -> escape(body, i, text)
                c == '$' -> template(body, i, text, parts) ?: return null
                else -> {
                    text.append(c)
                    i + 1
                }
            }
        }
        parts += text.toString()
        return parts
    }

    /**
     * The `$` at [start]: a hole in [parts] for `$name` or `${expr}`, ending the fixed [text] before
     * it, or a dollar sign in [text] when it opens neither. The index after it; null for a `${`
     * that is never closed.
     */
    private fun template(body: String, start: Int, text: StringBuilder, parts: MutableList<String?>): Int? {
        val next = body.getOrNull(start + 1)
        val end = when {
            next == '{' -> expressionEnd(body, start + 2) ?: return null
            next != null && (next.isLetter() || next == '_') -> identifierEnd(body, start + 1)
            else -> start
        }
        val dollar = end == start || (next == '{' && body.substring(start + 2, end - 1).trim() == "'$'")
        if (dollar) {
            text.append('$')
        } else {
            parts += text.toString()
            text.clear()
            parts += null
        }
        return if (end == start) start + 1 else end
    }

    private fun identifierEnd(body: String, from: Int): Int {
        var i = from
        while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_')) i++
        return i
    }

    /** Decodes the escape at [start] into [into]; the index after it. */
    private fun escape(body: String, start: Int, into: StringBuilder): Int {
        val next = body[start + 1]
        val code = if (next == 'u' && start + UNICODE_ESCAPE_LENGTH <= body.length) {
            body.substring(start + 2, start + UNICODE_ESCAPE_LENGTH).toIntOrNull(HEX)
        } else {
            null
        }
        return if (code != null) {
            into.append(code.toChar())
            start + UNICODE_ESCAPE_LENGTH
        } else {
            into.append(ESCAPES[next] ?: next)
            start + 2
        }
    }

    /**
     * The index just past the `}` that closes a `${` whose expression starts at [from], or null.
     * Braces nest — `${items.map { it.name }}` — and strings and characters inside the expression,
     * which may hold braces of their own, are skipped whole.
     */
    private fun expressionEnd(code: String, from: Int): Int? {
        var depth = 1
        var i = from
        while (i < code.length) {
            i = when (code[i]) {
                '{' -> {
                    depth++
                    i + 1
                }
                '}' -> {
                    if (--depth == 0) return i + 1
                    i + 1
                }
                '"' -> stringEnd(code, i) ?: return null
                '\'' -> charEnd(code, i) ?: return null
                else -> i + 1
            }
        }
        return null
    }

    /** The index just past the string literal that opens at [start] inside an expression, or null. */
    private fun stringEnd(code: String, start: Int): Int? {
        if (code.startsWith(TRIPLE_QUOTE, start)) {
            val close = code.indexOf(TRIPLE_QUOTE, start + RAW_QUOTES)
            return if (close < 0) null else close + RAW_QUOTES
        }
        var i = start + 1
        while (i < code.length) {
            when {
                code[i] == '\\' -> i += 2
                code[i] == '"' -> return i + 1
                code[i] == '$' && code.getOrNull(i + 1) == '{' -> i = expressionEnd(code, i + 2) ?: return null
                else -> i++
            }
        }
        return null
    }

    /** The index just past the character literal that opens at [start], or null. */
    private fun charEnd(code: String, start: Int): Int? {
        var i = start + 1
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i += 2
                '\'' -> return i + 1
                else -> i++
            }
        }
        return null
    }

    private const val HOLE = ".+"
    private const val RAW_QUOTES = 3
    private const val TRIPLE_QUOTE = "\"\"\""
    private const val HEX = 16
    private const val UNICODE_ESCAPE_LENGTH = 6
    private val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b')
    private val WORD = Regex("""[\p{L}\p{N}_]+""")
    private val TAG_ARGUMENT = Regex("""^\s*\(\s*(?:tag\s*=\s*)?.""")

    /** `java.util.Formatter`'s syntax, as `getString(id, args)` reads it; `%%` and `%n` included. */
    private val FORMAT_SPECIFIER = Regex("""%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[a-zA-Z%]""")
}
