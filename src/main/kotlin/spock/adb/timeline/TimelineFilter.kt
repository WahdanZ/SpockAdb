package spock.adb.timeline

/** What the panel and `android_get_debug_timeline` show. Pure, so the rules are directly testable. */
data class TimelineFilter(
    val categories: Set<TimelineCategory> = TimelineCategory.entries.toSet(),
    val minSeverity: TimelineSeverity = TimelineSeverity.INFO,
    val query: String = "",
    /** Inclusive bounds on [TimelineEvent.timeMs]; null leaves that end open. */
    val sinceMs: Long? = null,
    val untilMs: Long? = null,
) {
    fun matches(event: TimelineEvent): Boolean {
        if (event.category !in categories) return false
        if (!event.severity.isAtLeast(minSeverity)) return false
        if (sinceMs != null && event.timeMs < sinceMs) return false
        if (untilMs != null && event.timeMs > untilMs) return false
        if (query.isBlank()) return true
        return event.title.contains(query, ignoreCase = true) ||
            event.detail.contains(query, ignoreCase = true)
    }
}
