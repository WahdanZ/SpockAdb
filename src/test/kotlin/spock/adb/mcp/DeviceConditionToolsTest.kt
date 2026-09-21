package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spock.adb.backgroundwork.DeviceConditionsCloseListener
import spock.adb.command.DeviceCondition
import spock.adb.command.DeviceConditionTracker
import spock.adb.mcp.tools.ForceDozeTool
import spock.adb.mcp.tools.GetDeviceConditionsTool
import spock.adb.mcp.tools.ResetDeviceConditionsTool
import spock.adb.mcp.tools.SetStandbyBucketTool
import spock.adb.mcp.tools.UnplugBatteryTool
import java.util.concurrent.TimeUnit

/**
 * The device-condition tools against a fake device that keeps Doze, battery and bucket state the
 * way an API 34 emulator does — including refusing to move an app below [minimumBucket].
 */
class DeviceConditionToolsTest {

    private class FakeDevice(private val minimumBucket: Int = 10) {
        var deep = "ACTIVE"
        var batteryStopped = false
        var bucket = 10
        val sent = mutableListOf<String>()

        val context: FakeToolContext = run {
            val device = mockk<IDevice>(relaxed = true)
            every { device.serialNumber } returns SERIAL
            every { device.isOnline } returns true
            every { device.getProperty("ro.build.version.sdk") } returns "34"
            val command = slot<String>()
            val receiver = slot<IShellOutputReceiver>()
            every { device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>()) } answers {
                val text = command.captured
                sent += text
                val out = when {
                    text.startsWith("pm list packages") -> "package:$APP"
                    text == "dumpsys deviceidle force-idle" -> {
                        deep = "IDLE"
                        "Now forced in to deep idle mode"
                    }
                    text == "dumpsys deviceidle unforce" -> {
                        deep = "ACTIVE"
                        "Light state: ACTIVE, deep state: ACTIVE"
                    }
                    text == "dumpsys deviceidle get deep" -> deep
                    text == "dumpsys battery unplug" -> {
                        batteryStopped = true
                        ""
                    }
                    text == "dumpsys battery reset" -> {
                        batteryStopped = false
                        ""
                    }
                    text == "dumpsys battery" ->
                        "Current Battery Service state:\n" +
                            (if (batteryStopped) "  (UPDATES STOPPED -- use 'reset' to restart)\n" else "") +
                            "  AC powered: false\n  level: 100"
                    text.startsWith("am set-standby-bucket") -> {
                        val wanted = mapOf(
                            "active" to 10, "working_set" to 20, "frequent" to 30, "rare" to 40,
                            "restricted" to 45
                        ).getValue(text.substringAfterLast(' '))
                        // A higher number is a lower bucket; the device will not go below its minimum.
                        bucket = minOf(wanted, maxOf(minimumBucket, 10)).takeIf { minimumBucket > 10 } ?: wanted
                        ""
                    }
                    text.startsWith("am get-standby-bucket") -> bucket.toString()
                    else -> ""
                }
                val bytes = out.toByteArray()
                receiver.captured.addOutput(bytes, 0, bytes.size)
                Unit
            }
            FakeToolContext(
                available = listOf(FakeToolContext.device(SERIAL).copy(device = device)),
                applicationId = APP,
                confirmationAnswer = true,
            )
        }
    }

    @BeforeEach
    @AfterEach
    fun forget() = DeviceConditionTracker.resetForTests()

    @Test
    fun `forcing Doze asks first, then records it until reset`() {
        val device = FakeDevice()

        val result = ForceDozeTool().execute(JsonObject(), device.context)

        assertFalse(result.isError, result.text())
        assertEquals(listOf("android_force_doze"), device.context.confirmations)
        assertEquals("IDLE", device.deep)
        assertEquals(setOf(DeviceCondition.Doze, DeviceCondition.Battery), DeviceConditionTracker.conditions(SERIAL))

        val reset = ResetDeviceConditionsTool().execute(JsonObject(), device.context)
        assertFalse(reset.isError, reset.text())
        assertEquals("ACTIVE", device.deep)
        assertFalse(device.batteryStopped)
        assertTrue(DeviceConditionTracker.conditions(SERIAL).isEmpty())
    }

    @Test
    fun `a declined Doze never reaches the device`() {
        val device = FakeDevice()
        device.context.confirmationAnswer = false

        val result = ForceDozeTool().execute(JsonObject(), device.context)

        assertTrue(result.isError)
        assertTrue(device.sent.none { it.startsWith("dumpsys") }, device.sent.toString())
        assertTrue(DeviceConditionTracker.conditions(SERIAL).isEmpty())
    }

    @Test
    fun `a bucket is read back, and one the device refused is an error that says why`() {
        val moves = FakeDevice()
        val ok = SetStandbyBucketTool().execute(JsonObject().apply { addProperty("bucket", "rare") }, moves.context)
        assertFalse(ok.isError, ok.text())
        assertEquals(40, moves.bucket)
        assertEquals(setOf(DeviceCondition.Bucket(APP)), DeviceConditionTracker.conditions(SERIAL))

        DeviceConditionTracker.resetForTests()
        // Like an app holding USE_EXACT_ALARM on Android 12+: never below the working set.
        val clamped = FakeDevice(minimumBucket = 20)
        val refused = SetStandbyBucketTool().execute(
            JsonObject().apply { addProperty("bucket", "rare") },
            clamped.context
        )
        assertTrue(refused.isError)
        assertTrue(refused.text().contains("Android kept $APP in Working set rather than Rare"), refused.text())
        // Still recorded: the app was asked to move, and Reset has to put it back.
        assertEquals(setOf(DeviceCondition.Bucket(APP)), DeviceConditionTracker.conditions(SERIAL))
    }

    @Test
    fun `reset puts the buckets Spock moved back to active`() {
        val device = FakeDevice()
        SetStandbyBucketTool().execute(JsonObject().apply { addProperty("bucket", "restricted") }, device.context)
        UnplugBatteryTool().execute(JsonObject(), device.context)

        val reset = ResetDeviceConditionsTool().execute(JsonObject(), device.context)

        assertEquals(10, device.bucket)
        assertTrue(reset.text().contains("$APP set back to Active"), reset.text())
    }

    @Test
    fun `reading conditions drops what the device has already shed`() {
        val device = FakeDevice()
        ForceDozeTool().execute(JsonObject(), device.context)
        // A reboot, or a reset from a terminal.
        device.deep = "ACTIVE"
        device.batteryStopped = false

        val read = GetDeviceConditionsTool().execute(JsonObject(), device.context)

        assertTrue(read.text().contains("Changed by Spock and not reset: nothing."), read.text())
        assertTrue(DeviceConditionTracker.conditions(SERIAL).isEmpty())
    }

    @Test
    fun `closing resets every online device with a change, and only the last project closes it`() {
        val device = FakeDevice()
        ForceDozeTool().execute(JsonObject(), device.context)
        assertTrue(DeviceConditionTracker.hasOnlineChanges())

        assertFalse(DeviceConditionsCloseListener.isLastProject(openProjects = 2), "another project is still open")
        assertTrue(DeviceConditionsCloseListener.isLastProject(openProjects = 1))

        DeviceConditionTracker.resetAllOnline()
        assertEquals("ACTIVE", device.deep)
        assertFalse(device.batteryStopped)
        assertFalse(DeviceConditionTracker.hasOnlineChanges())
    }

    private companion object {
        const val SERIAL = "emulator-5554"
        const val APP = "com.example.app"
    }
}
