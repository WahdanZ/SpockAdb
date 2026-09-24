package spock.adb

/**
 * Whether the work a caller started should stop.
 *
 * Every caller that can cancel already does it by interrupting a thread: the MCP stdio server
 * interrupts the worker running a request, and a closed tab interrupts its pooled thread. So the
 * usual signal is [currentThread], and code that holds one needs no second cancellation API.
 */
fun interface CancellationSignal {

    fun isCancelled(): Boolean

    companion object {
        /**
         * Cancelled once the thread calling this is interrupted.
         *
         * The thread is captured here rather than looked up at poll time, because ddmlib may
         * ask from its own thread, and that thread being interrupted says nothing about ours.
         */
        fun currentThread(): CancellationSignal {
            val owner = Thread.currentThread()
            return CancellationSignal { owner.isInterrupted }
        }
    }
}
