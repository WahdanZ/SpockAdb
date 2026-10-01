package spock.adb.flutter.dtd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DtdUriTest {

    @Test
    fun `the registry's address is kept as written, with no ws suffix added`() {
        val uri = DtdUri.parse("ws://127.0.0.1:53295/DtDsEcReT_xyz=")
        assertEquals("ws://127.0.0.1:53295/DtDsEcReT_xyz=", uri.webSocketUri.toString())
        assertEquals(53295, uri.port)
        assertFalse(uri.secure)
    }

    @Test
    fun `toString and redacted never carry the secret, padded or not`() {
        listOf("DtDsEcReT_xyz=", "plainSecretNoPadding").forEach { secret ->
            val uri = DtdUri.parse("ws://127.0.0.1:53295/$secret")
            assertEquals("ws://127.0.0.1:53295/<redacted>", uri.toString())
            assertEquals(uri.toString(), uri.redacted())
            assertFalse(uri.toString().contains(secret))
        }
    }

    @Test
    fun `scrub takes the secret out by value, and VM Service tokens besides`() {
        val uri = DtdUri.parse("ws://127.0.0.1:53295/plainSecretNoPadding")
        val text = "failed at ws://127.0.0.1:53295/plainSecretNoPadding and ws://127.0.0.1:1/VmToKeN_123=/ws"
        val scrubbed = uri.scrub(text)
        assertFalse(scrubbed.contains("plainSecretNoPadding"))
        assertFalse(scrubbed.contains("VmToKeN_123="))
    }

    @Test
    fun `loopback only`() {
        assertThrows<IllegalArgumentException> { DtdUri.parse("ws://192.168.1.5:53295/DtDsEcReT_xyz=") }
        assertThrows<IllegalArgumentException> { DtdUri.parse("ws://example.com:53295/DtDsEcReT_xyz=") }
        assertTrue(DtdUri.parse("ws://localhost:1/s=").host == "localhost")
        assertEquals("ws://[::1]:1/<redacted>", DtdUri.parse("ws://[::1]:1/s=").toString())
    }

    @Test
    fun `not a WebSocket address, no port, or unreadable`() {
        assertNull(DtdUri.parseOrNull("http://127.0.0.1:53295/DtDsEcReT_xyz="))
        assertNull(DtdUri.parseOrNull("ws://127.0.0.1/DtDsEcReT_xyz="))
        assertNull(DtdUri.parseOrNull("ws://127.0.0.1:53295/a?b=c"))
        assertNull(DtdUri.parseOrNull("not a uri at all ^^"))
    }

    @Test
    fun `a refusal never quotes the secret`() {
        listOf(
            "http://127.0.0.1:53295/plainSecretNoPadding",
            "ws://10.0.0.1:53295/plainSecretNoPadding",
            "ws://127.0.0.1:53295/plainSecretNoPadding^^",
        ).forEach { text ->
            val error = assertThrows<IllegalArgumentException> { DtdUri.parse(text) }
            assertFalse(error.message.orEmpty().contains("plainSecretNoPadding"), error.message)
        }
    }
}
