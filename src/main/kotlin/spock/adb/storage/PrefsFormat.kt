package spock.adb.storage

/** One key in a preferences file, as the editor sees it. */
sealed interface PrefItem {
    val key: String

    data class Typed(override val key: String, val value: PrefValue) : PrefItem

    /**
     * Present in the file and written back untouched, but not something the editor can change:
     * an XML element it does not model, or a DataStore value of a kind it does not know.
     */
    data class Opaque(override val key: String, val description: String) : PrefItem
}

/** An edit, expressed against the file as it was read. */
sealed interface PrefChange {
    val key: String

    data class Put(override val key: String, val value: PrefValue) : PrefChange

    data class Remove(override val key: String) : PrefChange
}

/** The file is not in the shape its format requires. Nothing may be written over it. */
class PrefsFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * A preferences file format: how to read one, and how to write an edit back into one.
 *
 * [write] takes the original bytes rather than a list of items on purpose. Everything the
 * editor does not model lives only in those bytes, and a write that rebuilt the file from what
 * the editor understood would drop it — corrupting the app's state rather than editing it.
 */
interface PrefsFormat {

    /** The types a [PrefChange.Put] may store in this format. */
    val types: List<PrefType>

    /** @throws PrefsFormatException when the bytes are not a file of this format. */
    fun read(bytes: ByteArray): List<PrefItem>

    /**
     * @throws PrefsFormatException when [original] is not a file of this format.
     * @throws IllegalArgumentException when a change cannot be applied: a type the format cannot
     *   store, a key held by an [PrefItem.Opaque] entry, or removing a key that is not there.
     */
    fun write(original: ByteArray, changes: List<PrefChange>): ByteArray
}

/**
 * Keys written by Jetpack Security's EncryptedSharedPreferences. Every other key and value in
 * such a file is ciphertext, and editing it would only produce something the app cannot decrypt.
 */
fun List<PrefItem>.isEncryptedPreferences(): Boolean =
    any { it.key.startsWith(ENCRYPTED_PREFS_KEY_PREFIX) }

private const val ENCRYPTED_PREFS_KEY_PREFIX = "__androidx_security_crypto_encrypted_prefs_"

/**
 * Applies [changes] to a format's own entries, in order, keeping everything else in place.
 *
 * A changed key keeps its position, so a diff of the file shows the edit and nothing else. A
 * key the file holds more than once is collapsed into the first position: both formats let the
 * last one win on read, so the survivor has to be the value that was asked for. Position comes
 * from the first duplicate, but what `build` carries over as `previous` comes from the last,
 * live one — the first is state the app never sees, and carrying it would revive it.
 */
internal fun <N> List<N>.applying(
    changes: List<PrefChange>,
    types: List<PrefType>,
    keyOf: (N) -> String?,
    isEditable: (N) -> Boolean,
    build: (key: String, value: PrefValue, previous: N?) -> N,
): List<N> {
    val result = toMutableList()
    changes.forEach { change ->
        val held = result.indices.filter { keyOf(result[it]) == change.key }
        require(held.all { isEditable(result[it]) }) {
            "'${change.key}' holds a value this editor cannot read, so it is left as it is."
        }
        when (change) {
            is PrefChange.Put -> {
                require(change.value.type in types) {
                    "This file cannot store a ${change.value.type.label}. It can store: ${types.joinToString()}."
                }
                result.put(held, build(change.key, change.value, held.lastOrNull()?.let { result[it] }))
            }
            is PrefChange.Remove -> {
                require(held.isNotEmpty()) { "'${change.key}' is not in this file." }
                result.removeAllAt(held)
            }
        }
    }
    return result
}

/** Puts [entry] where the first of [held] was, or at the end when nothing was, and drops the rest. */
private fun <N> MutableList<N>.put(held: List<Int>, entry: N) {
    if (held.isEmpty()) {
        add(entry)
        return
    }
    this[held.first()] = entry
    removeAllAt(held.drop(1))
}

/** [indices] ascending; removed from the end so the earlier ones stay valid. */
private fun <N> MutableList<N>.removeAllAt(indices: List<Int>) {
    indices.asReversed().forEach { removeAt(it) }
}
