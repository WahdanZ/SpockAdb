package spock.adb.storage

/**
 * One file open in the storage editor: the rows the developer edits, and the changes they add
 * up to.
 *
 * Kept free of Swing so what Apply writes is decided by tested code, and the table model only
 * displays it. Edits are compared against the file as read, never replayed from a history of
 * keystrokes, so a value changed and changed back writes nothing.
 */
class PrefsEditSession(val file: StorageFile, val original: ByteArray) {

    /** One table row. [type] is null for an entry the editor cannot read. */
    class Row(var key: String, var type: PrefType?, var text: String, val editable: Boolean)

    private val items: List<PrefItem>

    /** Why nothing here can be changed, or null when the file can be edited. */
    val readOnlyReason: String?

    val rows: MutableList<Row>

    /** The types a row may take in this file. */
    val types: List<PrefType> = file.kind.format?.types.orEmpty()

    init {
        val format = file.kind.format
        val read = format?.let { runCatching { it.read(original) } }
        items = read?.getOrNull().orEmpty()
        readOnlyReason = when {
            format == null ->
                "${file.name} is a ${file.kind.label} file. It holds the app's own protobuf message, which " +
                    "cannot be decoded without that message's schema."
            read?.isFailure == true ->
                "${file.name} could not be read, so it is shown read-only: ${read.exceptionOrNull()?.message}"
            items.isEncryptedPreferences() ->
                "${file.name} holds EncryptedSharedPreferences. Its keys and values are ciphertext, so it is read-only."
            else -> null
        }
        rows = rowsOf(items, editable = readOnlyReason == null)
    }

    /** A new editable row with a key not yet in the file. */
    fun addRow(): Row {
        check(readOnlyReason == null) { readOnlyReason.orEmpty() }
        val taken = rows.map { it.key }.toSet()
        val key = generateSequence(1) { it + 1 }
            .map { if (it == 1) NEW_KEY else "${NEW_KEY}_$it" }
            .first { it !in taken }
        return Row(key, PrefType.STRING, "", editable = true).also { rows += it }
    }

    /** Why [row] cannot be written as it stands, or null when it can. */
    fun problemWith(row: Row): String? = when {
        !row.editable -> null
        row.type == null -> "'${row.key}' has no type."
        else -> runCatching { PrefValue.parse(requireNotNull(row.type), row.text) }.exceptionOrNull()?.message
    }

    /**
     * What Apply would write, as changes against the file as read.
     *
     * @throws IllegalStateException when the file is read-only.
     * @throws IllegalArgumentException naming the first row that cannot be written.
     */
    fun changes(): List<PrefChange> {
        check(readOnlyReason == null) { readOnlyReason.orEmpty() }
        val before = items.filterIsInstance<PrefItem.Typed>().associate { it.key to it.value }
        val locked = items.filterIsInstance<PrefItem.Opaque>().map { it.key }.toSet()

        val after = linkedMapOf<String, PrefValue>()
        rows.filter { it.editable }.forEach { row ->
            problemWith(row)?.let { throw IllegalArgumentException(it) }
            require(row.key !in locked) { "'${row.key}' is already held by an entry this editor cannot read." }
            require(after.put(row.key, PrefValue.parse(requireNotNull(row.type), row.text)) == null) {
                "'${row.key}' appears more than once. Each key can hold one value."
            }
        }

        val removed = (before.keys - after.keys).map { PrefChange.Remove(it) }
        val put = after
            .filter { (key, value) -> before[key] != value }
            .map { (key, value) -> PrefChange.Put(key, value) }
        return removed + put
    }

    /** True when Apply would write something, or when a row cannot be written yet. */
    val isDirty: Boolean
        get() = readOnlyReason == null && runCatching { changes().isNotEmpty() }.getOrDefault(true)

    /** The bytes Apply writes. Throws as [changes] does. */
    fun encode(): ByteArray = requireNotNull(file.kind.format).write(original, changes())

    private companion object {
        const val NEW_KEY = "new_key"

        /**
         * One row per key. A key the file holds twice — possible in a DataStore file — shows the
         * value that is read, which is the last, in the position of the first.
         */
        fun rowsOf(items: List<PrefItem>, editable: Boolean): MutableList<Row> {
            val byKey = linkedMapOf<String, PrefItem>()
            items.forEach { byKey[it.key] = it }
            return byKey.values.map { item ->
                when (item) {
                    is PrefItem.Typed -> Row(item.key, item.value.type, item.value.text(), editable)
                    is PrefItem.Opaque -> Row(item.key, null, item.description, editable = false)
                }
            }.toMutableList()
        }
    }
}
