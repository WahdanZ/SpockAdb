package spock.adb.logcat

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import spock.adb.CancellableShellReceiver
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live `logcat` stream from one device.
 *
 * ddmlib has no streaming logcat API that survives across the versions this plugin
 * supports, so the stream is `logcat -v threadtime` read through a
 * [CancellableShellReceiver]. That receiver is what makes stopping possible at all: the
 * previous receiver hard-coded `isCancelled()` to `false`, so a long-running command could
 * never be interrupted.
 *
 * Lines arrive on an ADB reader thread. Parsing happens there — it is cheap and keeps the
 * EDT free — and only the finished [LogcatEntry] is handed to the listener, which is
 * responsible for getting itself onto the EDT before touching Swing.
 */
class LogcatStream(
    private val device: IDevice,
    private val onEntry: (LogcatEntry) -> Unit,
    private val onStopped: (Throwable?) -> Unit = {},
) {

    private val log = Logger.getInstance(LogcatStream::class.java)
    private val running = AtomicBoolean(false)
    private var receiver: CancellableShellReceiver? = null

    val isRunning: Boolean get() = running.get()

    // A background task must not die on an unexpected exception: the caller would never
    // learn the work stopped. Everything is reported through the completion callback.
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        if (!running.compareAndSet(false, true)) return

        val shellReceiver = CancellableShellReceiver { line ->
            LogcatParser.parse(line)?.let(onEntry)
        }
        receiver = shellReceiver

        ApplicationManager.getApplication().executeOnPooledThread {
            var failure: Throwable? = null
            try {
                // `-v threadtime` is what LogcatParser expects. No `-d`: this is a live tail.
                //
                // A zero timeout means "no limit on time between output chunks", which is
                // required here — an idle device can legitimately log nothing for minutes.
                // It is only safe because the receiver can be cancelled; the same zero
                // timeout on the non-cancellable receiver was an unrecoverable hang.
                device.executeShellCommand("logcat -v threadtime", shellReceiver, 0L, TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (!shellReceiver.isCancelled) {
                    failure = e
                    log.warn("Logcat stream for ${device.serialNumber} ended unexpectedly", e)
                }
            } finally {
                running.set(false)
                onStopped(failure)
            }
        }
    }

    private companion object {
        const val CLEAR_TIMEOUT_SECONDS = 10L
    }

    fun stop() {
        receiver?.cancel()
        running.set(false)
    }

    /** Clears the device's log buffers so the next lines are genuinely new. */
    fun clearDeviceBuffer() {
        runCatching {
            device.executeShellCommand(
                "logcat -c",
                spock.adb.ShellOutputReceiver(),
                CLEAR_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }.onFailure { log.warn("Could not clear the logcat buffer", it) }
    }
}
