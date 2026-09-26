package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.command.PushDelivery.Outcome
import java.util.concurrent.TimeUnit

/**
 * `am broadcast` says "Broadcast completed" whether or not the receiver was allowed to hear it,
 * so the verdict is read from the log. These pin that a refusal is never reported as a delivery,
 * and that a payload reaches the device intact.
 */
class PushMessageTest {

    private val pkg = "com.example.app"

    @Test
    fun `a completed broadcast with no denial is accepted`() {
        val delivery = PushBroadcast.parse("Pixel", pkg, ID, output(uid = "0"))

        assertEquals(Outcome.ACCEPTED, delivery.outcome)
        assertEquals(ShellAccess.ROOT, delivery.access)
        assertTrue(delivery.accepted)
    }

    @Test
    fun `a permission denial in the log is a refusal, though the broadcast completed`() {
        val denial = "W BroadcastQueue: Permission Denial: broadcasting Intent " +
            "{ act=com.google.android.c2dm.intent.RECEIVE flg=0x400020 pkg=$pkg (has extras) } " +
            "from null (pid=4321, uid=2000) requires com.google.android.c2dm.permission.SEND " +
            "due to receiver $pkg/com.google.firebase.iid.FirebaseInstanceIdReceiver"

        val delivery = PushBroadcast.parse("Pixel", pkg, ID, output(log = denial))

        assertEquals(Outcome.REFUSED, delivery.outcome)
        assertFalse(delivery.accepted)
        assertTrue(delivery.message.contains("only accepts Google Play services"), delivery.message)
        assertTrue(delivery.detail.contains("Permission Denial"), delivery.detail)
    }

    @Test
    fun `a denial for another app is not this app's refusal`() {
        val other = "W BroadcastQueue: Permission Denial: broadcasting Intent " +
            "{ act=com.google.android.c2dm.intent.RECEIVE pkg=com.other } due to receiver com.other/.R"

        assertEquals(Outcome.ACCEPTED, PushBroadcast.parse("Pixel", pkg, ID, output(uid = "0", log = other)).outcome)
    }

    @Test
    fun `the refusal names the way out that fits the device`() {
        val debuggable = PushBroadcast.parse("emu", pkg, ID, output(debuggable = "1", log = denial()))
        val retail = PushBroadcast.parse("phone", pkg, ID, output(debuggable = "0", log = denial()))

        assertEquals(ShellAccess.CAN_ROOT, debuggable.access)
        assertTrue(debuggable.message.contains("adb root"), debuggable.message)
        assertEquals(ShellAccess.NO_ROOT, retail.access)
        assertTrue(retail.message.contains("debuggable build"), retail.message)
    }

    @Test
    fun `an app with no receiver is reported as such, not as delivered`() {
        val delivery = PushBroadcast.parse("Pixel", pkg, ID, output(uid = "0", receivers = "No receivers found"))

        assertEquals(Outcome.NO_RECEIVER, delivery.outcome)
    }

    @Test
    fun `a device without cmd is not taken to mean the app has no receiver`() {
        val noCmd = output(uid = "0", receivers = "/system/bin/sh: cmd: not found")

        val delivery = PushBroadcast.parse("Old", pkg, ID, noCmd)

        assertEquals(Outcome.ACCEPTED, delivery.outcome)
    }

    @Test
    fun `a broadcast am rejected is a failure carrying its words`() {
        val delivery = PushBroadcast.parse("Pixel", pkg, ID, output(broadcast = "Error: Unknown option: -f"))

        assertEquals(Outcome.FAILED, delivery.outcome)
        assertTrue(delivery.message.contains("Unknown option"), delivery.message)
    }

    @Test
    fun `a log that could not be read leaves the send unconfirmed`() {
        val unreadable = PushBroadcast.parse("Pixel", pkg, ID, output(log = "logcat: Unknown option -T"))
        val cutOff = PushBroadcast.parse("Pixel", pkg, ID, output().substringBefore("@@spock-log"))

        assertEquals(Outcome.UNCONFIRMED, unreadable.outcome)
        assertEquals(Outcome.UNCONFIRMED, cutOff.outcome)
    }

