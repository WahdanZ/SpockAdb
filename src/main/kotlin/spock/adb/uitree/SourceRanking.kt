package spock.adb.uitree

/**
 * What the Inspector can look for in the project's sources to find where a captured element came
 * from, and how the places it finds are ordered.
 *
 * A `uiautomator` dump carries no source locations — Android Studio's Layout Inspector gets them
 * from an agent on the device, and this plugin has none. So "go to source" is a search for the
 * element's identifiers, and everything here says which identifier matched, so a guess is never
 * presented as a mapping. The search itself is [SourceLocator]; this file is the part that needs no
 * IDE, so it can be tested on its own.
 */
internal data class SourceQuery(
    /** A Compose test tag, exposed through `testTagsAsResourceId`. */
    val testTag: String? = null,
    /** The name in a `pkg:id/<name>` id. */
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    /** A View class the app declares itself: not the framework's, nor a library's. */
    val customClass: String? = null,
) {

    /** The searches to run, in order; the first to find anything is the answer. */
    val steps: List<SourceStep>
        get() = listOfNotNull(
            testTag?.let { SourceStep(SourceTier.TEST_TAG, it) },
            viewId?.let { SourceStep(SourceTier.VIEW_ID, it) },
            text?.let { SourceStep(SourceTier.TEXT, it) },
            contentDescription?.let { SourceStep(SourceTier.CONTENT_DESCRIPTION, it) },
            customClass?.let { SourceStep(SourceTier.CLASS, it) },
        )

    val isEmpty: Boolean get() = steps.isEmpty()

    companion object {
        /**
         * A `pkg:id/name` id is a View id on a Views screen. Anywhere Compose is present it may be a
         * tag too — captures have reported exposed tags in that shape as well as bare — so both are
         * tried, the tag first. A bare id is only ever a tag.
         */
        fun of(node: UiNode, framework: UiFramework): SourceQuery {
            val id = ResourceIdRef.parse(node.resourceId)
            val viewId = (id as? ResourceIdRef.ViewId)?.name
            return SourceQuery(
                testTag = (id as? ResourceIdRef.Tag)?.tag ?: viewId?.takeIf { framework != UiFramework.VIEWS },
                viewId = viewId,
                text = node.text.takeIf { it.isNotBlank() },
                contentDescription = node.contentDescription.takeIf { it.isNotBlank() },
                customClass = SourceMatching.customClassName(node.className),
            )
        }
    }
}

internal data class SourceStep(val tier: SourceTier, val value: String)

/**
 * Which identifier found the source. [label] finishes "found by …"; [guess] is true where the
 * same value can come from many places, so the match may be one of several.
 */
internal enum class SourceTier(val label: String, val noun: String, val guess: Boolean) {
    TEST_TAG("test tag", "tag", false),
    VIEW_ID("resource id", "id", false),
    TEXT("text", "text", true),
    CONTENT_DESCRIPTION("content description", "description", true),

    /** Text or a description found in `strings.xml`, then where that string is used. */
    STRING_RESOURCE("text in strings.xml", "string", true),
    CLASS("class", "class", false),
}

/** A resource id as `uiautomator` reports it. */
internal sealed class ResourceIdRef {
    /** `pkg:id/name` — a View id, or a Compose tag reported in the same shape. */
    data class ViewId(val packageName: String, val name: String) : ResourceIdRef()

    /** No `:id/`: a Compose test tag published whole by `testTagsAsResourceId`. */
    data class Tag(val tag: String) : ResourceIdRef()

    companion object {
        private const val ID_MARKER = ":id/"

        /** Null for no id, and for the framework's own (`android:id/content`), which no app declares. */
        fun parse(resourceId: String): ResourceIdRef? {
            if (resourceId.isBlank()) return null
            val marker = resourceId.indexOf(ID_MARKER)
            if (marker < 0) return Tag(resourceId)
            val packageName = resourceId.substring(0, marker)
            val name = resourceId.substring(marker + ID_MARKER.length)
            return if (packageName == "android" || name.isBlank()) null else ViewId(packageName, name)
        }
    }
}

/** One place in the project that matched, with what ranking needs to know about it. */
internal data class SourceHit(
    /** The file's VirtualFile URL, to open it again on the EDT. */
    val url: String,
    val fileName: String,
    val offset: Int,
    /** 1-based, as an editor shows it. */
    val line: Int,
    val snippet: String,
    /** Inside `testTag(...)` or on `android:id`: the element's own declaration, not a mention. */
    val exactContext: Boolean = false,
    val module: String? = null,
    val packageName: String? = null,
    /** How many of the rest of the capture's tags and texts this file quotes: see [SourceRanking.screenAffinity]. */
    val screenAffinity: Int = 0,
) {
    /** How the candidates popup lists it: `file:line — snippet`. */
    val presentation: String get() = "$fileName:$line — ${snippet.clipped()}"

    private fun String.clipped(): String = if (length <= SNIPPET_MAX) this else take(SNIPPET_MAX - 1) + "…"

    private companion object {
        const val SNIPPET_MAX = 90
    }
}

