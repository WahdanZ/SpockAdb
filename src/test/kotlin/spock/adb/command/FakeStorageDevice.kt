package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import spock.adb.storage.AppStoragePaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * A device whose shell understands exactly the commands [AppStorageShell] builds, backed by an
 * in-memory app data directory.
 *
 * Commands are matched by rebuilding them with [AppStorageShell] rather than by parsing them,
 * so a change to a command's text cannot quietly keep passing against a stale imitation.
 */
class FakeStorageDevice(
    private val installed: Boolean = true,
    private val debuggable: Boolean = true,
) {
    /** The app's files, by path relative to its data directory. */
    val files = linkedMapOf<String, ByteArray>()

    /** What sits in /data/local/tmp right now. */
    val staged = mutableMapOf<String, ByteArray>()

    /** Every path ever pushed. */
    val pushed = mutableListOf<String>()

    val commands = mutableListOf<String>()

    /** Makes the copy into the app's directory fail, as a full disk would. */
    var refuseWrites = false

    /** Makes the device keep different bytes from the ones it was sent. */
    var corruptWrites = false

    val device: IDevice = mockk(relaxed = true)

    init {
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every { device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>()) } answers {
            commands += command.captured
            val bytes = reply(command.captured).toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        val local = slot<String>()
        val remote = slot<String>()
        every { device.pushFile(capture(local), capture(remote)) } answers {
            pushed += remote.captured
            staged[remote.captured] = Files.readAllBytes(Path.of(local.captured))
        }
    }

    private fun reply(command: String): String = when {
        command.startsWith("pm list packages") -> if (installed) "package:$PKG" else ""
        command.startsWith("am force-stop") -> ""
        command.startsWith("run-as") || command.startsWith("cat ") && !debuggable ->
            if (debuggable) runAs(command) else "run-as: package not debuggable: $PKG"
        command.startsWith("cat ") -> write(command)
        command.startsWith("rm -f '/data/local/tmp/") -> "".also { staged.keys.removeIf { command.contains(it) } }
        else -> ""
    }

    private fun runAs(command: String): String {
        if (command == AppStorageShell.listCommand(PKG)) return files.keys.joinToString("\n") + "\nrc=0"
        val path = (files.keys + CANDIDATES).firstOrNull { command == AppStorageShell.readCommand(PKG, it) }
            ?: return "unexpected run-as command: $command"
        val bytes = files[path] ?: return "$path was not found\nrc=90"
        return Base64.getMimeEncoder().encodeToString(bytes) + "\nrc=0"
    }

    private fun write(command: String): String {
        val (remote, path) = staged.keys.flatMap { remote -> (files.keys + CANDIDATES).map { remote to it } }
            .firstOrNull { (remote, path) ->
                val file = AppStoragePaths.parse(path)
                command == AppStorageShell.writeCommand(PKG, remote, file, staged.getValue(remote).size)
            } ?: return "unexpected write: $command"
        if (refuseWrites) return "cat: write error: No space left on device\nrc=1"
        val sent = staged.getValue(remote)
        files[path] = if (corruptWrites) sent.copyOf(sent.size - 1) else sent
        return "rc=0"
    }

    companion object {
        const val PKG = "com.example.app"
        private val CANDIDATES = listOf("shared_prefs/settings.xml", "files/datastore/settings.preferences_pb")
    }
}