    /** Captured from an API 34 emulator: the log was clean, and only the history said why. */
    @Test
    fun `on Android 14 a refusal recorded only in the broadcast history is still a refusal`() {
        val refused = output(debuggable = "1", history = history("SKIPPED", DENIAL_REASON))

        val delivery = PushBroadcast.parse("emu", pkg, ID, refused)

        assertEquals(Outcome.REFUSED, delivery.outcome)
        assertTrue(delivery.message.contains("adb root"), delivery.message)
    }

    @Test
    fun `on Android 14 a clean log with no recorded state is unconfirmed, not accepted`() {
        val delivery = PushBroadcast.parse("emu", pkg, ID, output(uid = "0", sdk = "34"))

        assertEquals(Outcome.UNCONFIRMED, delivery.outcome)
    }

    @Test
    fun `an unknown release is not trusted to log refusals`() {
        assertEquals(Outcome.UNCONFIRMED, PushBroadcast.parse("emu", pkg, ID, output(uid = "0", sdk = "")).outcome)
    }

    @Test
    fun `a shell that stopped answering is unconfirmed unless delivery was recorded`() {
        val silent = PushBroadcast.parse("emu", pkg, ID, output(uid = "0"), timedOut = true)
        val recorded = output(uid = "0", history = history("DELIVERED"))
        val delivered = PushBroadcast.parse("emu", pkg, ID, recorded, timedOut = true)

        assertEquals(Outcome.UNCONFIRMED, silent.outcome)
        assertEquals(Outcome.ACCEPTED, delivered.outcome)
    }

    @Test
    fun `a record whose extras run many lines still yields its state`() {
        val long = history("SKIPPED", DENIAL_REASON).replace("line2", (1..80).joinToString("\n") { "line$it" })

        val delivery = PushBroadcast.parse("emu", pkg, ID, output(sdk = "34", history = long))

        assertEquals(Outcome.REFUSED, delivery.outcome)
    }

    @Test
    fun `a data key that would replace the message ID is refused`() {
        assertThrows<IllegalArgumentException> {
            PushMessage(data = mapOf(PushMessage.MESSAGE_ID to "x")).requireSendable()
        }
    }

    @Test
    fun `a receiver the history marks delivered is accepted`() {
        val delivery = PushBroadcast.parse("emu", pkg, ID, output(uid = "0", history = history("DELIVERED")))

        assertEquals(Outcome.ACCEPTED, delivery.outcome)
    }

    @Test
    fun `an earlier send's record is not this send's verdict`() {
        val earlier = history("SKIPPED", DENIAL_REASON).replace(ID, "spockadb-1")

        val both = output(uid = "0", history = earlier + "\n" + history("DELIVERED"))

        val delivery = PushBroadcast.parse("emu", pkg, ID, both)

        assertEquals(Outcome.ACCEPTED, delivery.outcome)
    }

    @Test
    fun `a receiver skipped for another reason is a failure that gives the reason`() {
        val reason = "reason: skipped by policy at enqueue: Background execution not allowed"

        val skipped = output(uid = "0", history = history("SKIPPED", reason))

        val delivery = PushBroadcast.parse("emu", pkg, ID, skipped)

        assertEquals(Outcome.FAILED, delivery.outcome)
        assertTrue(delivery.message.contains("Background execution not allowed"), delivery.message)
    }

    @Test
    fun `the history is searched for this send's message ID`() {
        val command = PushBroadcast.command(pkg, PushMessage(data = mapOf("a" to "b")), ID)

        assertTrue(command.contains("dumpsys activity broadcasts history"), command)
        assertTrue(command.contains("google.message_id=$ID/,/Historical Broadcast/p"), command)
    }

    @Test
    fun `a notification message carries the switch that makes the SDK treat it as one`() {
        val extras = PushMessage(title = "Shipped", body = "Order 42").extras("id-1").toMap()

        assertEquals("1", extras[PushMessage.NOTIFICATION_ENABLED])
        assertEquals("Shipped", extras[PushMessage.NOTIFICATION_TITLE])
        assertEquals("Order 42", extras[PushMessage.NOTIFICATION_BODY])
        assertEquals("id-1", extras[PushMessage.MESSAGE_ID])
    }

