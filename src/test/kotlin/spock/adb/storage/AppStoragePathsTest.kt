package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppStoragePathsTest {

    @Test
    fun `classifies the files the editor lists`() {
        assertEquals(StorageKind.SHARED_PREFERENCES, AppStoragePaths.classify("shared_prefs/app settings.xml")?.kind)
        assertEquals(
            StorageKind.PREFERENCES_DATASTORE,
            AppStoragePaths.classify("files/datastore/settings.preferences_pb")?.kind,
        )
        assertEquals(StorageKind.PROTO_DATASTORE, AppStoragePaths.classify("files/datastore/user.pb")?.kind)
    }

    @Test
    fun `working files and anything elsewhere are not listed`() {
        listOf(
            "shared_prefs/settings.xml.bak",
            "shared_prefs/.xml",
            "files/datastore/settings.preferences_pb.tmp",
            "files/datastore/settings.preferences_pb.lock",
            "files/settings.preferences_pb",
            "databases/app.db",
            "shared_prefs",
        ).forEach { assertNull(AppStoragePaths.classify(it), it) }
    }

    @Test
    fun `paths that could reach another file are refused`() {
        listOf(
            "shared_prefs/../databases/app.xml",
            "/data/data/com.other/shared_prefs/a.xml",
            "shared_prefs/nested/a.xml",
            "./shared_prefs/a.xml",
            "files/datastore/..",
            "shared_prefs/a\n.xml",
            "",
        ).forEach { path ->
            assertThrows<IllegalArgumentException>(path) { AppStoragePaths.parse(path) }
        }
    }
}
