package spock.adb.uitree

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.TimeoutException
import spock.adb.CancellationSignal
import spock.adb.InterruptibleShellReceiver
import spock.adb.uitree.UiCaptureException.Kind
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One shell command of a screen capture, cancellable and with its failure classified.
 *
 * The dump and the display-metrics read are one capture, so they share this rather than each
 * deciding what a cancel or a lost device looks like: [cancellation] is checked before and after
 * the command and handed to ddmlib, which stops reading when it is set, and every failure is a
 * [UiCaptureException] saying which kind it was.
 */
internal class UiCaptureShell(
    private val device: IDevice,
    private val timeoutSeconds: Long,
    /** Named in errors. Passed in rather than read from a device that may be gone by then. */
    private val serial: String,
    private val cancellation: CancellationSignal,
) {

    fun run(command: String): String {
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

    fun throwIfCancelled(command: String) {
        if (cancellation.isCancelled()) {
            throw UiCaptureException(Kind.CANCELLED, "UI capture cancelled at `$command`.")
        }
    }

    /**
     * Throws what a failed shell call means, asking about cancellation first: an interrupted
     * call surfaces as `ClosedByInterruptException`, which is an [IOException], or as a ddmlib
     * [TimeoutException] "interrupted with immediate timeout" — either would otherwise report
     * a cancel as a lost device or a slow one.
     *
     * A lost device is described without saying what to do about it: what a person in the UI
     * Inspector can do differs from what an agent can, so each caller adds its own next step.
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
                "Device $serial is no longer available " +
                    "(${cause.message ?: cause.javaClass.simpleName}); it may have been " +
                    "disconnected or gone offline.",
                cause,
            )
    }
}
