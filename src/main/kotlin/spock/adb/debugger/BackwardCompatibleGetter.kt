package spock.adb.debugger

/**
 * Calls the API the current IDE is expected to have, falling back to an older one when it does
 * not have it.
 *
 * Free of IntelliJ and ddmlib types on purpose: the rule that decides "this IDE has a different
 * API" is the whole point of the class, and it was wrong for two years without anything being
 * able to notice. Here it can be run.
 */
abstract class BackwardCompatibleGetter<T> {

    // Catching Throwable and rethrowing a RuntimeException is the contract, not an oversight:
    // this exists to survive whatever an IDE of an unknown version throws, and the wrapping is
    // what callers have always seen for a failure that is not a version difference.
    @Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
    fun get(): T = try {
        getCurrentImplementation()
    } catch (e: Throwable) {
        // Not a version difference: a real failure, wrapped as it always has been so callers
        // that catch RuntimeException keep working.
        if (!isApiMismatch(e)) throw RuntimeException(e)
        getPreviousImplementation()
    }

    abstract fun getCurrentImplementation(): T

    abstract fun getPreviousImplementation(): T

    companion object {

        /**
         * Whether [failure] — or anything it wraps — means "this IDE does not have that API".
         *
         * **The cause chain, not just the top.** jOOR reports a missing method as a
         * `ReflectException` *caused by* `NoSuchMethodException`, and the check here used to
         * look only at the throwable it was handed. Every jOOR miss was therefore classified as
         * a genuine error, so the fallback this class exists to provide was unreachable for the
         * one failure it was written for: an IDE carrying the older signature got
         *
         *     RuntimeException: ReflectException: NoSuchMethodException: No similar method
         *     attachToClient with params [...] could be found
         *
         * instead of the older call that would have worked.
         */
        fun isApiMismatch(failure: Throwable): Boolean = causes(failure).any {
            it is NoSuchMethodException ||
                it is NoSuchFieldException ||
                it is ClassNotFoundException ||
                // Covers NoSuchMethodError and NoSuchFieldError, which are LinkageErrors.
                it is LinkageError
        }

        /**
         * [failure] and everything it wraps, outermost first.
         *
         * Cycle-guarded and depth-bounded. A throwable whose cause chain loops back on itself is
         * rare, but this runs on the EDT and a spin there freezes the IDE.
         */
        fun causes(failure: Throwable): List<Throwable> {
            val chain = mutableListOf<Throwable>()
            var current: Throwable? = failure
            while (current != null && chain.size < MAX_CAUSE_DEPTH && chain.none { it === current }) {
                chain += current
                current = current.cause
            }
            return chain
        }

        private const val MAX_CAUSE_DEPTH = 16
    }
}
