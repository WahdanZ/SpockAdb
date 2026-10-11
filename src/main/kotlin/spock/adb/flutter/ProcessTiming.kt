package spock.adb.flutter

/**
 * When a process on the device started, read from the device's own `/proc` so no host/device
 * clock skew enters: [startEpochMs] on the device's wall clock (to compare with the VM's
 * `getVM().startTime`), [ageMs] from the device's uptime. Either is null when not readable.
 */
data class ProcessTiming(val startEpochMs: Long?, val ageMs: Long?) {

    companion object {
        /**
         * `USER_HZ`, what `starttime` is counted in, when `getconf` does not say: 100 on every
         * Android ABI (`getconf CLK_TCK` answered 100 on an Android 14 emulator).
         */
        const val DEFAULT_CLOCK_TICKS = 100L

        /** `/proc/<pid>/stat`'s field 22, counted from field 3 — the first after `comm`'s `)`. */
        private const val START_TIME_AFTER_COMM = 22 - 3

        private const val MS_PER_SECOND = 1000L
        private val UPTIME = Regex("""^(\d+(?:\.\d+)?)\s+\d+(?:\.\d+)?$""")
        private val BTIME = Regex("""^btime\s+(\d+)$""")
        private val NUMBER = Regex("""^\d+$""")

        /**
         * One shell command printing what [parse] reads. Readable by the adb shell user on an
         * emulator (Android 14, another app's pid); a phone whose `/proc` is mounted `hidepid` or
         * whose SELinux policy refuses prints an error instead, and [parse] gives nulls.
         */
        fun command(pid: Long): String =
            "cat /proc/$pid/stat /proc/uptime; grep '^btime' /proc/stat; getconf CLK_TCK 2>/dev/null"

        /** What [command] printed, or null when it holds no `stat` line. Never throws. */
        fun parse(output: String): ProcessTiming? {
            val lines = output.lines().map { it.trim() }
            // `comm` may hold spaces and parentheses: fields are counted after the last `)`.
            val stat = lines.firstOrNull { it.contains(") ") && it.first().isDigit() } ?: return null
            val ticks = stat.substringAfterLast(')').trim().split(Regex("""\s+"""))
                .getOrNull(START_TIME_AFTER_COMM)?.toLongOrNull() ?: return null
            val hz = lines.lastOrNull { NUMBER.matches(it) }?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_CLOCK_TICKS
            val sinceBootMs = ticks * MS_PER_SECOND / hz
            val uptimeMs = lines.firstNotNullOfOrNull { UPTIME.find(it) }?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { (it * MS_PER_SECOND).toLong() }
            val bootEpochMs = lines.firstNotNullOfOrNull { BTIME.find(it) }?.groupValues?.get(1)?.toLongOrNull()
                ?.let { it * MS_PER_SECOND }
            return ProcessTiming(
                startEpochMs = bootEpochMs?.let { it + sinceBootMs },
                ageMs = uptimeMs?.let { (it - sinceBootMs).coerceAtLeast(0) },
            )
        }
    }
}
