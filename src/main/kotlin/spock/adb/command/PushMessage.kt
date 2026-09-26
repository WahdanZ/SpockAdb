package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.util.concurrent.TimeUnit

/**
 * A push message as the app's messaging receiver sees it: data pairs, and optionally the
 * title and body that make it a notification message.
 *
 * @param name what the message is saved as, so "order shipped" is one pick next time. Not sent.
 */
data class PushMessage(
    val data: Map<String, String> = emptyMap(),
    val title: String? = null,
    val body: String? = null,
    val name: String = "",
) {
    /** A notification message, which the SDK shows itself when the app is in the background. */
    val isNotification: Boolean get() = !title.isNullOrBlank() || !body.isNullOrBlank()

    /**
     * Every extra the broadcast carries, in the order sent.
     *
     * The notification keys are the ones Firebase Messaging reads from a delivered intent.
     * `gcm.n.e` is the switch that makes it treat the extras as a notification at all — without
     * it a title and body arrive as two unexplained data pairs. The message ID is unique per send
     * because the SDK drops a message whose ID it has already seen.
     */
    fun extras(messageId: String): List<Pair<String, String>> = buildList {
        add(MESSAGE_ID to messageId)
        if (isNotification) {
            add(NOTIFICATION_ENABLED to "1")
            title?.takeIf { it.isNotBlank() }?.let { add(NOTIFICATION_TITLE to it) }
            body?.takeIf { it.isNotBlank() }?.let { add(NOTIFICATION_BODY to it) }
        }
        data.forEach { (key, value) -> add(key to value) }
    }

    fun requireSendable() {
        require(data.isNotEmpty() || isNotification) {
            "Add a data pair, a title or a body first — an empty push message tests nothing."
        }
        require(data.keys.none { it.isBlank() }) { "A data key is blank. Give every value a key." }
        // The send is found again by this ID; a second one would replace it, and the verdict with it.
        require(MESSAGE_ID !in data) { "$MESSAGE_ID is set by Spock ADB for each send. Remove it from the data." }
    }

    companion object {
        const val ACTION = "com.google.android.c2dm.intent.RECEIVE"
        const val MESSAGE_ID = "google.message_id"
        const val NOTIFICATION_ENABLED = "gcm.n.e"
        const val NOTIFICATION_TITLE = "gcm.notification.title"
        const val NOTIFICATION_BODY = "gcm.notification.body"
    }
}

/**
 * What the device did with a push message, per device — never just "sent".
 *
 * A plain `am broadcast` reports `Broadcast completed` whether or not the receiver was allowed
 * to hear it: the messaging receiver is guarded by a signature permission the `shell` user does
 * not hold, and the refusal is recorded elsewhere. So the verdict comes from the device's own
 * record of the broadcast — see [PushBroadcast.parse].
 */
data class PushDelivery(
    val device: String,
    val packageName: String,
    val outcome: Outcome,
    val access: ShellAccess,
    val detail: String = "",
) {
    enum class Outcome { ACCEPTED, REFUSED, NO_RECEIVER, UNCONFIRMED, FAILED }

    val accepted: Boolean get() = outcome == Outcome.ACCEPTED

    val message: String
        get() = when (outcome) {
            Outcome.ACCEPTED ->
                "$device accepted the push message for $packageName."
            Outcome.REFUSED ->
                "Refused by $device: the receiver in $packageName only accepts Google Play services, " +
                    "the app itself, or root. ${access.fallback}"
            Outcome.NO_RECEIVER ->
                "$device has no push message receiver in $packageName, so nothing received it. " +
                    "Is the app installed, and does this build include Firebase Messaging?"
            Outcome.UNCONFIRMED ->
                "$device took the broadcast for $packageName, but its log could not be read to " +
                    "confirm the app was allowed to receive it."
            Outcome.FAILED ->
                "$device could not send the push message: " +
                    detail.ifBlank { "am broadcast reported nothing" }
        }
}

