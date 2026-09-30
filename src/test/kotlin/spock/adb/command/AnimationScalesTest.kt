package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * The animation-scale writes, which used to announce a value as set without asking the device.
 * `settings put` exits 0 whether or not the value is kept, so the read-back is the whole point.
 */
class AnimationScalesTest {

    private val project: Project = mockk(relaxed = true)

    /** A device that keeps the global settings the way `settings` does, or ignores every write. */
    private class FakeSettingsDevice(
        initial: Map<String, String> = AnimationScale.entries.associate { it.key to "1.0" },
        /** A device that runs `settings put`, says nothing, and keeps nothing. */
        private val refuses: Boolean = false,
        /** What the device stores in place of a value, as some store "0" for "0.0". */
        private val stores: (String) -> String = { it },
    ) {
        val commands = mutableListOf<String>()
        val settings = initial.toMutableMap()
        val device: IDevice = mockk(relaxed = true)

        init {
            val command = slot<String>()
            val receiver = slot<IShellOutputReceiver>()
            every {
                device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
            } answers {
                commands += command.captured
                val bytes = "${reply(command.captured)}\r\n".toByteArray()
                receiver.captured.addOutput(bytes, 0, bytes.size)
                receiver.captured.flush()
            }
        }

        val puts: List<String> get() = commands.filter { it.startsWith("settings put") }

        private fun reply(command: String): String {
            val words = command.split(' ')
            return when {
                command.startsWith("settings get global ") -> settings[words.last()] ?: "null"
                command.startsWith("settings put global ") && !refuses -> {
                    settings[words[3]] = stores(words[4].trim('\''))
                    ""
                }
                else -> ""
            }
        }
    }

    @Test
    fun `a write sends the quoted value and reports what the device then holds`() {
        val fake = FakeSettingsDevice()

        val write = fake.device.setAnimationScale(AnimationScale.WINDOW, "0.0")

        assertEquals(listOf("settings put global window_animation_scale '0.0'"), fake.puts)
        assertEquals("1.0", write.before)
        assertEquals("0.0", write.after)
        assertTrue(write.took)
        assertTrue(write.message.contains("Off"), write.message)
    }

    @Test
    fun `a device that stores 0 for 0_0 has taken it`() {
        val fake = FakeSettingsDevice(stores = { it.toFloat().toInt().toString() })

        val write = fake.device.setAnimationScale(AnimationScale.TRANSITION, "0.0")

        assertEquals("0", write.after)
        assertTrue(write.took)
    }

    @Test
    fun `a device that keeps nothing is not reported as set`() {
        val fake = FakeSettingsDevice(refuses = true)

        val write = fake.device.setAnimationScale(AnimationScale.DURATION, "0.0")

        assertFalse(write.took)
        assertEquals(
            "Animator duration scale is still 1×: the device did not accept Off.",
            write.message,
        )
    }

    @Test
    fun `a setting never written is described as not set`() {
        val fake = FakeSettingsDevice(initial = emptyMap())

        val write = fake.device.setAnimationScale(AnimationScale.WINDOW, "0.5")

        assertEquals("null", write.before)
        assertEquals("not set (1×)", describeAnimationScale(write.before))
        assertEquals("not set (1×)", describeAnimationScale(""))
        assertEquals("0.5×", describeAnimationScale("0.5"))
    }

    @Test
    fun `all three are written in the order the settings screen lists them`() {
        val fake = FakeSettingsDevice()

        val writes = fake.device.setAllAnimationScales("0.0")

        assertEquals(
            listOf(
                "settings put global window_animation_scale '0.0'",
                "settings put global transition_animation_scale '0.0'",
                "settings put global animator_duration_scale '0.0'",
            ),
            fake.puts,
        )
        assertEquals(AnimationScale.entries, writes.map { it.scale })
        assertTrue(writes.all { it.took })
    }

    @Test
    fun `a scale the dropdowns do not offer is refused before the device is touched`() {
        val fake = FakeSettingsDevice()

        val thrown = assertThrows<IllegalArgumentException> {
            SetAllAnimationScalesCommand().execute("3", project, fake.device)
        }

        assertTrue(thrown.message!!.contains("10.0"), thrown.message)
        assertTrue(fake.puts.isEmpty(), fake.commands.toString())
    }

    @Test
    fun `the existing single-scale command reports the read-back`() {
        val fake = FakeSettingsDevice(refuses = true)

        val write = WindowAnimatorScaleCommand().execute("2.0", project, fake.device)

        assertFalse(write.took, "the tool window must show an error, not a success")
    }
}
