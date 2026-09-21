package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.command.AmStartResult.Outcome

/**
 * A deep link either opened or it did not, and only the device knows which. These pin the
 * command that asks — `-W`, so the component comes back — and the verdict read out of the
 * answer: the two failures that never print the word "Error", the success that looks like a
 * warning, and the URI text the device echoes back, which the caller wrote and so cannot be
 * allowed to decide anything.
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
        assertEquals(
            "am start -W -a android.intent.action.VIEW -d 'myapp://x?q=\$(id)' 2>&1",
            AmStart.command("myapp://x?q=\$(id)"),
        )
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
    fun `Status ok without an activity still reports the link as opened`() {
        val result = AmStartResult.parse(uri, "Starting: Intent { dat=myapp://product/42 }\nStatus: ok")

        assertEquals(Outcome.STARTED, result.outcome)
        assertEquals("Opened myapp://product/42.", result.message)
    }

    @Test
    fun `the DOS line endings adb uses do not end up in the component`() {
        val result = AmStartResult.parse(uri, "Status: ok\r\nActivity: com.example/.Main\r\n")

        assertEquals(Outcome.STARTED, result.outcome)
        assertEquals("com.example/.Main", result.component)
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
    fun `an unresolved intent scoped to a package says the app may not be installed`() {
        val result = AmStartResult.parse(
            uri,
            "Starting: Intent { dat=myapp://product/42 }\n" +
                "Error: Activity not started, unable to resolve Intent { }",
            packageName = "com.example.app",
        )

        assertEquals(Outcome.NOT_RESOLVED, result.outcome)
        assertTrue(result.message.contains("com.example.app"), result.message)
        assertTrue(result.message.contains("installed"), result.message)
    }

    @Test
    fun `a generic am error is a device error`() {
        val result = AmStartResult.parse(
            uri,
            "Starting: Intent { dat=myapp://product/42 }\n" +
                "Error: Activity class {com.example/.Gone} does not exist.",
        )

        assertEquals(Outcome.DEVICE_ERROR, result.outcome)
        assertFalse(result.succeeded)
        assertTrue(result.message.contains("does not exist"), result.message)
    }

    @Test
    fun `a thrown exception is a device error even though am never says Error`() {
        listOf(
            "Exception occurred while executing 'start':\njava.lang.IllegalArgumentException: Unknown URI",
            "java.lang.NullPointerException: at com.android.server.am.ActivityManagerShellCommand",
            "Error type 3\nError: Activity class does not exist",
        ).forEach { raw ->
            val result = AmStartResult.parse(uri, "Starting: Intent { dat=myapp://product/42 }\n$raw")

            assertEquals(Outcome.DEVICE_ERROR, result.outcome, raw)
            assertFalse(result.succeeded, raw)
        }
    }

    @Test
    fun `a permission denial is a failure even though it never says Error`() {
        // Deliberately contains no line starting `Error:` — this is the shape the old
        // contains("Error") heuristic reported as opened.
        val result = AmStartResult.parse(uri, PERMISSION_DENIAL)

        assertEquals(Outcome.PERMISSION_DENIED, result.outcome)
        assertFalse(result.succeeded)
        assertTrue(result.message.contains("exported"), result.message)
        // The device's own words, so the developer can tell a refusal from a typo.
        assertTrue(result.message.contains("Permission Denial"), result.message)
    }

    @Test
    fun `a uri that says SecurityException does not fake a refusal`() {
        // The Starting: line is the device echoing back text the caller wrote.
        val result = AmStartResult.parse(
            "myapp://help/SecurityException",
            """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://help/SecurityException }
            Status: ok
            Activity: com.example/.HelpActivity
            """.trimIndent(),
        )

        assertEquals(Outcome.STARTED, result.outcome)
        assertTrue(result.succeeded)
    }

    @Test
    fun `a newline in the uri cannot forge a verdict`() {
        val spoof = "myapp://x\nError: Activity not started, unable to resolve Intent { }"
        val result = AmStartResult.parse(
            spoof,
            "Starting: Intent { act=android.intent.action.VIEW dat=myapp://x\n" +
                "Error: Activity not started, unable to resolve Intent { } }\n" +
                "Status: ok\n" +
                "Activity: com.example/.MainActivity",
        )

        assertEquals(Outcome.STARTED, result.outcome, "the echoed intent must not decide the outcome")
        assertTrue(result.succeeded)
    }

    @Test
    fun `brought to the front is a success, and says the link may not have been delivered`() {
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
        // The task is resumed without onNewIntent, so "Opened" would be a second false report.
        assertTrue(result.message.contains("may not have been delivered"), result.message)
    }

    @Test
    fun `Status timeout is unconfirmed, not a refusal`() {
        val result = AmStartResult.parse(
            uri,
            """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Status: timeout
            """.trimIndent(),
        )

        assertEquals(Outcome.WAIT_TIMED_OUT, result.outcome)
        assertTrue(result.succeeded, "am gave up waiting; the activity almost certainly started")
        assertTrue(result.message.contains("stopped waiting"), result.message)
        assertFalse(result.message.contains("refused"), result.message)
    }

    @Test
    fun `an unknown Status value is a device error`() {
        val result = AmStartResult.parse(uri, "Status: something-new")

        assertEquals(Outcome.DEVICE_ERROR, result.outcome)
        assertFalse(result.succeeded)
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
    fun `output cut short by a slow launch is unconfirmed, not an error`() {
        // What the ddmlib timeout leaves behind when `am -W` is still waiting.
        val result = AmStartResult.parse(uri, "Starting: Intent { act=android.intent.action.VIEW }")

        assertEquals(Outcome.UNRECOGNISED, result.outcome)
        assertTrue(result.succeeded)
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

    private companion object {
        val PERMISSION_DENIAL = """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Security exception: Permission Denial: starting Intent { cmp=com.example/.DeepLinkActivity }
             from null (pid=9123, uid=2000) not exported from uid 10234
            java.lang.SecurityException: Permission Denial: starting Intent
        """.trimIndent()
    }
}