/**
 * Whether a device can deliver to a receiver that only Google Play services may send to.
 *
 * Two routes get past the receiver's signature permission. Android waives the check when the
 * sender is the receiving app itself, and `run-as` runs the broadcast as the app on any device —
 * retail phones included — for a debuggable build. Root holds every permission, which covers a
 * release build too, but `adb root` only works on debuggable images.
 */
enum class ShellAccess(val label: String, val fallback: String) {
    ROOT(
        "root shell: push messages can be delivered",
        "",
    ),
    APP(
        "debuggable app: sent as the app itself, no root needed",
        "",
    ),
    CAN_ROOT(
        "the app is not a debuggable build: install a debug build, or run `adb root`",
        "Install a debuggable build of the app, or run `adb root` (this device allows it), then send again.",
    ),
    NO_ROOT(
        "the app is not a debuggable build, and this device has no root",
        "Install a debuggable build of the app — it is then sent as the app itself, which needs no root.",
    ),
    UNKNOWN(
        "could not tell whether push messages can be delivered",
        "Make sure the installed build is debuggable, so it can be sent as the app itself.",
    ),
}

/** The shell line for one send, and how to read what it printed. */
internal object PushBroadcast {

    /** -f 0x20: FLAG_INCLUDE_STOPPED_PACKAGES, which a real push carries, so a force-stopped app still hears it. */
    private const val INCLUDE_STOPPED_PACKAGES = 32

    const val TIMEOUT_SECONDS = 20L

    private const val UID = "@@spock-uid"
    private const val DEBUGGABLE = "@@spock-debuggable"
    private const val RUN_AS = "@@spock-run-as"
    private const val RECEIVERS = "@@spock-receivers"
    private const val BROADCAST = "@@spock-broadcast"
    private const val LOG = "@@spock-log"
    private const val HISTORY = "@@spock-history"
    private const val SDK = "@@spock-sdk"
    private val MARKERS = listOf(UID, DEBUGGABLE, RUN_AS, SDK, RECEIVERS, BROADCAST, LOG, HISTORY)

    /** Android 14, whose broadcast queue skips a receiver without logging why. */
    private const val SILENT_SKIP_SDK = 34

    /** A receiver's state in the broadcast history, as Android 14's queue prints it. */
    private val RECEIVER_STATE = Regex("""^\s*(DELIVERED|SKIPPED|FAILURE|TIMEOUT)\b""")

    /**
     * One shell invocation: who the shell is, whether the app has a receiver, the broadcast, the
     * warnings logged since just before it, and the device's history entry for this send, found
     * by its unique [messageId]. One round trip also means the log window starts on the same clock
     * as the broadcast. Every key and value is quoted: payloads hold JSON, spaces and quotes as a
     * matter of course.
     *
     * The broadcast goes out as the app, through `run-as`, unless the shell is root — see
     * [ShellAccess]. `run-as` execs `am` directly rather than through a second shell, so the one
     * level of quoting here is still the only one. An app sender has to name a real user, where
     * the shell's default of "all users" is refused, hence `--user`.
     */
    fun command(packageName: String, message: PushMessage, messageId: String): String {
        val pkg = ShellQuote.quote(ShellQuote.requireValidComponent(packageName, "Package name"))
        val extras = message.extras(messageId).joinToString(" ") { (key, value) ->
            "--es ${ShellQuote.quote(key)} ${ShellQuote.quote(value)}"
        }
        return listOf(
            accessCommand(packageName),
            "echo $SDK; getprop ro.build.version.sdk",
            "echo $RECEIVERS; cmd package query-receivers --brief -a ${PushMessage.ACTION} -p $pkg 2>&1",
            "u=\$(am get-current-user 2>/dev/null)",
            // As the app when run-as answers with its uid; plain, as root, otherwise.
            "if [ \"\$(id -u)\" != 0 ] && [ \"\$(run-as $pkg id -u 2>/dev/null)\" -gt 0 ] 2>/dev/null; " +
                "then set -- run-as $pkg; else set --; fi",
            "echo $BROADCAST; t=\$(date +'%m-%d %H:%M:%S.000')",
            "\"\$@\" am broadcast --user \"\${u:-0}\" -a ${PushMessage.ACTION} -p $pkg " +
                "-f $INCLUDE_STOPPED_PACKAGES $extras 2>&1",
            "echo $LOG; logcat -d -T \"\$t\" '*:W' 2>&1",
            // From this send's record to the next one, however long its extras run: a fixed line
            // count cut a multi-line payload off before the receiver's state.
            "echo $HISTORY; dumpsys activity broadcasts history 2>&1 | " +
                "sed -n ${ShellQuote.quote("/${PushMessage.MESSAGE_ID}=$messageId/,/Historical Broadcast/p")}",
        ).joinToString("; ")
    }

