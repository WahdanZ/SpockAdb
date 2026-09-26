package spock.adb.timeline

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Moves logcat's `threadtime` stamps onto the host's clock.
 *
 * A `threadtime` stamp is the device's wall clock in the device's time zone, with no year:
 * `09-26 14:03:07.412`. The host and the device rarely agree on the time to the second, and not
 * always on the zone, so a stamp read as if it were host time put a log line seconds away from
 * the button press that caused it.
 *
 * The clock is measured, not assumed: the recorder writes a marker line with `log` at a known
 * host moment and reads back the stamp the device gave it. The difference between the two,
 * with the stamp read as UTC, is the whole correction — zone and drift together — so no time
 * zone has to be asked for or parsed.
 */
class DeviceClock private constructor(private val offsetMs: Long) {

    /**
     * The host moment [stamp] describes, or null when it is not a `threadtime` stamp.
     *
     * The stamp has no year, so the year is the one that puts it nearest [hostNowMs]: a line
     * logged on the 31st of December and read after midnight belongs to last year.
     */
    fun toHostMillis(stamp: String, hostNowMs: Long): Long? {
        val deviceYear = LocalDateTime.ofEpochSecond((hostNowMs + offsetMs) / MILLIS, 0, ZoneOffset.UTC).year
        return (deviceYear - 1..deviceYear + 1)
            .mapNotNull { year -> wallClockMillis(stamp, year)?.minus(offsetMs) }
            .minByOrNull { kotlin.math.abs(it - hostNowMs) }
    }

    companion object {
        private const val MILLIS = 1_000L
        private val STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")

        /**
         * A clock from the stamp the device gave a line written at [hostMs].
         *
         * The year is the host's, give or take one: whichever puts the two clocks closest.
         */
        fun sync(deviceStamp: String, hostMs: Long): DeviceClock? {
            val hostYear = LocalDateTime.ofEpochSecond(hostMs / MILLIS, 0, ZoneOffset.UTC).year
            val offset = (hostYear - 1..hostYear + 1)
                .mapNotNull { year -> wallClockMillis(deviceStamp, year)?.minus(hostMs) }
                .minByOrNull { kotlin.math.abs(it) }
                ?: return null
            return DeviceClock(offset)
        }

        /** [stamp] read as UTC in [year]; null for anything that is not `MM-dd HH:mm:ss.SSS`. */
        internal fun wallClockMillis(stamp: String, year: Int): Long? = try {
            LocalDateTime.parse("$year-${stamp.trim()}", STAMP).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
