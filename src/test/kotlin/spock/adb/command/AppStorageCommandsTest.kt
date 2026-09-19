package spock.adb.command

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.storage.AppStoragePaths
import spock.adb.storage.StorageKind

/**
 * The device path of the storage editor, against a scripted device.
 *
 * What these pin is order and cleanup: a stale edit is refused before the app is stopped, the
 * app is stopped before the final comparison read and the write, the staged copy is removed on
 * every path, and success is decided by reading the file back.
 */
class AppStorageCommandsTest {

    private val prefs = AppStoragePaths.parse("shared_prefs/settings.xml")
    private val datastore = AppStoragePaths.parse("files/datastore/settings.preferences_pb")

    @Test
    fun `lists preference files and leaves working files out`() {
        val device = FakeStorageDevice().apply {
            files["shared_prefs/settings.xml"] = "<map />".toByteArray()
            files["shared_prefs/settings.xml.bak"] = ByteArray(0)
            files["files/datastore/settings.preferences_pb"] = ByteArray(0)
            files["files/datastore/user.pb"] = ByteArray(0)
        }

        val listed = device.device.listAppStorage(FakeStorageDevice.PKG)

        assertEquals(
            listOf(
                "files/datastore/settings.preferences_pb" to StorageKind.PREFERENCES_DATASTORE,
                "files/datastore/user.pb" to StorageKind.PROTO_DATASTORE,
                "shared_prefs/settings.xml" to StorageKind.SHARED_PREFERENCES,
            ),
            listed.map { it.path to it.kind },
        )
    }

    @Test
    fun `a file name holding a newline is skipped, not listed as the names it spells`() {
        val command = AppStorageShell.listCommand(FakeStorageDevice.PKG)
        assertTrue(command.contains("case \"\$f\" in *'\\''\n'\\''*) continue;; esac;"), command)

        val device = FakeStorageDevice().apply {
            files["shared_prefs/settings.xml"] = "<map />".toByteArray()
            // A name cannot hold '/', so the forgery is the line before the newline.
            files["shared_prefs/forged.xml\nrest"] = "<map />".toByteArray()
        }

        val listed = device.device.listAppStorage(FakeStorageDevice.PKG)

        assertEquals(listOf("shared_prefs/settings.xml"), listed.map { it.path })
    }

    @Test
    fun `a release build is refused with the reason`() {
        val device = FakeStorageDevice(debuggable = false)

        val thrown = assertThrows<IllegalStateException> { device.device.listAppStorage(FakeStorageDevice.PKG) }

        assertTrue(thrown.message!!.contains("not a debuggable build"), thrown.message)
    }

    @Test
    fun `reads a file byte for byte`() {
        val bytes = ByteArray(1000) { (it * 7).toByte() }
        val device = FakeStorageDevice().apply { files[datastore.path] = bytes }

        assertArrayEquals(bytes, device.device.readAppStorageFile(FakeStorageDevice.PKG, datastore))
    }

    @Test
    fun `a missing file says it was not found`() {
        val device = FakeStorageDevice()

        val thrown = assertThrows<IllegalStateException> {
            device.device.readAppStorageFile(FakeStorageDevice.PKG, prefs)
        }

        assertTrue(thrown.message!!.contains("not found"), thrown.message)
    }

    @Test
    fun `writes after stopping the app, verifies, and cleans up`() {
        val device = FakeStorageDevice().apply { files[prefs.path] = OLD }

        val result = device.device.writeAppStorageFile(
            FakeStorageDevice.PKG,
            prefs,
            NEW,
            expected = OLD,
            restart = false,
        )

        assertArrayEquals(NEW, device.files[prefs.path])
        assertArrayEquals(OLD, result.previous)
        assertFalse(result.restarted)

        val stop = device.commands.indexOfFirst { it.startsWith("am force-stop") }
        val write = device.commands.indexOfFirst { it.startsWith("cat '/data/local/tmp/") }
        val reads = device.commands.indices.filter { device.commands[it].contains("base64") }
        assertTrue(stop >= 0 && stop < write, "the app must be stopped before the write: ${device.commands}")
        assertTrue(
            reads.any { it in stop + 1 until write },
            "the file must be compared again between the stop and the write: ${device.commands}",
        )
        assertTrue(device.staged.isEmpty(), "the staged copy must be removed")
    }

