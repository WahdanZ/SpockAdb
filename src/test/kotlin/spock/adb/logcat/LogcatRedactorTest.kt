package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatRedactorTest {

    @Test
    fun `an authorization header loses its value`() {
        val result = LogcatRedactor.redact("12:00:00.000 I/OkHttp: Authorization: Bearer EXAMPLE-NOT-A-REAL-TOKEN")

        assertFalse(result.text.contains("EXAMPLE-NOT-A-REAL-TOKEN"))
        assertTrue(result.text.contains(LogcatRedactor.PLACEHOLDER))
        assertTrue(result.count >= 1)
    }

    @Test
    fun `a cookie header loses its value`() {
        val result = LogcatRedactor.redact("Set-Cookie: session=EXAMPLE-NOT-REAL; Path=/; HttpOnly")

        assertFalse(result.text.contains("EXAMPLE-NOT-REAL"))
    }

    @Test
    fun `a JWT is redacted wherever it appears`() {
        val jwt = "eyJhbGciOiJub25lIn0.eyJub3RlIjoiZXhhbXBsZS1vbmx5In0.not-a-real-signature"
        val result = LogcatRedactor.redact("Response body: {\"id_token\":\"$jwt\"}")

        assertFalse(result.text.contains(jwt))
    }

    @Test
    fun `key-shaped assignments are redacted`() {
        val result = LogcatRedactor.redact("api_key=EXAMPLE-KEY password=EXAMPLE-PASSWORD sessionId: EXAMPLE-SESSION")

        assertFalse(result.text.contains("EXAMPLE-KEY"))
        assertFalse(result.text.contains("EXAMPLE-PASSWORD"))
        assertFalse(result.text.contains("EXAMPLE-SESSION"))
        assertEquals(3, result.count)
    }

    @Test
    fun `credentials in a URL are redacted`() {
        val result = LogcatRedactor.redact("connecting to https://admin:EXAMPLE-PASSWORD@internal.example.com/api")

        assertFalse(result.text.contains("EXAMPLE-PASSWORD"))
        assertTrue(result.text.contains("internal.example.com"))
    }

    @Test
    fun `ordinary log text is left alone`() {
        val line = "12:00:00.000  1234  1250 I MainActivity: Screen opened: Offers (12 results)"

        val result = LogcatRedactor.redact(line)

        assertEquals(line, result.text)
        assertEquals(0, result.count)
    }
}
