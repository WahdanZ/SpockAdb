package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Telling a `pm grant` that worked from one that did not.
 *
 * Neither prints anything on success, neither sets an exit status the shell reports, and the
 * plugin discarded the output — so a permission the app had never requested, or one granted at
 * install and unchangeable, was announced as granted.
 */
class PermissionChangeTest {

    @Test
    fun `silence is success`() {
        assertNull(PermissionChange.failureOf(""))
        assertNull(PermissionChange.failureOf("\n  \n"))
    }

    @Test
    fun `a permission the app never asked for is named as the reason`() {
        val output = """
            Exception occurred while executing 'grant':
            java.lang.SecurityException: Package com.example.app has not requested permission android.permission.CAMERA
        """.trimIndent()

        assertEquals(
            "Package com.example.app has not requested permission android.permission.CAMERA",
            PermissionChange.failureOf(output),
            "the exception class is noise; the sentence after it is the answer",
        )
    }

    @Test
    fun `an unchangeable permission says why`() {
        val output = """
            Exception occurred while executing 'grant':
            java.lang.SecurityException: Permission android.permission.INTERNET requested by com.android.chrome is not a changeable permission type
        """.trimIndent()

        val failure = PermissionChange.failureOf(output)

        assertTrue(failure!!.startsWith("Permission android.permission.INTERNET"), failure)
        assertTrue(failure.endsWith("is not a changeable permission type"), failure)
    }

    @Test
    fun `output it does not recognise is still a failure, reported as it came`() {
        assertEquals("Operation not allowed", PermissionChange.failureOf("Operation not allowed"))
    }
}
