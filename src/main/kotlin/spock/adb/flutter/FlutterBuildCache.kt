package spock.adb.flutter

/**
 * Flutter detection, remembered per install.
 *
 * Listing every APK of a package with `unzip -l` is the slow part of reading app info, and its
 * answer only changes when the app is installed again — which changes `versionCode` or
 * `lastUpdateTime` in `dumpsys package`. Keyed on those, a refresh reuses the answer.
 */
class FlutterBuildCache(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Key(val serial: String, val packageName: String, val versionCode: String, val lastUpdateTime: String)

    /** Null [build] is an answer too: the install was listed and is not a Flutter app. */
    private data class Detected(val build: FlutterBuild?)

    private val detected = object : LinkedHashMap<Key, Detected>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Detected>?) = size > capacity
    }

    /**
     * How [packageName] on device [serial] was built, running [list] — the output of
     * [FlutterBuild.listingCommand] — only when this install has not been listed before.
     *
     * Best effort: a listing that fails or times out reads as not Flutter and is not remembered,
     * so the next read tries again. Without a version and an update time in [dumpsys] there is no
     * telling one install from the next, so nothing is remembered then either.
     */
    fun detect(serial: String, packageName: String, dumpsys: String, list: () -> String): FlutterBuild? {
        val key = keyOf(serial, packageName, dumpsys)
        key?.let { synchronized(detected) { detected[it] } }?.let { return it.build }
        val listing = runCatching(list).getOrNull() ?: return null
        val build = FlutterBuild.of(listing, FlutterBuild.isDebuggable(dumpsys))
        key?.let { synchronized(detected) { detected[it] = Detected(build) } }
        return build
    }

    private fun keyOf(serial: String, packageName: String, dumpsys: String): Key? {
        val versionCode = VERSION_CODE.find(dumpsys)?.groupValues?.get(1) ?: return null
        val updated = LAST_UPDATE_TIME.find(dumpsys)?.groupValues?.get(1)?.trim() ?: return null
        return Key(serial, packageName, versionCode, updated)
    }

    companion object {
        private const val DEFAULT_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f

        private val VERSION_CODE = Regex("""\bversionCode=(\S+)""")

        /** `lastUpdateTime=2026-10-01 05:31:02`: a date and a time, so the rest of the line. */
        private val LAST_UPDATE_TIME = Regex("""\blastUpdateTime=([^\r\n]+)""")

        /** One for the IDE: the tool window and the agent tools ask about the same installs. */
        val shared = FlutterBuildCache()
    }
}
