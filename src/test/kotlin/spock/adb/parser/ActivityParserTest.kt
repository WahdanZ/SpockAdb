package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ActivityParserTest {

    @Test
    fun `parses mResumedActivity line from pre-Android 13 devices`() {
        val output = "  mResumedActivity: ActivityRecord{a1b2c3 u0 com.example.app/.ui.MainActivity t42}"

        assertEquals("com.example.app.ui.MainActivity", ActivityParser.parseResumedActivity(output))
    }

    @Test
    fun `parses topResumedActivity line used on Android 13 and above`() {
        val output = "  topResumedActivity=ActivityRecord{9f8e7d u0 com.example.app/.HomeActivity t7}"

        assertEquals("com.example.app.HomeActivity", ActivityParser.parseResumedActivity(output))
    }

    @Test
    fun `resolves fully qualified activity names that are not package-relative`() {
        val output = "  mResumedActivity: ActivityRecord{1 u0 com.example.app/com.other.lib.SplashActivity t1}"

        assertEquals("com.other.lib.SplashActivity", ActivityParser.parseResumedActivity(output))
    }

    @Test
    fun `returns null when the device reports no resumed activity`() {
        assertNull(ActivityParser.parseResumedActivity(""))
        assertNull(ActivityParser.parseResumedActivity("mResumedActivity: null"))
    }
}