    @Test
    fun `a data message is not marked as a notification`() {
        val extras = PushMessage(data = mapOf("orderId" to "42")).extras("id-1").toMap()

        assertFalse(PushMessage.NOTIFICATION_ENABLED in extras)
        assertEquals("42", extras["orderId"])
    }

    @Test
    fun `an empty message or a blank key is refused before anything is sent`() {
        assertThrows<IllegalArgumentException> { PushMessage().requireSendable() }
        assertThrows<IllegalArgumentException> { PushMessage(data = mapOf(" " to "x")).requireSendable() }
    }

    @Test
    fun `a package name that is not one is refused`() {
        assertThrows<IllegalArgumentException> {
            PushBroadcast.command("com.x; reboot", PushMessage(data = mapOf("a" to "b")), "id")
        }
    }

    /**
     * The command is run through a real `sh`, with the device tools stubbed, so what `am` would
     * receive is observed rather than inferred from the string. Payloads hold JSON, quotes,
     * newlines and `$` as a matter of course; each must arrive as exactly one argument.
     */
    @Test
    fun `every key and value reaches am intact, through a real shell`() {
        val awkward = mapOf(
            "json" to """{"id": 42, "tags": ["a b", "c'd"]}""",
            "it's" to "don't \$HOME `id` \"quoted\"",
            "multi" to "line one\nline two",
            "empty" to "",
        )
        val message = PushMessage(data = awkward, title = "Title with 'quotes'", body = "Body; rm -rf /")

        val received = amArguments(PushBroadcast.command(pkg, message, "id-1"))

        message.extras("id-1").forEach { (key, value) ->
            val index = received.indexOf(key)
            assertTrue(index > 0 && received[index - 1] == "--es", "missing key <$key> in $received")
            assertEquals(value, received[index + 1], "value of <$key>")
        }
        assertEquals(pkg, received[received.indexOf("-p") + 1])
    }

    @Test
    fun `a shell that is not root sends as the app when the build is debuggable`() {
        val command = PushBroadcast.command(pkg, PushMessage(data = mapOf("a" to "b")), ID)

        val received = amArguments(command, appUid = "10182")

        assertEquals("run-as:$pkg", received.first(), "am must run through run-as: $received")
        assertEquals("0", received[received.indexOf("--user") + 1])
    }

    @Test
    fun `a build that is not debuggable is sent plainly, for the verdict to explain`() {
        val command = PushBroadcast.command(pkg, PushMessage(data = mapOf("a" to "b")), ID)

        val received = amArguments(command, appUid = null)

        assertEquals("broadcast", received.first(), "no run-as prefix expected: $received")
    }

    @Test
    fun `run-as answering with the app's uid means it is sent as the app`() {
        assertEquals(ShellAccess.APP, PushBroadcast.parseAccess(access(uid = "2000", runAs = "10182")))
        assertEquals(ShellAccess.ROOT, PushBroadcast.parseAccess(access(uid = "0", runAs = "10182")))
        assertEquals(
            ShellAccess.CAN_ROOT,
            PushBroadcast.parseAccess(access(uid = "2000", runAs = "run-as: package not debuggable: $pkg")),
        )
        assertEquals(
            ShellAccess.NO_ROOT,
            PushBroadcast.parseAccess(access(uid = "2000", debuggable = "0", runAs = "run-as: unknown package: $pkg")),
        )
    }

    private fun access(uid: String, runAs: String, debuggable: String = "1") =
        "@@spock-uid\n$uid\n@@spock-debuggable\n$debuggable\n@@spock-run-as\n$runAs\n"

