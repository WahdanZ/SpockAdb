package spock.adb.device.ops

import com.android.ddmlib.IDevice
import spock.adb.CancellationSignal
import spock.adb.ShellQuote
import spock.adb.uitree.DisplayMetrics
import spock.adb.uitree.DisplayMetricsReader
import spock.adb.uitree.UiCaptureException
import spock.adb.uitree.UiCaptureException.Kind
import spock.adb.uitree.UiCaptureShell
import spock.adb.uitree.UiObservation
import spock.adb.uitree.UiTree
import spock.adb.uitree.UiTreeParser

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
 * Every failure is a [UiCaptureException] saying which kind it was. The commands themselves
 * are sent by [UiCaptureShell], which [DisplayMetricsReader] shares, so the two `wm` commands
 * of an [observe] are cancelled and classified exactly as the dump is.
 */
class UiTreeOperations(
    private val device: IDevice,
    timeoutSeconds: Long = DUMP_TIMEOUT_SECONDS,
    /** Named in errors. Read once, here, rather than from a device that may be gone by then. */
    private val serial: String = device.serialNumber,
    private val cancellation: CancellationSignal = CancellationSignal.currentThread(),
) {

    private val shell = UiCaptureShell(device, timeoutSeconds, serial, cancellation)

    /**
     * Dumps the screen and says what the dump is: which device, which window, when by the
     * host's clock, and at what viewport and density — see [UiObservation].
     *
     * The clock brackets the dump alone, not the metrics read that follows it, since the
     * dump is the moment the observation describes.
     *
     * @param metrics display metrics already known, which saves the two `wm` commands; a
     *   caller polling one screen reads them once. When null they are read after the dump,
     *   best effort: a failed read leaves fields null and is listed in [UiObservation.limits].
     * @throws UiCaptureException as [read] does, and also for a cancel or a lost device during
     *   the metrics read.
     */
    fun observe(metrics: DisplayMetrics? = null): UiObservation {
        val startedAt = System.currentTimeMillis()
        val tree = read()
        val completedAt = System.currentTimeMillis()
        val measured = metrics ?: DisplayMetricsReader.read(device, cancellation, serial)
        return UiObservation(
            tree = tree.copy(densityDpi = measured.densityDpi),
            deviceSerial = serial,
            startedAtMillis = startedAt,
            completedAtMillis = completedAt,
            metrics = measured,
        )
    }

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
        val dumpOutput = shell.run("uiautomator dump $DUMP_PATH")
        if (dumpOutput.contains("ERROR", ignoreCase = true)) {
            throw UiCaptureException(
                Kind.DUMP_REFUSED,
                "uiautomator could not dump the UI: $dumpOutput. This happens when the screen is " +
                    "off, a secure window is showing, or the UI is still animating.",
            )
        }

        val xml = shell.run("cat ${ShellQuote.quote(DUMP_PATH)}")
        // Best effort: a dump left behind is untidy, not a failure of the capture. A cancel is
        // still a cancel, though, so it is asked about again rather than swallowed with the rest.
        runCatching { shell.run("rm -f ${ShellQuote.quote(DUMP_PATH)}") }
        shell.throwIfCancelled("rm -f $DUMP_PATH")

        if (xml.isBlank()) throw UiCaptureException(Kind.EMPTY_DUMP, "uiautomator produced an empty dump.")
        return UiTreeParser.parse(xml)
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