    /** Who the shell is, and whether it can act as the app: which devices can receive, before sending. */
    fun accessCommand(packageName: String): String {
        val pkg = ShellQuote.quote(ShellQuote.requireValidComponent(packageName, "Package name"))
        return "echo $UID; id -u; echo $DEBUGGABLE; getprop ro.debuggable; echo $RUN_AS; run-as $pkg id -u 2>&1"
    }

    fun parseAccess(output: String): ShellAccess = access(sections(output))

    /**
     * Reads the verdict, strongest evidence first.
     *
     * Android 14's broadcast queue logs nothing when it skips a receiver for a missing
     * permission — verified on an API 34 emulator, where a refused send left the log clean — so the
     * log alone reported refusals as deliveries. The broadcast history does record it, per
     * receiver, next to the extras that carry this send's message ID. Earlier releases log the
     * denial, and their history does not name a per-receiver state, so there the log decides.
     *
     * A clean log is evidence of delivery only there. On Android 14 and later, or when the
     * release is unknown or the shell stopped answering ([timedOut]) before the record was read,
     * no recorded state means UNCONFIRMED — never ACCEPTED.
     */
    fun parse(
        device: String,
        packageName: String,
        messageId: String,
        output: String,
        timedOut: Boolean = false,
    ): PushDelivery {
        val sections = sections(output)
        val access = access(sections)
        val broadcast = sections[BROADCAST].orEmpty()
        val log = sections[LOG]
        val history = sections[HISTORY]?.let { historyEntry(it, messageId) }.orEmpty()

        fun delivery(outcome: PushDelivery.Outcome, detail: String = "") =
            PushDelivery(device, packageName, outcome, access, detail)

        if (hasNoReceiver(sections[RECEIVERS], packageName)) return delivery(PushDelivery.Outcome.NO_RECEIVER)
        if (!broadcast.contains("Broadcast completed")) {
            return delivery(PushDelivery.Outcome.FAILED, broadcast.lines().firstOrNull { it.isNotBlank() }.orEmpty())
        }
        val denial = (log.orEmpty().lines() + history.lines()).firstOrNull { isDenial(it, packageName) }
        if (denial != null) return delivery(PushDelivery.Outcome.REFUSED, denial.trim())

        val states = history.lines().mapNotNull { RECEIVER_STATE.find(it)?.groupValues?.get(1) }
        return when {
            "DELIVERED" in states -> delivery(PushDelivery.Outcome.ACCEPTED)
            states.isNotEmpty() -> delivery(
                PushDelivery.Outcome.FAILED,
                history.lines().firstOrNull { it.trim().startsWith("reason:") }?.trim()
                    ?: "the device recorded it as ${states.first()}",
            )
            timedOut || log == null || log.contains("logcat:") -> delivery(PushDelivery.Outcome.UNCONFIRMED)
            logRecordsRefusals(sections[SDK]) -> delivery(PushDelivery.Outcome.ACCEPTED)
            else -> delivery(PushDelivery.Outcome.UNCONFIRMED)
        }
    }

    private fun logRecordsRefusals(sdk: String?): Boolean =
        sdk?.trim()?.toIntOrNull()?.let { it < SILENT_SKIP_SDK } ?: false

