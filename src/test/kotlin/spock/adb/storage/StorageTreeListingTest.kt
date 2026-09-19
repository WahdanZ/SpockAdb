package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.command.StorageTree

/**
 * Listing the app's own directories, and the line that separates browsing from editing.
 *
 * The tree shows whatever the app has; only preference files can be opened in the table and
 * written back. Widening the first must not widen the second — a write takes a [StorageFile],
 * and the only way to get one is [AppStoragePaths.classify].
 */
class StorageTreeListingTest {

    @Test
    fun `a name with spaces survives, which is why ls is not parsed`() {
        val entries = StorageTree.parseEntries(
            listOf("f shared_prefs/ spaced name .xml", "f shared_prefs/awkward.xml"),
        )

        assertEquals(
            listOf("shared_prefs/ spaced name .xml", "shared_prefs/awkward.xml"),
            entries.map { it.path },
            "the path is the rest of the line, however many spaces it holds",
        )
    }

    @Test
    fun `the data directory's own entries lose the dot the shell expanded them with`() {
        val entries = StorageTree.parseEntries(listOf("d ./shared_prefs", "d ./cache", "f ./file.txt"))

        assertEquals(
            listOf("cache", "file.txt", "shared_prefs"),
            entries.map { it.path }.sorted(),
        )
    }

    @Test
    fun `directories come before files, each by name`() {
        val entries = StorageTree.parseEntries(
            listOf("f files/b.txt", "d files/zebra", "f files/A.txt", "d files/alpha"),
        )

        assertEquals(
            listOf("files/alpha", "files/zebra", "files/A.txt", "files/b.txt"),
            entries.map { it.path },
        )
    }

    @Test
    fun `anything that is not a tagged entry is not one`() {
        val entries = StorageTree.parseEntries(
            listOf("rc=0", "", "x files/odd", "d", "d ", "run-as: package not debuggable"),
        )

        assertEquals(emptyList<String>(), entries.map { it.path })
    }

    @Test
    fun `a path that climbs out of the data directory is refused`() {
        assertFalse(AppStoragePaths.isBrowsable("../../system/build.prop"))
        assertFalse(AppStoragePaths.isBrowsable("files/../../etc/hosts"))
        assertFalse(AppStoragePaths.isBrowsable("/data/data/other.app"))
        assertThrows<IllegalArgumentException> { AppStoragePaths.requireBrowsable("..") }

        // And a listing that somehow contained one drops it rather than showing it.
        assertEquals(
            emptyList<String>(),
            StorageTree.parseEntries(listOf("f ../../etc/hosts")).map { it.path },
        )
    }

    @Test
    fun `everything inside the data directory can be browsed`() {
        assertTrue(AppStoragePaths.isBrowsable(""), "the data directory itself")
        assertTrue(AppStoragePaths.isBrowsable("databases"))
        assertTrue(AppStoragePaths.isBrowsable("files/datastore/settings.preferences_pb"))
        assertTrue(AppStoragePaths.isBrowsable("cache/some file.bin"))
    }

    @Test
    fun `browsing a file does not make it editable`() {
        val database = StorageEntry("databases/app.db", isDirectory = false)
        val cached = StorageEntry("cache/blob.bin", isDirectory = false)
        val prefs = StorageEntry("shared_prefs/settings.xml", isDirectory = false)
        val directory = StorageEntry("shared_prefs", isDirectory = true)

        assertFalse(database.editable, "a database is listed, and the editor still cannot write it")
        assertFalse(cached.editable)
        assertFalse(directory.editable)
        assertTrue(prefs.editable)
        assertEquals(StorageKind.SHARED_PREFERENCES, prefs.file?.kind)
    }

    @Test
    fun `a proto DataStore file is listed and shown, but has no format to write`() {
        val proto = StorageEntry("files/datastore/custom.pb", isDirectory = false)

        assertEquals(StorageKind.PROTO_DATASTORE, proto.file?.kind)
        assertFalse(proto.editable, "its schema is the app's own, so the editor cannot encode it")
    }
}
