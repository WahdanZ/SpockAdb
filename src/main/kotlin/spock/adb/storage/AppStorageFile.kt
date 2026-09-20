package spock.adb.storage

/** What a file in an app's storage is, and so whether it can be read and edited. */
enum class StorageKind(val label: String) {
    SHARED_PREFERENCES("SharedPreferences"),
    PREFERENCES_DATASTORE("Preferences DataStore"),

    /**
     * A DataStore file that is not a `PreferenceMap`. Proto DataStore serialises the app's own
     * message, which cannot be decoded without that schema; listing it as unsupported is honest,
     * where decoding it as a PreferenceMap would show garbage and invite an edit that corrupts it.
     */
    PROTO_DATASTORE("Proto DataStore (custom schema, not supported)"),
    ;

    val format: PrefsFormat?
        get() = when (this) {
            SHARED_PREFERENCES -> SharedPrefsXml
            PREFERENCES_DATASTORE -> PreferencesProto
            PROTO_DATASTORE -> null
        }
}

/**
 * One entry of the app's data directory, as the tree shows it.
 *
 * [editable] is the same question [AppStoragePaths.classify] answers: the tree lists everything
 * the app has, and only preference files can be opened in the table and written back.
 */
data class StorageEntry(val path: String, val isDirectory: Boolean) {
    val name: String get() = path.substringAfterLast('/')

    val file: StorageFile? get() = if (isDirectory) null else AppStoragePaths.classify(path)

    val editable: Boolean get() = file?.kind?.format != null

    override fun toString(): String = path
}

/** A file in the app's storage, by its path relative to the app's data directory. */
data class StorageFile(val path: String, val kind: StorageKind) {
    val name: String get() = path.substringAfterLast('/')

    override fun toString(): String = path
}

/**
 * The only paths the storage editor reads or writes.
 *
 * This is the boundary that keeps an agent's `android_set_app_preference` from becoming a way
 * to write an arbitrary file inside an app's sandbox: a path is accepted only when it names a
 * file directly inside one of the two preference directories. Paths stay relative, so nothing
 * here can point outside the app's own data directory, and names are quoted before they reach
 * a shell — this check is about *which* files, not about escaping.
 */
object AppStoragePaths {
    const val SHARED_PREFS_DIR = "shared_prefs"
    const val DATASTORE_DIR = "files/datastore"

    private const val SHARED_PREFS_SUFFIX = ".xml"
    private const val PREFERENCES_DATASTORE_SUFFIX = ".preferences_pb"

    /** Written alongside the real files by SharedPreferences and DataStore while they save. */
    private val WORKING_FILE_SUFFIXES = listOf(".bak", ".tmp", ".lock")

    /** Null for anything that is not a preferences file: backups, temporaries, other directories. */
    fun classify(path: String): StorageFile? {
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        if (!isPlainName(name)) return null

        return when {
            directory == SHARED_PREFS_DIR && name.hasStem(SHARED_PREFS_SUFFIX) ->
                StorageFile(path, StorageKind.SHARED_PREFERENCES)
            directory != DATASTORE_DIR -> null
            name.hasStem(PREFERENCES_DATASTORE_SUFFIX) -> StorageFile(path, StorageKind.PREFERENCES_DATASTORE)
            WORKING_FILE_SUFFIXES.any { name.endsWith(it) } -> null
            else -> StorageFile(path, StorageKind.PROTO_DATASTORE)
        }
    }

    /**
     * Whether [path] names something inside the app's own data directory.
     *
     * Wider than [classify] on purpose, and only for *browsing*: the tree lists whatever the
     * app has, while [classify] still decides what may be edited. A path is browsable when it
     * is relative and climbs nowhere — the empty path is the data directory itself.
     *
     * Widening this does not widen writing. A write takes a [StorageFile], and the only way to
     * obtain one is [classify] or [parse], so `android_set_app_preference` reaches exactly the
     * files it always did.
     */
    fun isBrowsable(path: String): Boolean {
        if (path.isEmpty()) return true
        if (path.startsWith('/')) return false
        val segments = path.split('/')
        return segments.all { isPlainName(it) }
    }

    /** @throws IllegalArgumentException when [path] leaves the app's data directory. */
    fun requireBrowsable(path: String): String = path.also {
        require(isBrowsable(it)) {
            "'$it' is not a path inside the app's data directory. Give it relative to that " +
                "directory, for example $SHARED_PREFS_DIR or ${DATASTORE_DIR}."
        }
    }

    /** @throws IllegalArgumentException naming the shape a path must have. */
    fun parse(path: String): StorageFile = requireNotNull(classify(path)) {
        "'$path' is not a SharedPreferences or DataStore file. Give the path as " +
            "android_list_app_storage lists it, for example $SHARED_PREFS_DIR/settings.xml or " +
            "$DATASTORE_DIR/settings.preferences_pb."
    }

    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it.isISOControl() }

    /** Ends with [suffix] and has a name in front of it. */
    private fun String.hasStem(suffix: String): Boolean = endsWith(suffix) && length > suffix.length
}
