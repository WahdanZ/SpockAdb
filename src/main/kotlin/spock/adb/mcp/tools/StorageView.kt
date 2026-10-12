package spock.adb.mcp.tools

import com.google.gson.JsonObject
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
 * - a value whose key says token, secret, auth, password or session is redacted, since these files
 *   hold push and auth tokens and the tool runs without asking. Booleans are shown anyway: a
 *   `session_active` flag is one bit and no secret. Matching is by key name, so `author` is
 *   redacted too — cheap to reveal, where the opposite mistake is not.
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
    fun secretKeys(items: List<PrefItem>): List<String> = items.filter { matches(it) && isSecret(it) }.map { it.key }

    fun describe(file: StorageFile, items: List<PrefItem>, reveal: Reveal = Reveal.NOT_ASKED): String {
        val shown = items.filter(::matches)
        val lines = shown.map { line(it, reveal) }
        return buildList {
            add("${file.path} (${file.kind.label}, ${entries(items.size)})")
            if (items.isEncryptedPreferences()) {
                add("Encrypted (EncryptedSharedPreferences): keys and values are ciphertext. Read-only.")
            }
            keyFilter?.let { add(filterNote(it, shown.size)) }
            redactionNote(shown.count(::isSecret), reveal)?.let(::add)
            lines.count { it.truncated }.takeIf { it > 0 }?.let { add(truncationNote(it)) }
            lines.forEach { add(it.text) }
        }.joinToString("\n")
    }

    private fun matches(item: PrefItem) = keyFilter == null || item.key.contains(keyFilter, ignoreCase = true)

    private fun isSecret(item: PrefItem) =
        item is PrefItem.Typed && item.value.type != PrefType.BOOLEAN && SECRET_KEY.containsMatchIn(item.key)

    private class Line(val text: String, val truncated: Boolean)

    private fun line(item: PrefItem, reveal: Reveal): Line {
        val key = escapeControlChars(item.key)
        return when (item) {
            is PrefItem.Opaque -> {
                val description = bounded(item.description)
                Line("$key (not editable: ${description.text})", description.truncated)
            }
            is PrefItem.Typed -> {
                val raw = item.value.text()
                val value = when {
                    isSecret(item) && reveal != Reveal.ALLOWED -> Line("<redacted, ${raw.length} chars>", false)
                    else -> bounded(raw)
                }
                Line("$key (${item.value.type.argument}) = ${value.text}", value.truncated)
            }
        }
    }

    /** `{"items":[…]…[truncated, 40116 chars]`: the length is the whole value's, so the agent knows what it missed. */
    private fun bounded(raw: String): Line {
        val escaped = escapeControlChars(raw)
        return when {
            escaped.length <= maxValueChars -> Line(escaped, truncated = false)
            else -> Line(escaped.take(maxValueChars) + "…[truncated, ${raw.length} chars]", truncated = true)
        }
    }

    private fun filterNote(filter: String, matched: Int): String = when (matched) {
        0 -> "No entry has a key containing '${escapeControlChars(filter)}'."
        else -> "Showing ${entries(matched)} whose key contains '${escapeControlChars(filter)}'."
    }

    private fun redactionNote(secrets: Int, reveal: Reveal): String? {
        if (secrets == 0) return null
        val values = if (secrets == 1) "1 value is" else "$secrets values are"
        return when (reveal) {
            Reveal.ALLOWED -> "$values shown although the key looks secret: the developer allowed it."
            Reveal.DECLINED ->
                "$values redacted because the key looks secret, and the developer declined to reveal them."
            Reveal.NOT_ASKED ->
                "$values redacted because the key contains token, secret, auth, password or session. " +
                    "Pass showSecrets: true to ask the developer to reveal them."
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

        private val SECRET_KEY = Regex("token|secret|auth|password|session", RegexOption.IGNORE_CASE)

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