/** The outcome of one search: which tier matched, and its places, best first. */
internal data class SourceResult(
    val query: SourceQuery,
    val tier: SourceTier?,
    val hits: List<SourceHit>,
) {
    val best: SourceHit? get() = hits.firstOrNull()
}

internal object SourceRanking {

    /**
     * Best first: the element's own declaration (a `testTag(...)` argument, an `android:id`) over a
     * mere mention; then a place in the module most of the other matches are in; then a file whose
     * package shares more of the captured window's package; then a file that builds more of the
     * rest of the captured screen. Ties keep a stable file-then-offset order.
     */
    fun rank(hits: List<SourceHit>, windowPackage: String?): List<SourceHit> {
        val distinct = hits.distinctBy { it.url to it.offset }
        val perModule = distinct.mapNotNull { it.module }.groupingBy { it }.eachCount()
        return distinct.sortedWith(
            compareByDescending<SourceHit> { it.exactContext }
                .thenByDescending { hit -> hit.module?.let { perModule[it] } ?: 0 }
                .thenByDescending { packageAffinity(it.packageName, windowPackage) }
                .thenByDescending { it.screenAffinity }
                .thenBy { it.url }
                .thenBy { it.offset },
        )
    }

    /**
     * How many leading package segments the two share. The window's package is the application
     * id, which is often, but not always, the code's package: `com.app` owns `com.app.ui.Home`,
     * and a `.debug` suffix still shares everything before it.
     */
    fun packageAffinity(filePackage: String?, windowPackage: String?): Int {
        if (filePackage.isNullOrBlank() || windowPackage.isNullOrBlank()) return 0
        return filePackage.split('.').zip(windowPackage.split('.')).takeWhile { (a, b) -> a == b }.size
    }

    /**
     * The tags and texts of every node in [tree] but [node], which tell apart two files that both
     * hold [node]'s text: the one that also holds the rest of the screen is the one that drew it.
     * Short values are left out — "OK" is in every file — and so is anything past [MAX_SCREEN_VALUES].
     */
    fun screenValues(tree: UiTree?, node: UiNode): Set<String> =
        tree?.nodes().orEmpty()
            .filter { it !== node }
            .flatMap { sequenceOf(it.testTag, it.text, it.contentDescription) }
            .filterNotNull()
            .filter { it.length >= MIN_SCREEN_VALUE }
            .take(MAX_SCREEN_VALUES)
            .toSet()

    /** How many of [values] [fileText] quotes whole, as a string literal or an XML value. */
    fun screenAffinity(fileText: CharSequence, values: Set<String>): Int =
        values.count { fileText.contains("\"$it\"") || fileText.contains(">$it<") }

    private const val MIN_SCREEN_VALUE = 3
    private const val MAX_SCREEN_VALUES = 60
}

/** Recognising a match in source text: literals, call sites and resource references. */
internal object SourceMatching {

    private val FRAMEWORK_PREFIXES = listOf("android.", "androidx.", "com.android.", "com.google.android.")

    private val TEST_TAG_CALL = Regex("""\btestTag\s*\(\s*(?:tag\s*=\s*)?$""")

    /**
     * A class the app could have declared: [className] as the device reports it, with `$` for a
     * nested class. Null for framework and Jetpack classes — Compose reports every node as one —
     * and for a class name with no package, which cannot be looked up.
     */
    fun customClassName(className: String): String? {
        if (className.isBlank() || '.' !in className) return null
        if (FRAMEWORK_PREFIXES.any { className.startsWith(it) }) return null
        return className.replace('$', '.')
    }

    /**
     * The word to look up in the IDE's word index: its longest run of identifier characters, since
     * the index holds words, not phrases. Null when there is none, as for "→".
     */
    fun searchWord(value: String): String? =
        Regex("""[\p{L}\p{N}_]+""").findAll(value).map { it.value }.maxByOrNull { it.length }

    /** [before] is the source text leading up to a string literal; true when it opens `testTag(`. */
    fun isTestTagArgument(before: CharSequence): Boolean = TEST_TAG_CALL.containsMatchIn(before)

    /**
     * [before] is the source text leading up to an identifier; true when it reads `R.<type>.` — the
     * app's R, qualified or not, and not `android.R`.
     */
    fun isResourceReference(before: CharSequence, type: String): Boolean {
        val match = Regex("""(?:^|[^\w.])((?:\w+\s*\.\s*)*)R\s*\.\s*$type\s*\.\s*$""").find(before) ?: return false
        return match.groupValues[1].replace(Regex("""\s"""), "") != "android."
    }

    /** The id an `android:id` value declares or names: `@+id/name` or `@id/name`. */
    fun declaredIdName(attributeValue: String): String? =
        Regex("""^@\+?id/([\w.]+)$""").find(attributeValue.trim())?.groupValues?.get(1)

