package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatRedactorTest {

    @Test
    fun `an authorization header loses its value`() {
        val result = LogcatRedactor.redact("12:00:00.000 I/OkHttp: Authorization: Bearer abcdef1234567890")

        assertFalse(result.text.contains("abcdef1234567890"))
        assertTrue(result.text.contains(LogcatRedactor.PLACEHOLDER))
        assertTrue(result.count >= 1)
    }

    @Test
    fun `a cookie header loses its value`() {
        val result = LogcatRedactor.redact("Set-Cookie: session=9f8a7b6c5d; Path=/; HttpOnly")

        assertFalse(result.text.contains("9f8a7b6c5d"))
    }

    @Test
    fun `a JWT is redacted wherever it appears`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.dBjftJeZ4CVPmB92K27uhbUJU1p1r"
        val result = LogcatRedactor.redact("Response body: {\"id_token\":\"$jwt\"}")

        assertFalse(result.text.contains(jwt))
    }

    @Test
    fun `key-shaped assignments are redacted`() {
        val result = LogcatRedactor.redact("api_key=A1B2C3D4E5 password=hunter2 sessionId: 55AA77BB")

        assertFalse(result.text.contains("A1B2C3D4E5"))
        assertFalse(result.text.contains("hunter2"))
        assertFalse(result.text.contains("55AA77BB"))
        assertEquals(3, result.count)
    }

    @Test
    fun `credentials in a URL are redacted`() {
        val result = LogcatRedactor.redact("connecting to https://admin:s3cr3t@internal.example.com/api")

        assertFalse(result.text.contains("s3cr3t"))
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
