package spock.adb.parser

/** One alarm an app has pending with AlarmManager, as `dumpsys alarm` describes it. */
data class PendingAlarm(
    /** `RTC_WAKEUP`, `ELAPSED`, … as the dump names it. */
    val type: String,
    val packageName: String,
    /** The alarm's tag: usually the intent action, e.g. `*walarm*:com.example.SYNC`. */
    val tag: String?,
    /**
     * When the alarm is due, as device clock time in epoch milliseconds.
     *
     * Null when the dump gives no way to place it on the clock, and for alarms set to
     * `Long.MAX_VALUE`, which never fire.
     */
    val triggerAtMillis: Long?,
    /**
     * How long after the dump the alarm is due, measured on the device's own clock, so it does
     * not depend on the host's clock agreeing with the device's. Negative when overdue.
     */
    val dueInMillis: Long?,
    /** Milliseconds between repeats, or 0 for a one-shot alarm. */
    val repeatIntervalMillis: Long,
    /** The window the system may deliver in, as the dump prints it: `0` for exact, `+1h0m0s0ms`. */
    val window: String?,
    /** The PendingIntent or listener it delivers to. */
    val target: String?,
    /** The alarm's block of the dump as printed. */
    val raw: String,
) {
    /** Wakes the device from sleep to fire, rather than waiting until it is next awake. */
    val isWakeup: Boolean get() = type.endsWith("WAKEUP")

    val isRepeating: Boolean get() = repeatIntervalMillis > 0

    /** A zero window: the app asked for an exact time. */
    val isExact: Boolean get() = window == "0"
}

/** What was read from a `dumpsys alarm` run. */
data class AlarmDump(
    val alarms: List<PendingAlarm>,
    /** Set when the dump did not have the shape the parser expects, with the reason. */
    val problem: String? = null,
    /** The dump as the device printed it, kept when there is a [problem] so it can be shown. */
    val raw: String? = null,
)

/**
 * Reads the pending alarms out of `dumpsys alarm`.
 *
 * `dumpsys alarm` takes no package filter that works across releases, so the whole dump is read
 * and filtered here by the package each alarm names.
 *
 * Each alarm starts with a line like `RTC_WAKEUP #9: Alarm{c3b5193 type 0 origWhen 1790017200000
 * whenElapsed 213850006 com.android.settings}` (Android 12+) or `RTC_WAKEUP #0: Alarm{8d6d443
 * type 0 when 1570183200000 com.example}` (earlier, inside `Batch{…}` groups). Both carry the
 * absolute due time as a number, which is converted to a clock time through the `nowRTC` /
 * `nowELAPSED` pair the dump prints near its top — the device's own clock at the moment of the
 * dump, so the time shown is what the device will see.
 */
object AlarmDumpParser {

    private val headerRegex = Regex(
        """^(?<indent>\s*)(?<name>\w+) #\d+: Alarm\{(?<hash>\w+) type (?<type>\d+) (?:origWhen|when) """ +
            """(?<when>-?\d+)(?: whenElapsed (?<elapsed>-?\d+))? (?<owner>\S+)\}\s*$""",
    )
    private val nowRegex = Regex("""nowRTC=(\d+).*?nowELAPSED=(\d+)""")
    private val repeatRegex = Regex("""repeatInterval=(\d+)""")
    private val windowRegex = Regex("""window=(\S+)""")

    /** `AlarmManager.RTC_WAKEUP` and `AlarmManager.RTC`: the number is already clock time. */
    private val WALL_CLOCK_TYPES = setOf(0, 1)

    fun parse(dump: String, packageName: String? = null): AlarmDump {
        val lines = dump.lines().map { it.trimEnd('\r') }
        if (lines.none { headerRegex.matches(it) }) {
            val empty = lines.any { PENDING_NONE.containsMatchIn(it) }
            return AlarmDump(
                alarms = emptyList(),
                problem = when {
                    dump.isBlank() -> "The device returned nothing for dumpsys alarm."
                    // A device with no alarms at all is unusual but legitimate.
                    empty -> null
                    else -> "The dump lists no alarms in a format this version recognises."
                },
                raw = dump.takeUnless { empty },
            )
        }
        val now = lines.firstNotNullOfOrNull { nowRegex.find(it) }?.let { match ->
            match.groupValues[1].toLong() to match.groupValues[2].toLong()
        }

        val seen = mutableSetOf<String>()
        val alarms = lines.indices.mapNotNull { index ->
            val header = headerRegex.matchEntire(lines[index]) ?: return@mapNotNull null
            // The same alarm can be listed twice — pending, and again as past-due.
            if (!seen.add(header.group("hash"))) return@mapNotNull null
            if (packageName != null && header.group("owner") != packageName) return@mapNotNull null
            alarm(lines, index, header, now)
        }
        return AlarmDump(alarms)
    }

    /** The alarm whose header is [header], on line [index], with the detail lines below it. */
    private fun alarm(lines: List<String>, index: Int, header: MatchResult, now: Pair<Long, Long>?): PendingAlarm {
        val indent = header.group("indent").length
        val body = lines.drop(index + 1).takeWhile { line ->
            line.isNotBlank() && line.length - line.trimStart().length > indent
        }
        val details = body.map { it.trim() }
        val (triggerAt, dueIn) = due(
            type = header.group("type").toInt(),
            whenValue = header.group("when").toLongOrNull(),
            whenElapsed = header.groups["elapsed"]?.value?.toLongOrNull(),
            now = now,
        )
        return PendingAlarm(
            type = header.group("name"),
            packageName = header.group("owner"),
            tag = details.firstOrNull { it.startsWith("tag=") }?.removePrefix("tag="),
            triggerAtMillis = triggerAt,
            dueInMillis = dueIn,
            repeatIntervalMillis = details.firstNotNullOfOrNull { repeatRegex.find(it) }
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
            // On the `type=` line from Android 12, on a `window=` line of its own before.
            window = details.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith("type=") || it.startsWith("window=") }
                    ?.let { windowRegex.find(it)?.groupValues?.get(1) }
            },
            target = details.firstOrNull { it.startsWith("operation=") || it.startsWith("listener=") }
                ?.substringAfter('='),
            raw = (listOf(lines[index]) + body).joinToString("\n"),
        )
    }

    private fun MatchResult.group(name: String): String = groups[name]?.value.orEmpty()

    /**
     * The alarm's due time on the device clock, and how far after the dump that is.
     *
     * Android 12+ prints `whenElapsed`, the time the alarm will actually be delivered after the
     * system's deferral policies, on the elapsed-realtime clock; that is preferred. Earlier
     * releases print one number whose clock depends on the alarm's type.
     */
    private fun due(type: Int, whenValue: Long?, whenElapsed: Long?, now: Pair<Long, Long>?): Pair<Long?, Long?> {
        if (whenValue == Long.MAX_VALUE) return NEVER
        val wallClock = type in WALL_CLOCK_TYPES
        if (now == null) return (whenValue?.takeIf { wallClock }) to null

        val (nowRtc, nowElapsed) = now
        val dueIn = when {
            whenElapsed != null -> whenElapsed - nowElapsed
            whenValue == null -> return NEVER
            wallClock -> whenValue - nowRtc
            else -> whenValue - nowElapsed
        }
        // A far-future elapsed time overflows when added to the clock; it means "never".
        if (dueIn > Long.MAX_VALUE - nowRtc) return NEVER
        return (nowRtc + dueIn) to dueIn
    }

    private val NEVER: Pair<Long?, Long?> = null to null

    private val PENDING_NONE = Regex("""^\s*0 pending alarms|Pending alarm batches: 0""")
}
