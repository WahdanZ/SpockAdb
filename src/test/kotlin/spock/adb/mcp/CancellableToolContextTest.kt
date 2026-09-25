package spock.adb.mcp

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.CancellationSignal
import spock.adb.mcp.tools.CancellableToolContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CancellableToolContextTest {

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `the flag alone cancels`() {
        val flag = AtomicBoolean(false)
        val signal = CancellableToolContext(FakeToolContext(), flag::get).cancellationSignal()

        assertFalse(signal.isCancelled())
        flag.set(true)
        assertTrue(signal.isCancelled())
    }

    @Test
    fun `the base signal alone cancels`() {
        var baseCancelled = false
        val base = object : ToolContextDelegate(FakeToolContext()) {
            override fun cancellationSignal() = CancellationSignal { baseCancelled }
        }
        val signal = CancellableToolContext(base) { false }.cancellationSignal()

        assertFalse(signal.isCancelled())
        baseCancelled = true
        assertTrue(signal.isCancelled())
    }

    @Test
    fun `the base signal is taken on the calling thread, so its interrupt counts`() {
        val context = CancellableToolContext(FakeToolContext()) { false }
        val signal = AtomicReference<CancellationSignal>()
        val done = AtomicBoolean(false)
        val tool = Thread {
            signal.set(context.cancellationSignal())
            Thread.currentThread().interrupt()
            while (!done.get()) Thread.onSpinWait()
        }

        tool.start()
        while (signal.get() == null || !tool.isInterrupted) Thread.onSpinWait()
        // Asked from this thread, which is not interrupted, it answers for the tool's.
        val seen = signal.get().isCancelled()
        done.set(true)
        tool.join()

        assertTrue(seen)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `everything else is the base context's`() {
        val project = mockk<Project>()
        val base = FakeToolContext(project = project, applicationId = "com.example.wait")
        val context = CancellableToolContext(base) { false }

        assertSame(project, context.project)
        assertEquals("com.example.wait", context.projectApplicationId())
        assertEquals(base.devices(), context.devices())
        assertEquals("emulator-5554", context.selectDevice("emulator-5554").serialNumber)
        assertEquals("app", context.selectProject("app"))
        assertEquals("app", base.selectedProject)
        assertFalse(context.confirmDestructive("android_clear_app_data", "summary", base.devices().first()))
        assertEquals(listOf("android_clear_app_data"), base.confirmations)
    }

    /** A context that is [base] apart from what a test overrides. */
    private open class ToolContextDelegate(base: FakeToolContext) : spock.adb.mcp.tools.ToolContext by base
}
