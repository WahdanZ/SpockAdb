package spock.adb.logcat

/** One parsed logcat record. */
data class LogcatEntry(
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String,
    /** The original line, kept so copy and export reproduce exactly what the device sent. */
    val raw: String,
)

enum class LogLevel(val code: Char, val label: String) {
    VERBOSE('V', "Verbose"),
    DEBUG('D', "Debug"),
    INFO('I', "Info"),
    WARN('W', "Warn"),
    ERROR('E', "Error"),
    ASSERT('A', "Assert");

    /** Levels are ordered, so "at least WARN" is a comparison rather than a set. */
    fun isAtLeast(other: LogLevel): Boolean = ordinal >= other.ordinal

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** `F` (fatal) is what the device prints for what the API calls ASSERT. */
        fun fromCode(code: Char): LogLevel? = when (code) {
            'F' -> ASSERT
            else -> byCode[code]
        }
    }
}
