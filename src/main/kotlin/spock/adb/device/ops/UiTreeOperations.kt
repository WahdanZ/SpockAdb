package spock.adb.device.ops

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.uitree.UiTree
import spock.adb.uitree.UiTreeParser
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
 * One instance is one capture, on one thread.
 */
class UiTreeOperations(
    private val device: IDevice,
    private val timeoutSeconds: Long = DUMP_TIMEOUT_SECONDS,
) {

    /**
     * Dumps the screen and parses it.
     *
     * The dump is written to a file and read back rather than taken from `uiautomator`'s own
     * stdout, which prints only a confirmation line.
     *
     * @throws IllegalStateException with what the device said when it could not dump — a
     *   screen that is off, a secure window such as a password field, or a UI still animating.
     */
    fun read(): UiTree {
        val dumpOutput = shell("uiautomator dump $DUMP_PATH")
        check(!dumpOutput.contains("ERROR", ignoreCase = true)) {
            "uiautomator could not dump the UI: $dumpOutput. This happens when the screen is " +
                "off, a secure window is showing, or the UI is still animating."
        }

        val xml = shell("cat ${ShellQuote.quote(DUMP_PATH)}")
        // Best effort: a dump left behind is untidy, not a failure of the capture.
        runCatching { shell("rm -f ${ShellQuote.quote(DUMP_PATH)}") }

        check(xml.isNotBlank()) { "uiautomator produced an empty dump." }
        return UiTreeParser.parse(xml)
    }

    private fun shell(command: String): String {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(command, receiver, timeoutSeconds, TimeUnit.SECONDS)
        return receiver.toString()
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
