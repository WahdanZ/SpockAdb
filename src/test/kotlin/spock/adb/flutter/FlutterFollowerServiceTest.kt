package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Which ddmlib client is the selected app's process, for the follower. */
class FlutterFollowerServiceTest {

    @Test
    fun `the app's main process is the app`() {
        assertEquals(APP, FlutterFollowerService.processName(description = APP, packageName = APP))
    }

    @Test
    fun `a secondary process is not, though ddmlib gives it the app's package`() {
        assertNotEquals(APP, FlutterFollowerService.processName(description = "$APP:bg", packageName = APP))
    }

    @Test
    fun `before the process name is known, the package stands in`() {
        assertEquals(APP, FlutterFollowerService.processName(description = " ", packageName = APP))
        assertEquals(APP, FlutterFollowerService.processName(description = null, packageName = APP))
        assertEquals(null, FlutterFollowerService.processName(description = null, packageName = ""))
    }

    private companion object {
        const val APP = "com.foo"
    }
}
