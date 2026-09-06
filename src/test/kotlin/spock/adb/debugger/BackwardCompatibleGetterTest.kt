package spock.adb.debugger

import org.joor.ReflectException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The rule that decides whether to fall back to an older API.
 *
 * It was wrong for as long as the class existed and nothing could tell: jOOR reports a missing
 * method as a `ReflectException` *caused by* `NoSuchMethodException`, and the check looked only
 * at the throwable it was handed. Every jOOR miss was classified as a real error, so the
 * fallback was unreachable for the one failure it was written for — attaching the debugger on an
 * Android Studio whose `attachToClient` had a different arity crashed instead of falling back.
 */
class BackwardCompatibleGetterTest {

    private class Getter(
        private val current: () -> String,
        private val previous: () -> String = { "previous" },
    ) : BackwardCompatibleGetter<String>() {
        override fun getCurrentImplementation(): String = current()
        override fun getPreviousImplementation(): String = previous()
    }

    @Test
    fun `a jOOR missing-method failure falls back`() {
        // Exactly the shape from the crash report: ReflectException wrapping NoSuchMethodException.
        val joorMiss = ReflectException(NoSuchMethodException("No similar method attachToClient"))

        val result = Getter(current = { throw joorMiss }).get()

        assertEquals("previous", result)
    }

    @Test
    fun `a missing method thrown directly falls back`() {
        assertEquals("previous", Getter(current = { throw NoSuchMethodException("gone") }).get())
    }

    @Test
    fun `a linkage error falls back`() {
        // The method vanished between compile and run, which is the same situation.
        assertEquals("previous", Getter(current = { throw NoSuchMethodError("gone") }).get())
        assertEquals("previous", Getter(current = { throw NoClassDefFoundError("gone") }).get())
    }

    @Test
    fun `a missing class falls back`() {
        assertEquals("previous", Getter(current = { throw ClassNotFoundException("gone") }).get())
    }

    @Test
    fun `the current implementation is used when it works`() {
        assertEquals("current", Getter(current = { "current" }).get())
    }

    @Test
    fun `a real failure is not mistaken for a version difference`() {
        // The fallback must not paper over a genuine bug: calling the older API after an NPE
        // would hide the defect and probably throw the same way.
        val failure = assertThrows<RuntimeException> {
            Getter(current = { throw IllegalStateException("the device went away") }).get()
        }

        assertTrue(failure.cause is IllegalStateException, failure.toString())
    }

    @Test
    fun `a real failure wrapped by jOOR is still a real failure`() {
        val wrapped = ReflectException(IllegalStateException("the device went away"))

        assertFalse(BackwardCompatibleGetter.isApiMismatch(wrapped))
    }

    @Test
    fun `a missing method nested several levels deep is still found`() {
        val deep = RuntimeException(ReflectException(NoSuchMethodException("attachToClient")))

        assertTrue(BackwardCompatibleGetter.isApiMismatch(deep))
    }

    @Test
    fun `a cause chain that loops back on itself terminates`() {
        // This runs on the EDT, where a spin freezes the IDE rather than failing.
        val looping = RuntimeException("outer")
        looping.initCause(RuntimeException("inner", looping))

        assertFalse(BackwardCompatibleGetter.isApiMismatch(looping))
        assertTrue(BackwardCompatibleGetter.causes(looping).size < 16)
    }

    @Test
    fun `the chain reports the throwable and everything it wraps, outermost first`() {
        val root = NoSuchMethodException("attachToClient")
        val outer = ReflectException(root)

        assertEquals(listOf(outer, root), BackwardCompatibleGetter.causes(outer))
    }
}
