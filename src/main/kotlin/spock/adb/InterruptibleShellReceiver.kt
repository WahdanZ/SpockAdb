package spock.adb

import com.android.ddmlib.IShellOutputReceiver
import java.io.ByteArrayOutputStream

/**
 * [ShellOutputReceiver] that tells ddmlib to stop when [signal] says so.
 *
 * `ShellOutputReceiver` answers `isCancelled()` with a hard-coded `false`, so a command it
 * receives runs to its end or its timeout whatever the caller wants. This one buffers the
 * whole output too, but asks [signal], and decodes it as UTF-8 only once it is all in.
 * [CancellableShellReceiver] is the streaming counterpart, for output read line by line rather
 * than all at once.
 *
 * ddmlib polls [isCancelled] as it reads, so a cancel takes effect at its next poll, not
 * instantly — which is why callers still pass a timeout. On a cancel it stops reading and
 * returns normally, so the output collected here may be partial: check [signal] again before
 * trusting it.
 */
class InterruptibleShellReceiver(private val signal: CancellationSignal) : IShellOutputReceiver {

    // Bytes, decoded once in toString(): ddmlib chunks by buffer size, not by character, and a
    // UTF-8 character split across two chunks decoded one chunk at a time became two U+FFFD.
    private val bytes = ByteArrayOutputStream()

    override fun toString(): String {
        var ret = bytes.toString(Charsets.UTF_8.name())
        // Strip trailing newlines. They are especially ugly because adb uses DOS line endings.
        while (ret.endsWith("\r") || ret.endsWith("\n")) {
            ret = ret.substring(0, ret.length - 1)
        }
        return ret
    }

    override fun addOutput(data: ByteArray, offset: Int, length: Int) {
        bytes.write(data, offset, length)
    }

    override fun flush() {
        // Nothing to push anywhere: the output is kept whole until toString() is read.
    }

    override fun isCancelled(): Boolean = signal.isCancelled()
}
