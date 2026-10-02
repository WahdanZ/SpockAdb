package spock.adb.flutter

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.intellij.openapi.diagnostic.Logger
import spock.adb.ShellOutputReceiver
import spock.adb.timeline.DeviceClock
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import com.android.ddmlib.TimeoutException as AdbTimeoutException

/**
 * The device's clock against the host's, for one Flutter session (design §4a).
 *
 * Two clocks meet in a Flutter diagnosis: VM Service events carry epoch ms on the **device's**
 * clock, and logcat's `threadtime` stamps are the device's **local** time, in its zone, with no
 * year. Comparing them as if both were epoch is off by the zone — two hours on a device in
 * Berlin. So the zone is read with the clock, from the same `date` call, and logcat stamps are
 * moved to device epoch with it ([logcatToEpoch]). Correlation compares on device epoch, which
 * needs no host at all; the Timeline moves device epoch onto the host's clock with [epochToHost].
 *
 * @param epochOffsetMs device epoch minus host epoch at the same moment.
 * @param uncertaintyMs how far [epochOffsetMs] may be off: half the round trip of the sample it
 *   came from, or a second when the device's `date` has no milliseconds.
 * @param zone the device's UTC offset when measured. An offset, not a region: a stamp from the
 *   other side of a daylight-saving change is an hour off.
 * @param millisecondPrecision false when the device's `date` would not print milliseconds.
 */
data class DeviceTime(
    val epochOffsetMs: Long,
    val uncertaintyMs: Long,
    val zone: ZoneOffset,
    val millisecondPrecision: Boolean = true,
) {
    /** Device epoch ms to the host's clock. */
    fun epochToHost(deviceEpochMs: Long): Long = deviceEpochMs - epochOffsetMs

    /**
     * A logcat `threadtime` stamp (`10-02 14:03:07.412`) as device epoch ms, or null when it is
     * not one: the stamp read as UTC, minus the zone. It has no year, so the year is the one
     * that puts it nearest the device's now, as [DeviceClock] chooses it — a line logged on the
     * 31st of December and read after midnight belongs to last year.
     */
    fun logcatToEpoch(stamp: String, hostNowMs: Long = System.currentTimeMillis()): Long? {
        val deviceNow = hostNowMs + epochOffsetMs
        val year = LocalDateTime.ofEpochSecond(deviceNow / MILLIS, 0, ZoneOffset.UTC).year
        val zoneMs = zone.totalSeconds * MILLIS
        return (year - 1..year + 1)
            .mapNotNull { DeviceClock.wallClockMillis(stamp, it)?.minus(zoneMs) }
            .minByOrNull { kotlin.math.abs(it - deviceNow) }
    }

    /** What a report should say about this clock, when anything: only a coarse one needs a word. */
    val note: String?
        get() = if (millisecondPrecision) {
            null
        } else {
            "The device's `date` prints no milliseconds, so device times are known to about a second."
        }

    /** One `date` call: when it was sent and answered on the host's clock, and what it printed. */
    data class Sample(val sentHostMs: Long, val receivedHostMs: Long, val output: String) {
        val roundTripMs: Long get() = receivedHostMs - sentHostMs
    }

    /** What one `date` printed: device epoch ms, its zone, and whether the ms are real. */
    data class Reading(val deviceEpochMs: Long, val zone: ZoneOffset, val millisecondPrecision: Boolean)

    companion object {
        /** Epoch ms and the zone, from one call: `1727870587412 +0200`. Toybox has `%3N`. */
        const val COMMAND = "date '+%s%3N %z'"

        /** For a `date` that prints `%3N` as it is: whole seconds. */
        const val SECONDS_COMMAND = "date '+%s %z'"

        /** Samples per measurement. The first is a warm-up — the slowest, as measured — and dropped. */
        const val SAMPLES = 5

        /** What a seconds-only clock is known to. */
        const val SECOND_PRECISION_UNCERTAINTY_MS = 1_000L

        private const val MILLIS = 1_000L
        private const val HALF_SECOND_MS = 500L
        private const val MIN_MS_DIGITS = 12
        private const val MAX_SECONDS_DIGITS = 11
        private val NUMBER = Regex("""^\d+$""")
        private val OFFSET = Regex("""^([+-])(\d{2}):?(\d{2})$""")

        /**
         * What [COMMAND] or [SECONDS_COMMAND] printed — its last non-blank line — or null when it
         * is neither: a `date` that printed `%3N` literally, an error, nothing. A whole-seconds
         * answer reads as such.
         */
        fun parse(output: String): Reading? {
            // The last line: a shell may print a warning (a locale, a MOTD) before the answer.
            val line = output.lines().lastOrNull { it.isNotBlank() } ?: return null
            val parts = line.trim().split(Regex("""\s+"""))
            val stamp = parts.firstOrNull()?.takeIf { parts.size == 2 && NUMBER.matches(it) } ?: return null
            val zone = zoneOf(parts[1]) ?: return null
            val value = stamp.toLongOrNull() ?: return null
            return when {
                stamp.length >= MIN_MS_DIGITS -> Reading(value, zone, true)
                stamp.length <= MAX_SECONDS_DIGITS -> Reading(value * MILLIS + HALF_SECOND_MS, zone, false)
                else -> null
            }
        }

        /**
         * The clock from [samples] of one command, in the order taken: the first dropped when
         * there are others, then the one with the smallest round trip, whose middle is taken for
         * the moment the device read its clock. Null when no sample reads.
         */
        fun fromSamples(samples: List<Sample>): DeviceTime? {
            val kept = if (samples.size > 1) samples.drop(1) else samples
            val best = kept.mapNotNull { sample -> parse(sample.output)?.let { sample to it } }
                .minByOrNull { (sample, _) -> sample.roundTripMs }
                ?: return null
            val (sample, reading) = best
            val middle = (sample.sentHostMs + sample.receivedHostMs) / 2
            return DeviceTime(
                epochOffsetMs = reading.deviceEpochMs - middle,
                uncertaintyMs = if (reading.millisecondPrecision) {
                    sample.roundTripMs / 2
                } else {
                    SECOND_PRECISION_UNCERTAINTY_MS
                },
                zone = reading.zone,
                millisecondPrecision = reading.millisecondPrecision,
            )
        }

        private fun zoneOf(text: String): ZoneOffset? {
            val (sign, hours, minutes) = OFFSET.matchEntire(text)?.destructured ?: return null
            return try {
                ZoneOffset.of("$sign$hours:$minutes")
            } catch (_: DateTimeException) {
                null
            }
        }
    }
}

