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
 *
 * The value comes from the app being inspected, so matching is a linear scan ([Pattern]), not a
 * regular expression: `"id_${y}${m}${d}"` as `.+.+.+` backtracks for seconds on a long value that
 * does not match, inside a read action that typing waits for.
 */
internal object SourceTemplate {

    /** How many of a value's words are looked up in the index: see [searchWords]. */
    const val MAX_SEARCH_WORDS = 3

    /** Longer than any label a template renders; a value past it is compared whole, never matched as a pattern. */
    const val MAX_MATCHED_LENGTH = 1000

    /**
     * What a template renders to: [fixed] text in order, with at least [gaps]`[i]` characters of
     * anything — line breaks included — between `fixed[i]` and `fixed[i + 1]`. Adjacent holes are
     * one gap, each hole being at least one character, so there is never more than one way to
     * place a gap and matching needs no backtracking.
     */
    class Pattern internal constructor(private val fixed: List<String>, private val gaps: List<Int>) {

        /**
         * Whether [value] is what this renders to. The first fixed text must open it and the last
         * close it; each one between is taken at its leftmost place after the gap before it. That
         * is never wrong: a later place only leaves less room for the rest.
         */
        fun matches(value: String): Boolean {
            if (value.length > MAX_MATCHED_LENGTH) return false
            val prefix = fixed.first()
            val suffix = fixed.last()
            if (!value.startsWith(prefix) || !value.endsWith(suffix)) return false
            val end = value.length - suffix.length
            var at = prefix.length
            for (i in 1 until fixed.size - 1) {
                val found = value.indexOf(fixed[i], at + gaps[i - 1])
                if (found < 0) return false
                at = found + fixed[i].length
            }
            return at + gaps.last() <= end
        }

        internal companion object {
            /**
             * Fixed fragments and holes (null) to one anchored pattern, if there is a hole and a fixed
             * letter or digit. Fragments with no hole between them are joined, and a run of holes is one gap.
             */
            fun of(parts: List<String?>): Pattern? {
                if (parts.none { it == null }) return null
                if (parts.filterNotNull().none { part -> part.any { it.isLetterOrDigit() } }) return null
                val fixed = mutableListOf(StringBuilder())
                val gaps = ArrayList<Int>()
                var holes = 0
                parts.forEach { part ->
                    when {
                        part == null -> holes++
                        holes == 0 -> fixed.last().append(part)
                        part.isNotEmpty() -> {
                            gaps += holes
                            holes = 0
                            fixed += StringBuilder(part)
                        }
                    }
                }
                if (holes > 0) {
                    gaps += holes
                    fixed += StringBuilder()
                }
                return Pattern(fixed.map { it.toString() }, gaps)
            }
        }
    }

    /**
     * The pattern [source] — a Kotlin string literal, quotes included — renders to, or null when it
     * is not a template (a plain literal is compared whole instead), is malformed, or has no fixed
     * letter or digit.
     */
    fun pattern(source: String): Pattern? = parts(source)?.let(Pattern::of)

    /**
     * The value of [source] — a Kotlin string literal, quotes included — when it has no template in
     * it: escapes decoded, and `${'$'}` a dollar sign. Null for a template, whose value is only known
     * at run time, for a malformed one, and for anything that is not a string literal.
     */
    fun constant(source: String): String? = parts(source)?.singleOrNull()

    /** [source]'s fixed text and holes, when it is a string literal: see [parts]. */
    private fun parts(source: String): List<String?>? {
        val raw = source.length >= RAW_QUOTES * 2 && source.startsWith(TRIPLE_QUOTE) && source.endsWith(TRIPLE_QUOTE)
        val quoted = !raw && source.length >= 2 && source.startsWith('"') && source.endsWith('"')
        val body = when {
            raw -> source.substring(RAW_QUOTES, source.length - RAW_QUOTES)
            quoted -> source.substring(1, source.length - 1)
            else -> return null
        }
        return parts(body, raw)
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
    fun formatPattern(value: String): Pattern? {
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
        return Pattern.of(parts)
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
            // `$name`: the name runs to the first character that cannot be part of it.
            next != null && (next.isLetter() || next == '_') -> (start + 1 until body.length)
                .firstOrNull { !body[it].isLetterOrDigit() && body[it] != '_' } ?: body.length
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
