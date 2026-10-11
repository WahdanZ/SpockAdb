package spock.adb.mcp

import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.SetAnimationsTool
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `android_set_animations` against a device that keeps its global settings, or refuses some of
 * them. The tool is a safe action, so what it reports is all an agent has to go on.
 */
class AnimationsToolTest {

    private class FakeDevice(
        val settings: MutableMap<String, String> = mutableMapOf(
            WINDOW to "1.0",
            TRANSITION to "1.0",
            DURATION to "1.0",
        ),
        private val refused: Set<String> = emptySet(),
        /** Settings whose every shell call throws, as a device dropping off mid-call does. */
        private val unresponsive: Set<String> = emptySet(),
    ) {
        val target = FakeToolContext.device("emulator-5554")
        val context = FakeToolContext(available = listOf(target))

        init {
            val command = slot<String>()
            val receiver = slot<IShellOutputReceiver>()
            every {
                target.device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
            } answers {
                val words = command.captured.split(' ')
                if (unresponsive.any { command.captured.contains(it) }) {
                    throw IOException("device 'emulator-5554' not found")
                }
                val out = when {
                    command.captured.startsWith("settings get global ") -> settings[words.last()] ?: "null"
                    command.captured.startsWith("settings put global ") -> {
                        if (words[3] !in refused) settings[words[3]] = words[4].trim('\'')
                        ""
                    }
                    else -> ""
                }
                val bytes = "$out\r\n".toByteArray()
                receiver.captured.addOutput(bytes, 0, bytes.size)
                receiver.captured.flush()
            }
        }

        fun run(arguments: String) = SetAnimationsTool().execute(
            JsonParser.parseString(arguments).asJsonObject,
            context,
        )

        fun verifyNoPut() = verify(exactly = 0) {
            target.device.executeShellCommand(match { it.startsWith("settings put") }, any(), any(), any<TimeUnit>())
        }
    }

    @Test
    fun `scale 0 turns all three off and says how to restore them`() {
        val fake = FakeDevice()

        val result = fake.run("""{"scale":"0"}""")

        assertFalse(result.isError, result.text())
        assertEquals(mapOf(WINDOW to "0.0", TRANSITION to "0.0", DURATION to "0.0"), fake.settings)
        listOf(WINDOW, TRANSITION, DURATION).forEach {
            assertTrue(result.text().contains("$it: 1× → Off"), result.text())
        }
        assertTrue(result.text().contains("android_set_animations with scale 1"), result.text())
        assertTrue(fake.context.confirmations.isEmpty())
    }

    @Test
    fun `a JSON number is accepted`() {
        val fake = FakeDevice()

        val result = fake.run("""{"scale":0.5}""")

        assertFalse(result.isError, result.text())
        assertEquals("0.5", fake.settings[WINDOW])
        assertTrue(fake.context.confirmations.isEmpty())
    }

    @Test
    fun `a scale not offered is refused before the device is touched`() {
        val fake = FakeDevice()

        val result = fake.run("""{"scale":"3"}""")

        assertTrue(result.isError)
        assertTrue(result.text().contains("0, 0.5, 1, 1.5, 2, 5, 10"), result.text())
        fake.verifyNoPut()
        assertTrue(fake.context.confirmations.isEmpty())
    }

    @Test
    fun `a scale the device did not keep is an error naming it`() {
        val fake = FakeDevice(refused = setOf(TRANSITION))

        val result = fake.run("""{"scale":"0"}""")

        assertTrue(result.isError, "an untaken write is never a success")
        assertTrue(result.text().contains("$TRANSITION: 1× → 1×"), result.text())
        assertTrue(result.text().contains("Transition animation scale is still 1×"), result.text())
        assertTrue(fake.context.confirmations.isEmpty())
    }

    @Test
    fun `previous values that differed cannot be restored with one call`() {
        val fake = FakeDevice(settings = mutableMapOf(WINDOW to "0.5", TRANSITION to "1.0", DURATION to "1.0"))

        val result = fake.run("""{"scale":"0"}""")

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("previous values differed"), result.text())
        assertTrue(result.text().contains("$WINDOW: 0.5× → Off"), result.text())
        assertFalse(result.text().contains("To restore:"), result.text())
    }

    @Test
    fun `settings never written restore as 1`() {
        val fake = FakeDevice(settings = mutableMapOf(WINDOW to "1"))

        val result = fake.run("""{"scale":"0"}""")

        assertTrue(result.text().contains("To restore: android_set_animations with scale 1."), result.text())
    }

    @Test
    fun `a failure part-way still reports the scale already changed and how to restore it`() {
        val fake = FakeDevice(unresponsive = setOf(TRANSITION))

        val result = fake.run("""{"scale":"0"}""")

        assertTrue(result.isError, result.text())
        assertEquals("0.0", fake.settings[WINDOW], "the first write did happen")
        assertEquals("1.0", fake.settings[DURATION], "nothing is tried after the failure")
        assertTrue(result.text().contains("$WINDOW: 1× → Off"), result.text())
        assertTrue(result.text().contains("Transition animation scale could not be set"), result.text())
        assertTrue(result.text().contains("not found"), "the cause is carried through: ${result.text()}")
        assertTrue(result.text().contains("android_set_animations with scale 1"), result.text())
        assertTrue(fake.context.confirmations.isEmpty())
    }

    @Test
    fun `a failure on the first scale says nothing was changed`() {
        val fake = FakeDevice(unresponsive = setOf(WINDOW))

        val result = fake.run("""{"scale":"0"}""")

        assertTrue(result.isError)
        assertTrue(result.text().contains("Nothing was changed before it."), result.text())
    }

    @Test
    fun `it is registered as a safe action`() {
        assertEquals(ToolSafety.SAFE_ACTION, ToolRegistry.find("android_set_animations")!!.safety)
    }

    private companion object {
        const val WINDOW = "window_animation_scale"
        const val TRANSITION = "transition_animation_scale"
        const val DURATION = "animator_duration_scale"
    }
}
