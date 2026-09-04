package spock.adb.commandcenter

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import spock.adb.CancellableShellReceiver
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one shell command at a time against a device, streaming output and honouring cancel.
 *
 * Cancellation is real, not cosmetic: it uses [CancellableShellReceiver], whose
 * `isCancelled()` ddmlib polls between chunks. The Cancel button therefore stops a running
 * `logcat` or `dumpsys` rather than only hiding it.
 */
class CommandRunner {

    private val log = Logger.getInstance(CommandRunner::class.java)
    private val running = AtomicBoolean(false)
    private var receiver: CancellableShellReceiver? = null

    val isRunning: Boolean get() = running.get()

    /**
     * @param onLine called on a pooled thread for each output line.
     * @param onFinished called on a pooled thread with the failure, or null on success.
     */
    // A background task must not die on an unexpected exception: the caller would never
    // learn the work stopped. Everything is reported through the completion callback.
    @Suppress("TooGenericExceptionCaught")
    fun run(
        device: IDevice,
        command: String,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
        onFinished: (Throwable?) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            onFinished(IllegalStateException("A command is already running. Cancel it first."))
            return
        }

        val shellReceiver = CancellableShellReceiver(onLine)
        receiver = shellReceiver

        ApplicationManager.getApplication().executeOnPooledThread {
            var failure: Throwable? = null
            try {
                device.executeShellCommand(command, shellReceiver, timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // A cancelled command throws on the way out; that is the expected path, not
                // a failure worth reporting to the user.
                if (!shellReceiver.isCancelled) {
                    failure = e
                    log.warn("ADB command failed: $command", e)
                }
            } finally {
                running.set(false)
                receiver = null
                onFinished(failure)
            }
        }
    }

    fun cancel() {
        receiver?.cancel()
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val MAX_TIMEOUT_SECONDS = 300L
    }
}
