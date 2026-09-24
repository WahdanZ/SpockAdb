package spock.adb

import com.android.ddmlib.IShellOutputReceiver

/**
 * [ShellOutputReceiver] that tells ddmlib to stop when [signal] says so.
 *
 * `ShellOutputReceiver` answers `isCancelled()` with a hard-coded `false`, so a command it
 * receives runs to its end or its timeout whatever the caller wants. This one buffers the
 * same way, but asks [signal]. [CancellableShellReceiver] is the streaming counterpart, for
 * output read line by line rather than all at once.
 *
 * ddmlib polls [isCancelled] as it reads, so a cancel takes effect at its next poll, not
 * instantly — which is why callers still pass a timeout. On a cancel it stops reading and
 * returns normally, so the output collected here may be partial: check [signal] again before
 * trusting it.
 */
class InterruptibleShellReceiver(private val signal: CancellationSignal) : IShellOutputReceiver {

    private val builder = StringBuilder()

    override fun toString(): String {
        var ret = builder.toString()
        // Strip trailing newlines. They are especially ugly because adb uses DOS line endings.
        while (ret.endsWith("\r") || ret.endsWith("\n")) {
            ret = ret.substring(0, ret.length - 1)
        }
        return ret
    }

    override fun addOutput(data: ByteArray, offset: Int, length: Int) {
        builder.append(String(data, offset, length))
    }

    override fun flush() {
        // Nothing to push anywhere: the output is kept whole until toString() is read.
    }

    override fun isCancelled(): Boolean = signal.isCancelled()
}
