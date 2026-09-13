package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
}
