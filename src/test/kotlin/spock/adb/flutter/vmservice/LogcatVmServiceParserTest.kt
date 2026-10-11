package spock.adb.flutter.vmservice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LogcatVmServiceParserTest {

    /** `logcat -d -v threadtime -s flutter`: an old engine, the app's two runs, another app. */
    private val logcat = javaClass.getResource("/vmservice/logcat-vm-service.txt")!!.readText()

    private fun latest(vararg pids: Int) = LogcatVmServiceParser.latest(logcat, pids.toSet())

    @Test
    fun `the current announcement form is read with its device port`() {
        val found = latest(5021)!!
        assertEquals(5021, found.pid)
        assertEquals(43_181, found.uri.port)
        assertEquals("ws://127.0.0.1:43181/AbCdEfGh123=/ws", found.uri.webSocketUri.toString())
    }

    @Test
    fun `the Observatory form of older engines is read too`() {
        assertEquals("ws://127.0.0.1:38211/oLdToKeN1Ab=/ws", latest(4100)!!.uri.webSocketUri.toString())
    }

    @Test
    fun `across the app's processes the latest loopback announcement wins`() {
        // 5022 also announced a non-loopback address afterwards; that one is not usable.
        val found = latest(5021, 5022)!!
        assertEquals(5022, found.pid)
        assertEquals(45_555, found.uri.port)
    }

    @Test
    fun `another app's announcement is not taken`() {
        assertEquals(40_001, latest(6100)!!.uri.port)
        assertNull(latest(7777))
        assertNull(LogcatVmServiceParser.latest("", setOf(5021)))
    }

    @Test
    fun `adb's carriage returns do not end up in the address`() {
        val crlf = logcat.replace("\n", "\r\n")
        assertEquals(43_181, LogcatVmServiceParser.latest(crlf, setOf(5021))!!.uri.port)
    }
}