    /**
     * Runs [command] in `sh` with the device tools stubbed; returns the arguments `am broadcast`
     * got. `run-as` is a script on the PATH, since a function name cannot hold a hyphen in POSIX
     * sh: it answers `id -u` with [appUid] (or refuses, when null), and otherwise marks that it
     * ran and prints the arguments the same way `am` does.
     */
    private fun amArguments(command: String, appUid: String? = null): List<String> {
        val bin = kotlin.io.path.createTempDirectory("push-stubs").toFile().apply { deleteOnExit() }
        java.io.File(bin, "run-as").apply {
            writeText(
                "#!/bin/sh\n" +
                    (if (appUid == null) "echo \"run-as: package not debuggable: \$1\" >&2; exit 1\n" else "") +
                    "if [ \"\$2\" = id ]; then echo $appUid; exit 0; fi\n" +
                    "printf 'run-as:%s\\0' \"\$1\"; shift 2\n" +
                    "for a in \"\$@\"; do printf '%s\\0' \"\$a\"; done\n",
            )
            setExecutable(true)
            deleteOnExit()
        }
        val stubs = "PATH=\"${bin.absolutePath}:\$PATH\"; " +
            "id(){ echo 2000; }; getprop(){ echo 0; }; cmd(){ echo '$pkg/.R'; }; logcat(){ :; }; " +
            "am(){ [ \"\$1\" = get-current-user ] && { echo 0; return; }; " +
            "for a in \"\$@\"; do printf '%s\\0' \"\$a\"; done; }; "
        val process = ProcessBuilder("sh", "-c", stubs + command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        // The markers are newline-terminated; am's arguments are NUL-separated after the last one.
        val amOutput = output.substringAfter("@@spock-broadcast\n").substringBefore("@@spock-log")
        return amOutput.split('\u0000').dropLast(1)
    }

    /** One history entry, shaped like `dumpsys activity broadcasts history` on API 34. */
    private fun history(state: String, reason: String = "") = """
        |    Historical Broadcast modern #0:
        |      BroadcastRecord{c56465e com.google.android.c2dm.intent.RECEIVE/u0} to user 0
        |      Intent { act=com.google.android.c2dm.intent.RECEIVE flg=0x1400030 pkg=$pkg (has extras) }
        |        extras: Bundle[{orderId=42, multi=line1
        |  line2, google.message_id=$ID, gcm.n.e=1}]
        |      caller=null null pid=19362 uid=2000
        |      $state terminal +2d20h9m7s216ms (-1) #0: (manifest)
        |        ActivityInfo:
        |          name=$pkg.PushReceiver
        |          packageName=$pkg
        |          permission=com.google.android.c2dm.permission.SEND
        |        $reason
    """.trimMargin()

    private fun denial() = "Permission Denial: broadcasting Intent { act=${PushMessage.ACTION} pkg=$pkg } " +
        "requires com.google.android.c2dm.permission.SEND due to receiver $pkg/.R"

    private fun output(
        uid: String = "2000",
        debuggable: String = "1",
        receivers: String = "$pkg/com.google.firebase.iid.FirebaseInstanceIdReceiver",
        broadcast: String = "Broadcasting: Intent { act=${PushMessage.ACTION} flg=0x400020 pkg=$pkg (has extras) }\n" +
            "Broadcast completed: result=0",
        log: String = "",
        history: String = "",
        sdk: String = "33",
    ) = listOf(
        "@@spock-uid", uid,
        "@@spock-sdk", sdk,
        "@@spock-debuggable", debuggable,
        "@@spock-run-as", "run-as: package not debuggable: $pkg",
        "@@spock-receivers", receivers,
        "@@spock-broadcast", broadcast,
        "@@spock-log", log,
        "@@spock-history", history,
    ).joinToString("\n")

    private companion object {
        const val ID = "spockadb-1727280000000"
        const val DENIAL_REASON = "reason: skipped by policy at enqueue: Permission Denial: broadcasting " +
            "Intent { act=com.google.android.c2dm.intent.RECEIVE flg=0x1400030 pkg=com.example.app (has extras) } " +
            "from null (pid=19362, uid=2000) to com.example.app/.PushReceiver requires " +
            "com.google.android.c2dm.permission.SEND"
    }
}
