package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading an app's runtime permissions out of `dumpsys package`.
 *
 * The permissions used to be filtered against a list of names written into the plugin — the
 * dangerous permissions as they stood in Android 6 — so every runtime permission added since
 * was silently dropped. The device already says which of an app's permissions are runtime ones,
 * in a section of its own, and that answer is current for whatever Android it is running.
 */
class GetApplicationPermissionTest {

    /** Shaped as the emulator prints it, including the sections on either side. */
    private val dumpsys = """
        Packages:
          Package [com.example.app] (c3d4):
            appId=10188
            requested permissions:
              android.permission.INTERNET
              android.permission.POST_NOTIFICATIONS
            install permissions:
              android.permission.INTERNET: granted=true
            User 0: ceDataInode=312069 installed=true hidden=false
              runtime permissions:
                android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED]
                android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ GRANTED_BY_DEFAULT]
                android.permission.READ_MEDIA_IMAGES: granted=false, flags=[ USER_SENSITIVE_WHEN_DENIED]
                android.permission.BLUETOOTH_CONNECT: granted=true, flags=[ ]
            User 10: ceDataInode=999 installed=true
              runtime permissions:
                android.permission.POST_NOTIFICATIONS: granted=true, flags=[ ]
    """.trimIndent()

    @Test
    fun `the permissions added since Android 6 are no longer dropped`() {
        val names = GetApplicationPermission.parse(dumpsys).map { it.name }

        // None of these four are in the list the plugin used to filter against.
        assertTrue("android.permission.POST_NOTIFICATIONS" in names, names.toString())
        assertTrue("android.permission.READ_MEDIA_IMAGES" in names, names.toString())
        assertTrue("android.permission.BLUETOOTH_CONNECT" in names, names.toString())
        assertEquals(4, names.size, "every runtime permission and nothing else: $names")
    }

    @Test
    fun `granted is read from the device, not guessed`() {
        val byName = GetApplicationPermission.parse(dumpsys).associate { it.name to it.isSelected }

        assertTrue(byName.getValue("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(byName.getValue("android.permission.BLUETOOTH_CONNECT"))
        assertFalse(byName.getValue("android.permission.POST_NOTIFICATIONS"))
        assertFalse(byName.getValue("android.permission.READ_MEDIA_IMAGES"))
    }

    @Test
    fun `install permissions are not runtime permissions`() {
        val names = GetApplicationPermission.parse(dumpsys).map { it.name }

        assertFalse("android.permission.INTERNET" in names, "INTERNET is granted at install and cannot be revoked")
    }

    @Test
    fun `a second user does not report the same permission twice`() {
        val entries = GetApplicationPermission.parse(dumpsys)

        assertEquals(1, entries.count { it.name == "android.permission.POST_NOTIFICATIONS" })
        assertFalse(
            entries.single { it.name == "android.permission.POST_NOTIFICATIONS" }.isSelected,
            "user 0 is the one everything else in the plugin acts on",
        )
    }

    @Test
    fun `the list comes back in a stable order`() {
        val names = GetApplicationPermission.parse(dumpsys).map { it.name }

        assertEquals(names.sorted(), names)
    }

    @Test
    fun `an app with no runtime permissions reports none rather than failing`() {
        val none = """
            Packages:
              Package [com.example.app] (c3d4):
                install permissions:
                  android.permission.INTERNET: granted=true
                User 0: ceDataInode=1 installed=true
                  runtime permissions:
        """.trimIndent()

        assertEquals(emptyList<String>(), GetApplicationPermission.parse(none).map { it.name })
        assertEquals(emptyList<String>(), GetApplicationPermission.parse("").map { it.name })
    }
}
