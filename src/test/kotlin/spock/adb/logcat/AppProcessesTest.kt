package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppProcessesTest {

    @Test
    fun `the four ways of knowing nothing are told apart`() {
        // The whole point of the type: an empty set used to mean all of these at once.
        assertEquals(AppProcesses.State.UNKNOWN, AppProcesses.UNKNOWN.state)
        assertEquals(AppProcesses.State.RESOLVING, AppProcesses.resolving("a").state)
        assertEquals(AppProcesses.State.FAILED, AppProcesses.failed("a").state)
        assertEquals(AppProcesses.State.NOT_RUNNING, AppProcesses.resolving("a").withPids(emptySet()).state)
    }

    @Test
    fun `only a running app is resolved`() {
        assertFalse(AppProcesses.UNKNOWN.isResolved)
        assertFalse(AppProcesses.resolving("a").isResolved)
        assertFalse(AppProcesses.failed("a").isResolved)
        assertFalse(AppProcesses.resolving("a").withPids(emptySet()).isResolved)
        assertTrue(AppProcesses.resolving("a").withPids(setOf(1)).isResolved)
    }

    @Test
    fun `an unresolved app matches no process at all`() {
        listOf(AppProcesses.UNKNOWN, AppProcesses.resolving("a"), AppProcesses.failed("a")).forEach { app ->
            assertFalse(app.contains(1), "${app.state} claimed a process")
        }
    }

    @Test
    fun `losing the last process is not the same as never having had one`() {
        val stopped = AppProcesses.resolving("a").withPids(setOf(7)).minus(7)

        assertEquals(AppProcesses.State.NOT_RUNNING, stopped.state)
        // The package survives, so Related keeps working and the status bar can still name it.
        assertEquals("a", stopped.packageName)
    }

    @Test
    fun `the package is remembered through every state`() {
        val resolving = AppProcesses.resolving("com.example.app")

        assertTrue(resolving.isNamedIn("ANR in com.example.app"))
        assertTrue(resolving.withPids(emptySet()).isNamedIn("ANR in com.example.app"))
        assertFalse(resolving.isNamedIn("ANR in com.other.app"))
        assertFalse(AppProcesses.UNKNOWN.isNamedIn("anything"))
    }
}
