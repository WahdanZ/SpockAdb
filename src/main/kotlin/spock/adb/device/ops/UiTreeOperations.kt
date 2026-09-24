package spock.adb.device.ops

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.TimeoutException
import spock.adb.CancellationSignal
import spock.adb.InterruptibleShellReceiver
import spock.adb.ShellQuote
import spock.adb.uitree.UiCaptureException
import spock.adb.uitree.UiCaptureException.Kind
import spock.adb.uitree.UiTree
import spock.adb.uitree.UiTreeParser
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reading what is drawn on screen, as the accessibility tree `uiautomator` publishes.
 *
 * The UI Inspector tab and the `android_get_ui_tree` family had a copy of this each. They
 * dumped to **different files** on the device, explained a refusal in different words, and
 * disagreed about size: the agent's copy truncated the XML at 400,000 characters, which on a
 * large screen hands the parser a document cut off mid-element rather than a smaller tree.
 * The dump is parsed here and never shown raw, so there is nothing to cap — what an agent is
 * charged for is the rendering of the tree, which is bounded where it is rendered.
 *
 * One instance is one capture, on one thread, and [cancellation] is how that capture is
 * stopped. By default it is the interrupt flag of the thread that built the instance, which
 * is how every caller already cancels: the MCP stdio server interrupts a request's worker.
 * The signal is checked before and after each shell command and handed to ddmlib, which
 * stops reading when it is set. A cancelled capture throws [Kind.CANCELLED] at once — no
 * partial output is parsed and no cleanup is sent, since the next capture overwrites the same
 * file — and leaves the interrupt flag set for the thread's owner to clear.
 *
 * Every failure is a [UiCaptureException] saying which kind it was.
 */
class UiTreeOperations(
    private val device: IDevice,
    private val timeoutSeconds: Long = DUMP_TIMEOUT_SECONDS,
    /** Named in errors. Read once, here, rather than from a device that may be gone by then. */
    private val serial: String = device.serialNumber,
    private val cancellation: CancellationSignal = CancellationSignal.currentThread(),
) {

    /**
     * Dumps the screen and parses it.
     *
     * The dump is written to a file and read back rather than taken from `uiautomator`'s own
     * stdout, which prints only a confirmation line.
     *
     * @throws UiCaptureException — an [IllegalStateException] — saying what went wrong: a
     *   cancel, a lost device, a timeout, or what the device said when it could not dump — a
     *   screen that is off, a secure window such as a password field, or a UI still animating.
     */
    fun read(): UiTree {
        val dumpOutput = shell("uiautomator dump $DUMP_PATH")
        if (dumpOutput.contains("ERROR", ignoreCase = true)) {
            throw UiCaptureException(
                Kind.DUMP_REFUSED,
                "uiautomator could not dump the UI: $dumpOutput. This happens when the screen is " +
                    "off, a secure window is showing, or the UI is still animating.",
            )
        }

        val xml = shell("cat ${ShellQuote.quote(DUMP_PATH)}")
        // Best effort: a dump left behind is untidy, not a failure of the capture. A cancel is
        // still a cancel, though, so it is asked about again rather than swallowed with the rest.
        runCatching { shell("rm -f ${ShellQuote.quote(DUMP_PATH)}") }
        throwIfCancelled("rm -f $DUMP_PATH")

        if (xml.isBlank()) throw UiCaptureException(Kind.EMPTY_DUMP, "uiautomator produced an empty dump.")
        return UiTreeParser.parse(xml)
    }

    private fun shell(command: String): String {
        throwIfCancelled(command)
        val receiver = InterruptibleShellReceiver(cancellation)
        try {
            device.executeShellCommand(command, receiver, timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            failed(command, e)
        } catch (e: ShellCommandUnresponsiveException) {
            failed(command, e)
        } catch (e: AdbCommandRejectedException) {
            failed(command, e)
        } catch (e: IOException) {
            failed(command, e)
        }
        // ddmlib stops reading on a cancel and returns normally, with whatever it had so far.
        throwIfCancelled(command)
        return receiver.toString()
    }

    private fun throwIfCancelled(command: String) {
        if (cancellation.isCancelled()) {
            throw UiCaptureException(Kind.CANCELLED, "UI capture cancelled at `$command`.")
        }
    }

    /**
     * Throws what a failed shell call means, asking about cancellation first: an interrupted
     * call surfaces as `ClosedByInterruptException`, which is an [IOException], or as a ddmlib
     * [TimeoutException] "interrupted with immediate timeout" — either would otherwise report
     * a cancel as a lost device or a slow one.
     */
    private fun failed(command: String, cause: Exception): Nothing = throw when {
        cancellation.isCancelled() ->
            UiCaptureException(Kind.CANCELLED, "UI capture cancelled at `$command`.", cause)
        cause is TimeoutException || cause is ShellCommandUnresponsiveException ->
            UiCaptureException(
                Kind.TIMED_OUT,
                "`$command` did not finish within $timeoutSeconds seconds on device $serial.",
                cause,
            )
        else ->
            UiCaptureException(
                Kind.DEVICE_UNAVAILABLE,
                "Device $serial is not reachable (${cause.message ?: cause.javaClass.simpleName}). " +
                    "It may have been disconnected or gone offline; call android_list_devices " +
                    "to see which devices are connected.",
                cause,
            )
    }

    companion object {
        /**
         * Where the dump lands on the device. One path for both callers: two meant two files
         * left behind when a `rm` did not run, under names neither side would recognise.
         */
        const val DUMP_PATH = "/sdcard/spock-adb-ui-dump.xml"

        /** A dump of a busy screen is not fast, and 15s was not always enough for one. */
        const val DUMP_TIMEOUT_SECONDS = 30L
    }
}
