package spock.adb.context

import com.android.ddmlib.IDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Reading Android Studio's run target without depending on the class it lives in. */
class StudioRunTargetTest {

    /** Shaped like Android Studio's AndroidExecutionTarget, which is only reached by method name. */
    class FakeAndroidTarget(private val devices: Collection<IDevice>) {
        fun getRunningDevices(): Collection<IDevice> = devices
    }

    private fun device(serial: String) = mockk<IDevice> { every { serialNumber } returns serial }

    @Test
    fun `the running devices of an Android target are read by name`() {
        val target = FakeAndroidTarget(listOf(device("emulator-5554"), device("R58M")))
        assertEquals(listOf("emulator-5554", "R58M"), StudioRunTarget.serialsOf(target))
    }

    @Test
    fun `a target with no such method names no devices`() {
        assertEquals(emptyList<String>(), StudioRunTarget.serialsOf(Any()))
    }

    @Test
    fun `a chosen device that is not booted names none`() {
        assertEquals(emptyList<String>(), StudioRunTarget.serialsOf(FakeAndroidTarget(emptyList())))
    }
}
