package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.command.AmStartResult.Outcome

/**
 * A deep link either opened or it did not, and only the device knows which. These pin the
 * command that asks — `-W`, so the component comes back — and the verdict read out of the
 * answer, including the two failures that never print the word "Error" and the success that
 * looks like a warning.
 */
class AmStartTest {

    private val uri = "myapp://product/42"

    @Test
    fun `command names -W and quotes the uri`() {
        assertEquals(
            "am start -W -a android.intent.action.VIEW -d 'myapp://product/42' 2>&1",
            AmStart.command(uri),
        )
    }

    @Test
    fun `a uri containing shell metacharacters is quoted, not interpolated`() {
        // Double quotes do not suppress command substitution: this used to run `id` on the device.
        val command = AmStart.command("myapp://x?q=\$(id)")

        assertEquals(
            "am start -W -a android.intent.action.VIEW -d 'myapp://x?q=\$(id)' 2>&1",
            command,
        )
        assertTrue(command.contains("'myapp://x?q=" + '$' + "(id)'"), command)
    }

    @Test
    fun `a package scopes the intent`() {
        assertTrue(AmStart.command(uri, "com.example.app").contains(" -p 'com.example.app'"))
        assertFalse(AmStart.command(uri).contains(" -p "))
    }

    @Test
    fun `Status ok is a success and names the activity`() {
        val result = AmStartResult.parse(
            uri,
            """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Status: ok
            Activity: com.example/.DeepLinkActivity
            TotalTime: 214
            """.trimIndent(),
        )

        assertEquals(Outcome.STARTED, result.outcome)
        assertEquals("com.example/.DeepLinkActivity", result.component)
        assertTrue(result.succeeded)
        assertTrue(result.message.contains("com.example/.DeepLinkActivity"), result.message)
    }

    @Test
    fun `an unresolved intent is a failure`() {
        val result = AmStartResult.parse(
            uri,
            """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW }
            """.trimIndent(),
        )

        assertEquals(Outcome.NOT_RESOLVED, result.outcome)
        assertFalse(result.succeeded)
        assertTrue(result.message.contains(uri), result.message)
    }

    @Test
    fun `a permission denial is a failure even though it never says Error`() {
        val raw = """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Security exception: Permission Denial: starting Intent { cmp=com.example/.DeepLinkActivity }
             from null (pid=9123, uid=2000) not exported from uid 10234
            java.lang.SecurityException: Permission Denial: starting Intent
        """.trimIndent()

        // Why the old contains("Error") heuristic reported this as opened.
        assertTrue(raw.lines().none { it.trim().startsWith("Error:") }, raw)

        val result = AmStartResult.parse(uri, raw)

        assertEquals(Outcome.PERMISSION_DENIED, result.outcome)
        assertFalse(result.succeeded)
        assertTrue(result.message.contains("exported"), result.message)
    }

    @Test
    fun `brought to the front is a success, not a failure`() {
        val result = AmStartResult.parse(
            uri,
            """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Warning: Activity not started, its current task has been brought to the front
            Status: ok
            Activity: com.example/.DeepLinkActivity
            """.trimIndent(),
        )

        assertEquals(Outcome.BROUGHT_TO_FRONT, result.outcome)
        assertTrue(result.succeeded, result.message)
        assertTrue(result.message.contains("foreground"), result.message)
    }

    @Test
    fun `Status timeout is a device error`() {
        val result = AmStartResult.parse(
            uri,
            """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Status: timeout
            """.trimIndent(),
        )

        assertEquals(Outcome.DEVICE_ERROR, result.outcome)
        assertFalse(result.succeeded)
        assertTrue(result.message.contains("Status: timeout"), result.message)
    }

    @Test
    fun `unrecognised output is reported as unconfirmed, never as failure`() {
        val result = AmStartResult.parse(uri, "some OEM wording nobody has seen")

        assertEquals(Outcome.UNRECOGNISED, result.outcome)
        assertTrue(result.succeeded, "unknown wording must not be reported as a failure")
        assertTrue(result.message.contains("did not say"), result.message)
        assertFalse(result.message.contains("Opened"), result.message)
        assertTrue(result.message.contains("some OEM wording nobody has seen"), result.message)
    }

    @Test
    fun `empty output is unconfirmed`() {
        val result = AmStartResult.parse(uri, "")

        assertEquals(Outcome.UNRECOGNISED, result.outcome)
        assertTrue(result.succeeded)
        assertTrue(result.message.contains(uri), result.message)
    }

    @Test
    fun `every outcome produces a non-blank message naming the uri`() {
        Outcome.entries.forEach { outcome ->
            listOf("", "Status: ok\nActivity: com.example/.Main").forEach { raw ->
                val message = AmStartResult(uri, outcome, component = null, raw = raw).message

                assertTrue(message.isNotBlank(), "$outcome produced a blank message")
                assertTrue(message.contains(uri), "$outcome does not name the uri: $message")
            }
        }
    }
}
