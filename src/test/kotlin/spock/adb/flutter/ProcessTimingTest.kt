package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProcessTimingTest {

    /** `/proc/2561/stat` of com.android.systemui on an Android 14 emulator: field 22 is 7984. */
    private val systemUi = "2561 (ndroid.systemui) S 377 377 0 0 -1 1077952832 81372 0 0 0 1446 615 0 0 " +
        "10 -10 98 0 7984 15468032000 47360 18446744073709551615 1 1 0 0 0 0 4612 1 1073775864 0 0 0 17 2 0 0 0 0 0"

    private fun output(stat: String, hz: String = "100") =
        "$stat\n150000.50 280000.12\nbtime 1790771287\n$hz\n"

    @Test
    fun `the recorded systemui process - start on the device's clock, and its age`() {
        val timing = ProcessTiming.parse(output(systemUi))!!
        assertEquals(1_790_771_287_000L + 79_840L, timing.startEpochMs)
        assertEquals(150_000_500L - 79_840L, timing.ageMs)
    }

    @Test
    fun `a command name with spaces and parentheses - fields count from the last parenthesis`() {
        val odd = systemUi.replace("(ndroid.systemui)", "(a) b (c))")
        assertEquals(1_790_771_287_000L + 79_840L, ProcessTiming.parse(output(odd))!!.startEpochMs)
    }

    @Test
    fun `the clock rate from getconf, 100 when it says nothing`() {
        assertEquals(1_790_771_287_000L + 31_936L, ProcessTiming.parse(output(systemUi, hz = "250"))!!.startEpochMs)
        val noGetconf = "$systemUi\n150000.50 280000.12\nbtime 1790771287\n"
        assertEquals(1_790_771_287_000L + 79_840L, ProcessTiming.parse(noGetconf)!!.startEpochMs)
    }

    @Test
    fun `a refused stat is no timing, a missing btime or uptime only loses that half`() {
        assertNull(ProcessTiming.parse("cat: /proc/2561/stat: Permission denied\n150000.50 280000.12\nbtime 1\n100\n"))
        assertNull(ProcessTiming.parse(""))
        val noBtime = ProcessTiming.parse("$systemUi\n150000.50 280000.12\n")!!
        assertNull(noBtime.startEpochMs)
        assertEquals(150_000_500L - 79_840L, noBtime.ageMs)
    }

    @Test
    fun `the command reads only the pid's own stat, uptime and btime`() {
        assertEquals(
            "cat /proc/2561/stat /proc/uptime; grep '^btime' /proc/stat; getconf CLK_TCK 2>/dev/null",
            ProcessTiming.command(2561),
        )
    }
}
