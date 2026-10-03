package spock.adb.flutter

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.android.ddmlib.TimeoutException as AdbTimeoutException

/**
 * Android's cached-app freezer (Android 11+, on by default from 14): a process left in the
 * background is frozen, and a frozen process answers nothing — its VM Service included. Seen on
 * emulator-5554 (API 34, 2026-10-03): an app backgrounded overnight was `isFrozen=true`, procstate
 * `cch`, and every attach timed out until it came to the foreground. Asking `dumpsys` costs no
 * VM contact.
 */
object ProcessFreezer {

    private const val ADB_SECONDS = 10L

    /** The app's process records, with their freezer state. */
    fun command(applicationId: String): String = "dumpsys activity processes ${ShellQuote.quote(applicationId)}"

    /**
     * Whether any of [pids] is frozen in [dump] (what [command] printed): true when one is, false
     * when they are listed and none is, null when the dump does not say — an older Android, or a
     * format this does not know.
     */
    fun frozen(dump: String, pids: Set<Long>): Boolean? {
        var inRecord = false
        var said: Boolean? = null
        dump.lineSequence().forEach { line ->
            val header = line.trim()
            if (header.startsWith("*APP*") || header.startsWith("ProcessRecord{")) {
                inRecord = RECORD.find(header)?.groupValues?.get(1)?.toLongOrNull() in pids
                return@forEach
            }
            if (!inRecord) return@forEach
            when (FROZEN.find(line)?.groupValues?.get(1)) {
                "true" -> return true
                "false" -> said = false
            }
        }
        return said
    }

    /** [frozen] on [device]; null when adb fails or the dump does not say. Blocking. */
    fun check(device: IDevice, applicationId: String, pids: Set<Long>): Boolean? {
        val receiver = ShellOutputReceiver()
        val failed = try {
            device.executeShellCommand(command(applicationId), receiver, ADB_SECONDS, TimeUnit.SECONDS)
            false
        } catch (_: IOException) {
            true
        } catch (_: AdbCommandRejectedException) {
            true
        } catch (_: ShellCommandUnresponsiveException) {
            true
        } catch (_: AdbTimeoutException) {
            true
        }
        return if (failed) null else frozen(receiver.toString(), pids)
    }

    /** `ProcessRecord{abaa866 8972:spock.adb.spock_flutter_sample/u0a193}` → 8972. */
    private val RECORD = Regex("""ProcessRecord\{\S+ (\d+):""")
    private val FROZEN = Regex("""\bisFrozen=(true|false)\b""")
}
