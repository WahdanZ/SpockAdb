package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `settings get global http_proxy` has three different ways of saying "no proxy" and only
 * one of them looks like an absence, so the parsing is where this feature breaks if it
 * breaks at all — reporting a device as proxied when it is not is worse than not shipping.
 */
class HttpProxyTest {

    @Test
    fun `parses host and port`() {
        assertEquals(HttpProxy("192.168.1.10", 8888), HttpProxy.parse("192.168.1.10:8888"))
    }

    @Test
    fun `trims the trailing newline shell output carries`() {
        assertEquals(HttpProxy("10.0.0.2", 8080), HttpProxy.parse("10.0.0.2:8080\n"))
    }

    @Test
    fun `treats every form of unset as no proxy`() {
        assertNull(HttpProxy.parse(":0"), "Android clears the proxy by writing :0")
        assertNull(HttpProxy.parse("null"), "a device that never had one answers the literal null")
        assertNull(HttpProxy.parse(""))
        assertNull(HttpProxy.parse(null))
    }

    @Test
    fun `splits on the last colon so an IPv6 literal survives`() {
        assertEquals(HttpProxy("[::1]", 8888), HttpProxy.parse("[::1]:8888"))
    }

    @Test
    fun `rejects output that is not host and port`() {
        assertNull(HttpProxy.parse("192.168.1.10"), "no port")
        assertNull(HttpProxy.parse("192.168.1.10:"), "empty port")
        assertNull(HttpProxy.parse(":8888"), "empty host")
        assertNull(HttpProxy.parse("192.168.1.10:not-a-port"))
        assertNull(HttpProxy.parse("192.168.1.10:0"), "0 is the cleared sentinel, not a port")
        assertNull(HttpProxy.parse("192.168.1.10:70000"), "above the port range")
    }

    @Test
    fun `round trips through the value written to the device`() {
        val proxy = HttpProxy("192.168.1.10", 8888)
        assertEquals("192.168.1.10:8888", proxy.toString())
        assertEquals(proxy, HttpProxy.parse(proxy.toString()))
    }

    @Test
    fun `parses typed input`() {
        assertEquals(HttpProxy("192.168.1.10", 8888), HttpProxy.fromInput("  192.168.1.10:8888  "))
    }

    @Test
    fun `typed input names what to fix`() {
        assertEquals(
            "Enter the proxy as host:port, for example 192.168.1.10:8888.",
            assertThrows(IllegalArgumentException::class.java) { HttpProxy.fromInput("   ") }.message,
        )
        assertEquals(
            "'192.168.1.10' is not host:port. Enter both parts, for example 192.168.1.10:8888.",
            assertThrows(IllegalArgumentException::class.java) { HttpProxy.fromInput("192.168.1.10") }.message,
        )
        assertEquals(
            "'eight' is not a port number.",
            assertThrows(IllegalArgumentException::class.java) { HttpProxy.fromInput("10.0.0.2:eight") }.message,
        )
    }

    @Test
    fun `validates host and port supplied separately`() {
        assertEquals(HttpProxy("10.0.0.2", 8080), HttpProxy.of(" 10.0.0.2 ", 8080))

        assertThrows(IllegalArgumentException::class.java) { HttpProxy.of("", 8080) }
        assertThrows(IllegalArgumentException::class.java) { HttpProxy.of("10.0.0.2 fake", 8080) }
        assertThrows(IllegalArgumentException::class.java) { HttpProxy.of("10.0.0.2", 0) }
        assertThrows(IllegalArgumentException::class.java) { HttpProxy.of("10.0.0.2", 70000) }
    }

    @Test
    fun `refuses an unbracketed IPv6 address rather than splitting it`() {
        // Split on its last colon, this used to become host "fe80:" on port 1.
        assertEquals(
            "'fe80::1' looks like an IPv6 address. Wrap it in brackets and add the port, for example [::1]:8888.",
            assertThrows(IllegalArgumentException::class.java) { HttpProxy.fromInput("fe80::1") }.message,
        )
        assertThrows(IllegalArgumentException::class.java) { HttpProxy.fromInput("fe80::1:8888") }
    }

    @Test
    fun `accepts a bracketed IPv6 address with a port`() {
        assertEquals(HttpProxy("[::1]", 8888), HttpProxy.fromInput("[::1]:8888"))
        assertEquals(HttpProxy("[fe80::1]", 8080), HttpProxy.of("[fe80::1]", 8080))
    }

    @Test
    fun `a bracketed address without a port is not host and port`() {
        assertEquals(
            "'[::1]' is not host:port. Enter both parts, for example 192.168.1.10:8888.",
            assertThrows(IllegalArgumentException::class.java) { HttpProxy.fromInput("[::1]") }.message,
        )
    }

    @Test
    fun `a host supplied separately cannot carry a bare colon either`() {
        // What an agent's separate host argument goes through, so it gets the same protection.
        assertEquals(
            "Proxy host 'fe80::1' contains a colon. Wrap an IPv6 address in brackets, for example [::1].",
            assertThrows(IllegalArgumentException::class.java) { HttpProxy.of("fe80::1", 8888) }.message,
        )
        assertThrows(IllegalArgumentException::class.java) { HttpProxy.of("fe80:", 1) }
    }

    @Test
    fun `describes the device state without guessing`() {
        assertEquals("Active proxy: None", HttpProxy.describeDevice(Result.success(null)))
        assertEquals(
            "Active proxy: 10.0.0.2:8888",
            HttpProxy.describeDevice(Result.success(HttpProxy("10.0.0.2", 8888))),
        )
        assertEquals(
            "Active proxy: unknown",
            HttpProxy.describeDevice(Result.failure(IllegalStateException("device went away"))),
            "a failed read must not be shown as a direct connection",
        )
    }

    @Test
    fun `a write that took is reported as such`() {
        val proxy = HttpProxy("10.0.0.2", 8888)

        val set = HttpProxyWrite(requested = proxy, applied = proxy)
        assertTrue(set.took)
        assertEquals("HTTP proxy set to 10.0.0.2:8888.", set.message)

        val cleared = HttpProxyWrite(requested = null, applied = null)
        assertTrue(cleared.took)
        assertEquals("HTTP proxy cleared; the device connects directly.", cleared.message)
    }

    @Test
    fun `a write that did not take names what the device holds`() {
        val proxy = HttpProxy("10.0.0.2", 8888)

        val ignored = HttpProxyWrite(requested = proxy, applied = null)
        assertFalse(ignored.took)
        assertEquals(
            "Asked the device for 10.0.0.2:8888 but it reports no proxy. The setting did not stick.",
            ignored.message,
        )

        val stuck = HttpProxyWrite(requested = null, applied = proxy)
        assertFalse(stuck.took, "a clear that leaves a proxy behind is not a success")
        assertEquals("Cleared the proxy but the device still reports 10.0.0.2:8888.", stuck.message)
    }
}
