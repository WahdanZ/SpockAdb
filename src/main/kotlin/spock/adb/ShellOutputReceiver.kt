package spock.adb

import com.android.ddmlib.IShellOutputReceiver

class ShellOutputReceiver : IShellOutputReceiver {

    private val builder = StringBuilder()

    override fun toString(): String {
        var ret = builder.toString()
        // Strip trailing newlines. They are especially ugly because adb uses DOS line endings.
        while (ret.endsWith("\r") || ret.endsWith("\n")) {
            ret = ret.substring(0, ret.length - 1)
        }
        return ret
    }

    override fun addOutput(arg0: ByteArray, arg1: Int, arg2: Int) {
        builder.append(String(arg0, arg1, arg2))
    }

    override fun flush() {}

    override fun isCancelled() = false
}
