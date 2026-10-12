package spock.adb.mcp.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import spock.adb.storage.PrefItem
import spock.adb.storage.PrefType
import spock.adb.storage.StorageFile
import spock.adb.storage.isEncryptedPreferences

/**
 * How `android_read_app_storage` shows a file's entries to an agent: `key (type) = value`, one per
 * line, with every value bounded and secret-looking ones held back.
 *
 * Each of the three answers something the review of the 4.1.0 MCP server measured (#182):
 * - a value is cut at [maxValueChars], because one cached JSON string made a Flutter prefs file
 *   40 116 characters, every one of them paid for by the agent;
 * - [keyFilter] reads only the entries asked about, which is how a long value is read whole;
 * - a value that looks secret is redacted, since these files hold push and auth tokens and the
 *   tool runs without asking. Best-effort, by key name and by value shape (see [secret]), so it
 *   both misses secrets nothing marks and holds back values that only look like one — the second
 *   is cheap to undo with `showSecrets`, the first is not. Booleans, ints and longs are shown
 *   anyway: a `session_active` flag or a `session_count` is no credential.
 *
 * Control characters are escaped, as in the edit tools' confirmations, so a value with newlines
 * cannot pass itself off as further entries.
 */
internal class StorageView(
    val maxValueChars: Int = DEFAULT_MAX_VALUE_CHARS,
    val keyFilter: String? = null,
    val showSecrets: Boolean = false,
) {

    /** What became of [showSecrets]: whether the developer was asked, and what they said. */
    enum class Reveal { NOT_ASKED, ALLOWED, DECLINED }

    /** The keys of the shown entries that would be redacted, which is what the developer is asked about. */
    fun secretKeys(items: List<PrefItem>): List<String> =
        items.filter { matches(it) && secret(it) != null }.map { it.key }

    fun describe(file: StorageFile, items: List<PrefItem>, reveal: Reveal = Reveal.NOT_ASKED): String {
        // Each value is judged once: telling whether one holds a secret can mean parsing it as JSON.
        val shown = items.filter(::matches).map { it to secret(it) }
        val lines = shown.map { (item, secret) -> line(item, secret, reveal) }
        return buildList {
            add("${file.path} (${file.kind.label}, ${entries(items.size)})")
            if (items.isEncryptedPreferences()) {
                add("Encrypted (EncryptedSharedPreferences): keys and values are ciphertext. Read-only.")
            }
            keyFilter?.let { add(filterNote(it, shown.size)) }
            redactionNote(shown.count { it.second != null }, reveal)?.let(::add)
            lines.count { it.truncated }.takeIf { it > 0 }?.let { add(truncationNote(it)) }
            lines.forEach { add(it.text) }
        }.joinToString("\n")
    }

    private fun matches(item: PrefItem) = keyFilter == null || item.key.contains(keyFilter, ignoreCase = true)

    /** Why a value looks secret. Only the value-shape reasons are named in its marker. */
    private sealed interface Secret {
        data object Key : Secret
        data object Jwt : Secret
        data class Holds(val key: String) : Secret
    }

    /**
     * Why [item]'s value looks secret, or null. Best-effort, by key name first, then by the value's
     * shape: a JWT, or JSON that holds a secret-looking key at any depth — `flutter.user` cached as
     * `{"accessToken":"…"}` has a harmless key and a token inside.
     */
    private fun secret(item: PrefItem): Secret? {
        if (item !is PrefItem.Typed || item.value.type in NEVER_SECRET) return null
        val text = item.value.text()
        return when {
            SECRET_KEY.containsMatchIn(item.key) -> Secret.Key
            JWT.containsMatchIn(text) -> Secret.Jwt
            else -> secretKeyInJson(text)?.let(Secret::Holds)
        }
    }

    private class Line(val text: String, val truncated: Boolean)

    private fun line(item: PrefItem, secret: Secret?, reveal: Reveal): Line {
        val key = escapeControlChars(item.key)
        return when (item) {
            is PrefItem.Opaque -> {
                val description = bounded(item.description)
                Line("$key (not editable: ${description.text})", description.truncated)
            }
            is PrefItem.Typed -> {
                val raw = item.value.text()
                val value = when {
                    secret != null && reveal != Reveal.ALLOWED -> Line(redacted(secret, raw.length), false)
                    else -> bounded(raw)
                }
                Line("$key (${item.value.type.argument}) = ${value.text}", value.truncated)
            }
        }
    }

    private fun redacted(secret: Secret, length: Int): String = when (secret) {
        Secret.Key -> "<redacted, $length chars>"
        Secret.Jwt -> "<redacted: looks like a JWT, $length chars>"
        is Secret.Holds -> {
            val named = escapeControlChars(secret.key).take(MAX_NAMED_KEY_CHARS)
            "<redacted: holds $named, $length chars>"
        }
    }

    /**
     * `{"items":[…]…[truncated, 40116 chars]`.
     *
     * Measured in the value's own characters, both where it is cut and in the length reported, so
     * asking again with `maxValueChars` at that length returns the whole value. The cut is made
     * before escaping, so an escape is never split, and it never ends between the two halves of a
     * surrogate pair. Escapes can make the shown text longer than [maxValueChars]; the cap on the
     * whole result still holds.
     */
    private fun bounded(raw: String): Line {
        if (raw.length <= maxValueChars) return Line(escapeControlChars(raw), truncated = false)
        val end = if (raw[maxValueChars - 1].isHighSurrogate()) maxValueChars - 1 else maxValueChars
        return Line(escapeControlChars(raw.take(end)) + "…[truncated, ${raw.length} chars]", truncated = true)
    }

    private fun filterNote(filter: String, matched: Int): String = when (matched) {
        0 -> "No entry has a key containing '${escapeControlChars(filter)}'."
        else -> "Showing ${entries(matched)} whose key contains '${escapeControlChars(filter)}'."
    }

    private fun redactionNote(secrets: Int, reveal: Reveal): String? {
        if (secrets == 0) return null
        val values = if (secrets == 1) "1 value is" else "$secrets values are"
        return when (reveal) {
            Reveal.ALLOWED -> "$values shown although the key or value looks secret: the developer allowed it."
            Reveal.DECLINED ->
                "$values redacted because the key or value looks secret, and the developer declined to " +
                    "reveal them."
            Reveal.NOT_ASKED ->
                "$values redacted because the key or value looks secret (best-effort, by key name and " +
                    "value shape). Pass showSecrets: true to ask the developer to reveal them."
        }
    }

    private fun truncationNote(truncated: Int): String =
        "${if (truncated == 1) "1 value is" else "$truncated values are"} cut at $maxValueChars characters. " +
            "Pass key to read one entry, with maxValueChars up to $MAX_VALUE_CHARS to see more of it."

    private fun entries(count: Int) = "$count ${if (count == 1) "entry" else "entries"}"

    companion object {
        const val DEFAULT_MAX_VALUE_CHARS = 500
        const val MIN_VALUE_CHARS = 50

        /** The cap on the whole result, so a larger value could never be shown anyway. */
        const val MAX_VALUE_CHARS = McpShell.DEFAULT_MAX_CHARS

        /**
         * Key names that say "secret". `auth` is not followed by `or`, so `author` and `authority`
         * stay readable: they are common in cached JSON, where one match redacts the whole value.
         */
        private val SECRET_KEY = Regex(
            "token|secret|auth(?!or)|passw|pwd|session|api_?key|jwt|bearer|cookie|credential|private_?key",
            RegexOption.IGNORE_CASE,
        )

        /** A JWT's header and payload: base64url JSON, so both start `eyJ`, then a dot each. */
        private val JWT = Regex("""eyJ[\w-]+\.[\w-]+\.""")

        /** One bit, or a number: a flag or a counter, never a credential. */
        private val NEVER_SECRET = setOf(PrefType.BOOLEAN, PrefType.INT, PrefType.LONG)

        private const val MAX_NAMED_KEY_CHARS = 40

        /** The first secret-looking key in [text] read as a JSON object or array, at any depth. */
        private fun secretKeyInJson(text: String): String? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
            return runCatching { JsonParser.parseString(trimmed) }.getOrNull()?.let(::firstSecretKey)
        }

        private fun firstSecretKey(element: JsonElement): String? = when {
            element.isJsonObject -> element.asJsonObject.entrySet().firstNotNullOfOrNull { (key, value) ->
                key.takeIf { SECRET_KEY.containsMatchIn(it) } ?: firstSecretKey(value)
            }
            element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull(::firstSecretKey)
            else -> null
        }

        /** The view the arguments ask for, or why they ask for an impossible one. */
        fun from(arguments: JsonObject): Result<StorageView> = runCatching {
            val maxValueChars = arguments.optionalInt("maxValueChars", DEFAULT_MAX_VALUE_CHARS)
            require(maxValueChars in MIN_VALUE_CHARS..MAX_VALUE_CHARS) {
                "maxValueChars is $MIN_VALUE_CHARS-$MAX_VALUE_CHARS, not $maxValueChars."
            }
            StorageView(
                maxValueChars = maxValueChars,
                keyFilter = arguments.optionalString("key"),
                showSecrets = arguments.optionalBoolean("showSecrets", false),
            )
        }
    }
}