    @Test
    fun `the staged copy is made private before it is written from, and removed after`() {
        val device = FakeStorageDevice().apply { files[prefs.path] = OLD }

        device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = OLD, restart = false)

        val remote = device.pushed.single()
        assertEquals("chmod 600 '$remote'; echo rc=$?", AppStorageShell.restrictStagedCommand(remote))
        val push = device.commands.indexOf("push $remote")
        val chmod = device.commands.indexOf(AppStorageShell.restrictStagedCommand(remote))
        val write = device.commands.indexOfFirst { it.startsWith("cat '$remote' |") }
        val remove = device.commands.indexOf(AppStorageShell.removeStagedCommand(remote))
        assertTrue(push in 0 until chmod, "push, then chmod: ${device.commands}")
        assertTrue(chmod < write, "chmod, then the write: ${device.commands}")
        assertTrue(write < remove, "the write, then rm: ${device.commands}")
    }

    @Test
    fun `a SharedPreferences write removes the backup, a DataStore write has none to remove`() {
        val prefsWrite = AppStorageShell.writeCommand(FakeStorageDevice.PKG, "/data/local/tmp/x", prefs, 3)
        val datastoreWrite = AppStorageShell.writeCommand(FakeStorageDevice.PKG, "/data/local/tmp/x", datastore, 3)

        assertTrue(prefsWrite.contains("settings.xml.bak"), prefsWrite)
        assertFalse(datastoreWrite.contains(".bak"), datastoreWrite)
    }

    @Test
    fun `the file is given the mode Android writes it with, before it is moved into place`() {
        // `cat >` inside run-as creates the file under the shell's umask, which is 0: a device
        // showed the written file as rw-rw-rw- where SharedPreferences writes rw-rw----.
        val prefsWrite = AppStorageShell.writeCommand(FakeStorageDevice.PKG, "/data/local/tmp/x", prefs, 3)
        val datastoreWrite = AppStorageShell.writeCommand(FakeStorageDevice.PKG, "/data/local/tmp/x", datastore, 3)

        listOf("660" to prefsWrite, "600" to datastoreWrite).forEach { (mode, command) ->
            val script = command.substringAfter("sh -c ")
            val chmod = script.indexOf("chmod $mode ")
            val move = script.indexOf("mv ")
            assertTrue(chmod in 0 until move, "the mode must be set before the move: $script")
        }
    }

    @Test
    fun `the write only replaces the file once its size matches`() {
        val command = AppStorageShell.writeCommand(FakeStorageDevice.PKG, "/data/local/tmp/x", datastore, 42)
        val script = command.substringAfter("sh -c ")

        assertTrue(command.startsWith("cat '/data/local/tmp/x' | run-as 'com.example.app' sh -c "), command)
        val sizeCheck = script.indexOf("-eq 42")
        val move = script.indexOf("mv ")
        assertTrue(sizeCheck in 0 until move, "the size must be checked before the move: $script")
    }

    @Test
    fun `a file that changed since it was read is not overwritten`() {
        val device = FakeStorageDevice().apply { files[prefs.path] = "changed by the app".toByteArray() }

        val thrown = assertThrows<IllegalStateException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = OLD, restart = false)
        }

        assertTrue(thrown.message!!.contains("changed on the device"), thrown.message)
        assertArrayEquals("changed by the app".toByteArray(), device.files[prefs.path])
        assertTrue(device.pushed.isEmpty(), "nothing may be pushed: ${device.pushed}")
        assertTrue(
            device.commands.none { it.startsWith("am force-stop") },
            "a stale edit must not stop the app: ${device.commands}",
        )
    }

    @Test
    fun `a refused write is reported and still cleans up`() {
        val device = FakeStorageDevice().apply {
            files[prefs.path] = OLD
            refuseWrites = true
        }

        val thrown = assertThrows<IllegalStateException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = null, restart = false)
        }

        assertTrue(thrown.message!!.contains("No space left on device"), thrown.message)
        assertArrayEquals(OLD, device.files[prefs.path])
        assertTrue(device.staged.isEmpty(), "the staged copy must be removed after a failure too")
    }

    @Test
    fun `a write the device did not keep is unverified and still carries what the file held`() {
        val device = FakeStorageDevice().apply {
            files[prefs.path] = OLD
            corruptWrites = true
        }

        val thrown = assertThrows<AppStorageUnverifiedWriteException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = OLD, restart = false)
        }

        assertTrue(thrown.message!!.contains("differ"), thrown.message)
        assertArrayEquals(OLD, thrown.previous)
        // What the device actually holds, so an undo can be written over exactly that.
        assertArrayEquals(device.files[prefs.path], thrown.written)
    }

    @Test
    fun `a restart that fails does not turn a verified write into a failure`() {
        val device = FakeStorageDevice().apply {
            files[prefs.path] = OLD
            unresponsiveLaunch = true
        }

        val result = device.device.writeAppStorageFile(
            FakeStorageDevice.PKG,
            prefs,
            NEW,
            expected = OLD,
            restart = true,
        )

        assertArrayEquals(NEW, device.files[prefs.path])
        assertArrayEquals(OLD, result.previous)
        assertFalse(result.restarted)
    }

    @Test
    fun `an app that is not installed is never stopped`() {
        val device = FakeStorageDevice(installed = false)

        assertThrows<IllegalStateException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = null, restart = false)
        }

        assertTrue(device.commands.none { it.startsWith("am force-stop") }, "${device.commands}")
    }

    @Test
    fun `an unsupported file cannot be written`() {
        val device = FakeStorageDevice()
        val proto = AppStoragePaths.parse("files/datastore/user.pb")

        assertThrows<IllegalArgumentException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, proto, NEW, expected = null, restart = false)
        }
        assertTrue(device.commands.isEmpty(), "${device.commands}")
    }

    @Test
    fun `a quote in a file name stays inside its quotes`() {
        val file = AppStoragePaths.parse("shared_prefs/it's.xml")

        val command = AppStorageShell.readCommand(FakeStorageDevice.PKG, file.path)

        assertTrue(command.contains("f='\\''shared_prefs/it'\\''\\'\\'''\\''s.xml'\\''"), command)
    }

    @Test
    fun `a force-stop the device refused stops the write before anything is staged`() {
        val device = FakeStorageDevice().apply {
            files[prefs.path] = OLD
            refuseStop = true
        }

        val thrown = assertThrows<IllegalStateException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = OLD, restart = false)
        }

        assertTrue(thrown.message!!.contains("could not be stopped"), thrown.message)
        assertArrayEquals(OLD, device.files[prefs.path])
        assertTrue(device.pushed.isEmpty(), "nothing may be staged once the app may still be running")
    }

    @Test
    fun `a staged copy that could not be made private is never written from`() {
        val device = FakeStorageDevice().apply {
            files[prefs.path] = OLD
            refuseChmod = true
        }

        val thrown = assertThrows<IllegalStateException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = OLD, restart = false)
        }

        assertTrue(thrown.message!!.contains("unreadable to other apps"), thrown.message)
        assertArrayEquals(OLD, device.files[prefs.path])
        assertTrue(
            device.commands.none { it.startsWith("cat '/data/local/tmp/") },
            "the write must not run after a failed chmod: ${device.commands}",
        )
        assertTrue(device.staged.isEmpty(), "the staged copy must still be removed")
    }

    @Test
    fun `a staged copy the device would not remove is reported with the write`() {
        val device = FakeStorageDevice().apply {
            files[prefs.path] = OLD
            refuseStagedRemoval = true
        }

        val result = device.device.writeAppStorageFile(
            FakeStorageDevice.PKG,
            prefs,
            NEW,
            expected = OLD,
            restart = false,
        )

        assertArrayEquals(NEW, device.files[prefs.path])
        val warning = result.warning
        assertTrue(warning != null && warning.contains(device.pushed.single()), "warning was $warning")
        assertTrue(warning!!.contains("adb shell rm -f"), warning)
    }

    @Test
    fun `a failure that also leaves the staged copy behind says both`() {
        val device = FakeStorageDevice().apply {
            files[prefs.path] = OLD
            refuseWrites = true
            refuseStagedRemoval = true
        }

        val thrown = assertThrows<IllegalStateException> {
            device.device.writeAppStorageFile(FakeStorageDevice.PKG, prefs, NEW, expected = null, restart = false)
        }

        assertTrue(thrown.message!!.contains("No space left on device"), thrown.message)
        assertTrue(thrown.message!!.contains("could not be removed"), thrown.message)
    }

    private companion object {
        val OLD = "<map />".toByteArray()
        val NEW = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n".toByteArray()
    }
}