/**
 * Measures a device's [DeviceTime] with [SAMPLES][DeviceTime.SAMPLES] `date` calls through
 * [run] — `adb shell` in production. Blocking: off the EDT. Falls back to whole seconds when the
 * device's `date` has no `%3N`.
 */
class DeviceTimeSampler(
    private val run: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Null when the device answers neither command readably. [count] samples of each command. */
    fun sample(count: Int = DeviceTime.SAMPLES): DeviceTime? {
        val precise = samples(DeviceTime.COMMAND, count)
        DeviceTime.fromSamples(precise)?.takeIf { it.millisecondPrecision }?.let { return it }
        return DeviceTime.fromSamples(samples(DeviceTime.SECONDS_COMMAND, count))
    }

    private fun samples(command: String, count: Int): List<DeviceTime.Sample> = (1..count).map {
        val sent = clock()
        val output = run(command)
        DeviceTime.Sample(sent, clock(), output)
    }

    companion object {
        private const val ADB_SECONDS = 5L
        private val log = Logger.getInstance(DeviceTimeSampler::class.java)

        /**
         * [device]'s clock, or null when adb fails or the device's `date` cannot be read. One
         * [samples] reads the zone and the clock to a round trip — enough to place a log line to
         * the second.
         */
        fun measure(device: IDevice, samples: Int = DeviceTime.SAMPLES): DeviceTime? {
            val sampler = DeviceTimeSampler({ command -> shell(device, command) })
            return sampler.sample(samples).also {
                if (it == null) log.info("Could not read the device's clock with `date`")
            }
        }

        /** The command's output, or empty when adb fails: an unreadable sample, not an error. */
        private fun shell(device: IDevice, command: String): String {
            val receiver = ShellOutputReceiver()
            val failure: Exception = try {
                device.executeShellCommand(command, receiver, ADB_SECONDS, TimeUnit.SECONDS)
                return receiver.toString()
            } catch (e: IOException) {
                e
            } catch (e: AdbCommandRejectedException) {
                e
            } catch (e: ShellCommandUnresponsiveException) {
                e
            } catch (e: AdbTimeoutException) {
                e
            }
            log.info("`$command` failed on the device: ${failure.message ?: failure.javaClass.simpleName}")
            return ""
        }
    }
}

/**
 * The [DeviceTime] of one session: measured once, a moment after the session attaches, by
 * whoever [claim]s it first. Read without waiting ([current]) or with a bound ([await]).
 */
class DeviceTimeSlot {
    private val claimed = AtomicBoolean()
    private val value = CompletableFuture<DeviceTime?>()

    /** True for the one caller that is to measure; false once someone has. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** Null records that the measurement failed, so nobody waits for it. */
    fun set(time: DeviceTime?) {
        value.complete(time)
    }

    /** The measurement, or null while it runs or when it failed. */
    val current: DeviceTime? get() = value.getNow(null)

    /** Whether the measurement has finished, with or without a clock. */
    val done: Boolean get() = value.isDone

    /** Waits up to [timeoutMs] for the measurement. Blocking: off the EDT. */
    fun await(timeoutMs: Long): DeviceTime? = try {
        value.get(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }
}
