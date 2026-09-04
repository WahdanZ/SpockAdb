package spock.adb

import com.android.ddmlib.IShellOutputReceiver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A shell receiver that streams output as it arrives and can actually be stopped.
 *
 * [ShellOutputReceiver] buffers everything and hard-codes `isCancelled()` to `false`, so a
 * long-running command could not be interrupted at all — the reason logcat streaming and
 * command cancellation were not possible before.
 *
 * ddmlib polls [isCancelled] between chunks, so cancelling takes effect at the next chunk
 * boundary rather than instantly. A command producing no output will not notice until it
 * does, which is why callers still pass a timeout.
 */
class CancellableShellReceiver(
    /** Called on the ADB reader thread for every complete line. Must not block. */
    private val onLine: (String) -> Unit,
) : IShellOutputReceiver {

    private val cancelled = AtomicBoolean(false)
    private val pending = StringBuilder()

    fun cancel() {
        cancelled.set(true)
    }

    override fun isCancelled(): Boolean = cancelled.get()

    override fun addOutput(data: ByteArray, offset: Int, length: Int) {
        if (cancelled.get()) return

        pending.append(String(data, offset, length))

        // Emit only complete lines; a chunk boundary can land mid-line, and half a log
        // record rendered in the UI is worse than a few milliseconds of latency.
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            val line = pending.substring(0, newline).removeSuffix("\r")
            pending.delete(0, newline + 1)
            if (!cancelled.get()) onLine(line)
        }
    }

    override fun flush() {
        if (pending.isNotEmpty() && !cancelled.get()) {
            onLine(pending.toString().removeSuffix("\r"))
            pending.setLength(0)
        }
    }
}