    /**
     * The value of a string literal, from its source text including the quotes. Null for anything
     * that is not one whole constant: a Kotlin template with `$name` or `${…}` in it is only known
     * at run time, so it cannot equal what the device shows.
     */
    fun literalValue(source: String, kotlin: Boolean): String? {
        val raw = source.length >= RAW_QUOTES * 2 && source.startsWith(TRIPLE_QUOTE) && source.endsWith(TRIPLE_QUOTE)
        val quoted = source.length >= 2 && source.startsWith('"') && source.endsWith('"')
        return when {
            // A Kotlin raw string has no escapes; a Java text block strips indentation, which is not worth copying.
            raw -> source.substring(RAW_QUOTES, source.length - RAW_QUOTES).takeIf { kotlin && !hasTemplate(it) }
            quoted -> source.substring(1, source.length - 1).takeUnless { kotlin && hasTemplate(it) }?.let(::unescape)
            else -> null
        }
    }

    /**
     * A `strings.xml` value as the app shows it: markup and CDATA markers dropped, entities
     * decoded, whitespace collapsed unless the value is quoted, and Android's backslash escapes
     * applied.
     */
    fun androidStringValue(raw: String): String {
        // CDATA is taken as written; around it, markup goes and entities are decoded.
        val text = buildString {
            var from = 0
            CDATA.findAll(raw).forEach { section ->
                append(decodeEntities(raw.substring(from, section.range.first).replace(MARKUP, "")))
                append(section.groupValues[1])
                from = section.range.last + 1
            }
            append(decodeEntities(raw.substring(from).replace(MARKUP, "")))
        }
        val trimmed = text.trim()
        val quoted = trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
        val body = if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed.replace(Regex("""\s+"""), " ")
        return unescape(body)
    }

    /** An unescaped `$` followed by a name or `{`: Kotlin fills it in at run time. */
    private fun hasTemplate(body: String): Boolean {
        var i = 0
        while (i < body.length - 1) {
            when {
                body[i] == '\\' -> i++
                body[i] == '$' && (body[i + 1] == '{' || Character.isJavaIdentifierStart(body[i + 1])) -> return true
            }
            i++
        }
        return false
    }

    private fun unescape(body: String): String = buildString {
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i == body.length - 1) {
                append(c)
                i++
                continue
            }
            val next = body[i + 1]
            val unicode = next == 'u' && i + UNICODE_ESCAPE_LENGTH <= body.length
            val code = if (unicode) body.substring(i + 2, i + UNICODE_ESCAPE_LENGTH).toIntOrNull(HEX) else null
            if (code != null) {
                append(code.toChar())
                i += UNICODE_ESCAPE_LENGTH
            } else {
                append(ESCAPES[next] ?: next)
                i += 2
            }
        }
    }

    private fun decodeEntities(text: String): String =
        Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""").replace(text) { match ->
            when (val name = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (name.startsWith("#x")) name.drop(2).toIntOrNull(HEX) else name.drop(1).toIntOrNull()
                    code?.let { String(Character.toChars(it)) } ?: match.value
                }
            }
        }

    private val CDATA = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
    private val MARKUP = Regex("<[^>]*>")
    private const val RAW_QUOTES = 3
    private const val TRIPLE_QUOTE = "\"\"\""
    private const val HEX = 16
    private const val UNICODE_ESCAPE_LENGTH = 6
    private val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b')
}

/** What the Inspector says about the search, in the details pane and the status line. */
internal object SourceStatus {

    const val SEARCHING = "Searching the project…"
    const val INDEXING = "Available after indexing finishes"
    const val NOTHING_TO_SEARCH = "Nothing to search for: no test tag, id, text, description or app class."

    /** Said wherever a match is shown: this is a search, not a mapping. */
    const val HOW_FOUND = "Found by searching this project for the element's identifiers. A UI capture " +
        "carries no source locations, so this is the best match, not a certain one."

    /** `File.kt:12 · found by test tag`, saying so when it may be one of several. */
    fun found(result: SourceResult): String? {
        val best = result.best ?: return null
        val tier = result.tier ?: return null
        val where = "${best.fileName}:${best.line} · found by ${tier.label}"
        return when {
            result.hits.size > 1 -> "$where · best of ${result.hits.size}"
            tier.guess -> "$where — may be one of several"
            else -> where
        }
    }

    /** "No source found for tag 'x', text 'y' in this project", naming everything that was tried. */
    fun notFound(query: SourceQuery): String {
        val searched = query.steps.joinToString(", ") { "${it.tier.noun} '${it.value.shortened()}'" }
        return "No source found for $searched in this project."
    }

    private fun String.shortened(): String =
        replace('\n', ' ').let { if (it.length <= VALUE_MAX) it else it.take(VALUE_MAX - 1) + "…" }

    private const val VALUE_MAX = 40
}
