package spock.adb.flutter.vmservice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VmServiceUriTest {

    private val expected = "ws://127.0.0.1:50300/HXKQJZK_Rkw=/ws"

    private fun ws(text: String) = VmServiceUri.parse(text).webSocketUri.toString()

    @Test
    fun `flutter run's http form, with and without the trailing slash`() {
        assertEquals(expected, ws("http://127.0.0.1:50300/HXKQJZK_Rkw=/"))
        assertEquals(expected, ws("http://127.0.0.1:50300/HXKQJZK_Rkw="))
    }

    @Test
    fun `the WebSocket form is kept as it is`() {
        assertEquals(expected, ws("ws://127.0.0.1:50300/HXKQJZK_Rkw=/ws"))
    }

    @Test
    fun `https and wss become wss`() {
        assertEquals("wss://127.0.0.1:50300/HXKQJZK_Rkw=/ws", ws("https://127.0.0.1:50300/HXKQJZK_Rkw=/"))
        assertEquals("wss://127.0.0.1:50300/HXKQJZK_Rkw=/ws", ws("wss://127.0.0.1:50300/HXKQJZK_Rkw=/ws"))
        assertTrue(VmServiceUri.parse("wss://127.0.0.1:50300/HXKQJZK_Rkw=/ws").secure)
    }

    @Test
    fun `a DevTools link carries the address in the fragment's uri parameter`() {
        val link = "http://127.0.0.1:9100/#/?uri=ws%3A%2F%2F127.0.0.1%3A50300%2FHXKQJZK_Rkw%3D%2Fws&theme=dark"
        assertEquals(expected, ws(link))
    }

    @Test
    fun `a DevTools link with the uri in the query, encoded or raw as flutter run prints it`() {
        assertEquals(expected, ws("http://127.0.0.1:9100/?uri=http%3A%2F%2F127.0.0.1%3A50300%2FHXKQJZK_Rkw%3D%2F"))
        assertEquals(expected, ws("http://127.0.0.1:9100?uri=http://127.0.0.1:50300/HXKQJZK_Rkw=/"))
    }

    @Test
    fun `the address is found inside a console or logcat line`() {
        assertEquals(
            expected,
            ws("A Dart VM Service on sdk gphone64 arm64 is available at: http://127.0.0.1:50300/HXKQJZK_Rkw=/"),
        )
        assertEquals(
            expected,
            ws(
                "10-01 10:00:01.500  5021  5045 I flutter : " +
                    "The Dart VM service is listening on http://127.0.0.1:50300/HXKQJZK_Rkw=/",
            ),
        )
    }

    @Test
    fun `of several URLs on a line, the one with an auth code wins`() {
        assertEquals(
            expected,
            ws(
                "The Flutter DevTools debugger and profiler is available at: http://127.0.0.1:9100/ " +
                    "and the VM Service at http://127.0.0.1:50300/HXKQJZK_Rkw=/",
            ),
        )
        assertEquals(expected, ws("https://pub.dev/ then http://127.0.0.1:50300/HXKQJZK_Rkw=/"))
    }

    @Test
    fun `devtools is a page, not an auth code`() {
        assertEquals("ws://127.0.0.1:50300/ws", ws("http://127.0.0.1:50300/devtools/"))
    }

    @Test
    fun `a uri parameter is percent-decoded, twice if need be, and a plus stays a plus`() {
        assertEquals(expected, ws("http://127.0.0.1:9100/?uri=ws%253A%252F%252F127.0.0.1%253A50300%252FHXKQJZK_Rkw%253D%252Fws"))
        assertEquals("a+b=/ü%zz%4", VmServiceUri.decodePercent("a+b%3D%2F%C3%BC%zz%4"))
    }

    @Test
    fun `an address refused for its form never echoes its path`() {
        listOf("ftp://10.0.0.2:21/HXKQJZK_Rkw=/", "http://127.0.0.1:50300/HXKQ.JZK=/", "http://127.0.0.1/HXKQJZK_Rkw=/")
            .forEach { address ->
                val error = assertThrows<IllegalArgumentException>(address) { VmServiceUri.parse(address) }
                assertFalse(error.message!!.contains("HXKQ"), error.message)
            }
    }

    @Test
    fun `IPv6 loopback and localhost are loopback too`() {
        assertEquals("ws://[::1]:50300/HXKQJZK_Rkw=/ws", ws("http://[::1]:50300/HXKQJZK_Rkw=/"))
        assertEquals("ws://localhost:50300/HXKQJZK_Rkw=/ws", ws("http://LOCALHOST:50300/HXKQJZK_Rkw=/"))
    }

    @Test
    fun `an address with auth codes disabled has no token segment`() {
        assertEquals("ws://127.0.0.1:50300/ws", ws("http://127.0.0.1:50300/"))
        assertEquals("ws://127.0.0.1:50300/ws", ws("ws://127.0.0.1:50300/ws"))
    }

    @Test
    fun `anything off loopback is refused, saying why, without the token`() {
        listOf(
            "http://10.0.2.15:50300/HXKQJZK_Rkw=/",
            "http://192.168.1.20:50300/HXKQJZK_Rkw=/",
            "ws://example.com:50300/HXKQJZK_Rkw=/ws",
            "http://0.0.0.0:50300/HXKQJZK_Rkw=/",
            "http://127.0.0.1:9100/#/?uri=ws%3A%2F%2F10.0.0.2%3A50300%2FHXKQJZK_Rkw%3D%2Fws",
        ).forEach { address ->
            val error = assertThrows<IllegalArgumentException>(address) { VmServiceUri.parse(address) }
            assertTrue(error.message!!.contains("only 127.0.0.1, ::1 or localhost"), error.message)
            assertFalse(error.message!!.contains("HXKQJZK"), error.message)
        }
    }

    @Test
    fun `no port, no address, or a path that is not a token is not accepted`() {
        assertThrows<IllegalArgumentException> { VmServiceUri.parse("http://127.0.0.1/HXKQJZK_Rkw=/") }
        assertThrows<IllegalArgumentException> { VmServiceUri.parse("just some text") }
        assertThrows<IllegalArgumentException> { VmServiceUri.parse("ftp://127.0.0.1:21/x=/") }
        assertThrows<IllegalArgumentException> { VmServiceUri.parse("http://127.0.0.1:50300/not.a.token/") }
        assertNull(VmServiceUri.parseOrNull("http://10.0.2.15:50300/HXKQJZK_Rkw=/"))
    }

    @Test
    fun `printing an address never shows the token`() {
        val uri = VmServiceUri.parse("http://127.0.0.1:50300/HXKQJZK_Rkw=/")
        assertEquals("ws://127.0.0.1:50300/<redacted>/ws", uri.redacted())
        assertEquals(uri.redacted(), uri.toString())
        assertEquals("ws://127.0.0.1:50300/<redacted>/ws", "$uri")
    }

    @Test
    fun `a forwarded port keeps the token and moves to IPv4 loopback`() {
        val device = VmServiceUri.parse("http://127.0.0.1:43181/AbCdEfGh123=/")
        val forwarded = device.withPort(50_001)
        assertEquals("ws://127.0.0.1:50001/AbCdEfGh123=/ws", forwarded.webSocketUri.toString())
        assertEquals(VmServiceUri.parse("ws://127.0.0.1:50001/AbCdEfGh123=/ws"), forwarded)
    }
}