    /** This send's record: from the line naming its message ID to the next history entry. */
    private fun historyEntry(section: String, messageId: String): String {
        val lines = section.lines()
        val start = lines.indexOfFirst { it.contains("${PushMessage.MESSAGE_ID}=$messageId") }
        if (start < 0) return ""
        return lines.drop(start).takeWhile { !it.contains("Historical Broadcast") }.joinToString("\n")
    }

    /**
     * "broadcasting Intent ... requires ...c2dm.permission.SEND due to receiver <pkg>/..." on most
     * releases, "receiving Intent" on some; both name the action or the package.
     */
    private fun isDenial(line: String, packageName: String): Boolean =
        line.contains("Permission Denial") &&
            (line.contains(PushMessage.ACTION) || line.contains("c2dm.permission.SEND")) &&
            line.contains(packageName)

    /**
     * Only a definite "none": `cmd` is missing before Android 7, and a query that could not run
     * must not turn into a claim that the app has no receiver.
     */
    private fun hasNoReceiver(receivers: String?, packageName: String): Boolean =
        receivers != null && receivers.contains("No receivers found") && !receivers.contains("$packageName/")

    /**
     * `run-as` prints the app's uid when it can act as the app, and "package not debuggable" or
     * "unknown package" when it cannot. Root comes first because the command sends as root then.
     */
    private fun access(sections: Map<String, String>): ShellAccess {
        val uid = sections[UID]?.trim()
        val appUid = sections[RUN_AS]?.trim()?.toIntOrNull()
        return when {
            uid == "0" -> ShellAccess.ROOT
            appUid != null && appUid > 0 -> ShellAccess.APP
            uid?.toIntOrNull() == null -> ShellAccess.UNKNOWN
            sections[DEBUGGABLE]?.trim() == "1" -> ShellAccess.CAN_ROOT
            else -> ShellAccess.NO_ROOT
        }
    }

    /** Splits the output at the markers. A section the shell never reached is absent, not empty. */
    private fun sections(output: String): Map<String, String> {
        val found = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        output.lineSequence().forEach { line ->
            val marker = MARKERS.firstOrNull { line.trim() == it }
            if (marker != null) {
                current = StringBuilder().also { found[marker] = it }
            } else {
                current?.appendLine(line)
            }
        }
        return found.mapValues { it.value.toString() }
    }
}

/**
 * Hands [message] to [packageName]'s messaging receiver, the way Google Play services would,
 * and reports what the device did with it.
 *
 * Shared by the tool window and the MCP tool, so the two cannot disagree on what "accepted"
 * means.
 */
internal fun IDevice.sendPushMessage(
    packageName: String,
    message: PushMessage,
    messageId: String = "spockadb-${System.currentTimeMillis()}",
): PushDelivery {
    message.requireSendable()
    val receiver = ShellOutputReceiver()
    val timedOut = try {
        executeShellCommand(
            PushBroadcast.command(packageName, message, messageId),
            receiver,
            PushBroadcast.TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        false
    } catch (ignored: ShellCommandUnresponsiveException) {
        // What arrived is still read: a broadcast that completed before the log read stalled
        // comes back UNCONFIRMED rather than as an error the device never reported.
        true
    }
    return PushBroadcast.parse(displayName(), packageName, messageId, receiver.toString(), timedOut)
}

/** Whether this device's shell can deliver push messages at all. Blocks: call off the EDT. */
internal fun IDevice.pushShellAccess(packageName: String): ShellAccess {
    val receiver = ShellOutputReceiver()
    executeShellCommand(
        PushBroadcast.accessCommand(packageName),
        receiver,
        PushBroadcast.TIMEOUT_SECONDS,
        TimeUnit.SECONDS,
    )
    return PushBroadcast.parseAccess(receiver.toString())
}

private fun IDevice.displayName(): String =
    runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: serialNumber

class SendPushMessageCommand : Command2<String, PushMessage, PushDelivery> {

    override fun execute(p: String, p2: PushMessage, project: Project, device: IDevice): PushDelivery =
        device.sendPushMessage(p, p2)
}
