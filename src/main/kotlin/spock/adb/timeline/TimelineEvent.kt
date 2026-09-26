package spock.adb.timeline

/**
 * One thing that happened, on the host's clock.
 *
 * Every source converts to host time before it records: actions and agent calls happen on the
 * host already, and device log lines are moved onto it by [DeviceClock]. One clock is what lets
 * the timeline put a button press and the log line it caused in the right order.
 *
 * @param id assigned by [DebugTimeline]; orders events recorded at the same millisecond.
 * @param deviceTime the device's own stamp for an event read from its log, kept so the detail
 *   can be matched against Logcat.
 */
data class TimelineEvent(
    val timeMs: Long,
    val category: TimelineCategory,
    val severity: TimelineSeverity,
    val title: String,
    val detail: String = "",
    val deviceSerial: String? = null,
    val deviceTime: String? = null,
    val id: Long = 0,
)

enum class TimelineCategory(val label: String) {
    ACTIVITY("Activity"),
    APP_LIFECYCLE("App"),
    SPOCK_ACTION("Action"),
    STORAGE("Storage"),
    BACKGROUND_WORK("Jobs"),
    DEVICE_CONDITION("Conditions"),
    DEVICE("Device"),
    LOG("Log"),
    MCP("Agent"),
    MARKER("Marker"),
    ;

    companion object {
        /** Matches a label or a name, case-insensitively, for tool arguments. */
        fun parse(text: String): TimelineCategory? = entries.firstOrNull {
            it.name.equals(text, ignoreCase = true) || it.label.equals(text, ignoreCase = true)
        }
    }
}

/** Ordered, so "at least WARNING" is a comparison. */
enum class TimelineSeverity(val label: String) {
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
    ;

    fun isAtLeast(other: TimelineSeverity): Boolean = ordinal >= other.ordinal

    companion object {
        fun parse(text: String): TimelineSeverity? = entries.firstOrNull {
            it.name.equals(text, ignoreCase = true) || it.label.equals(text, ignoreCase = true)
        }
    }
}
