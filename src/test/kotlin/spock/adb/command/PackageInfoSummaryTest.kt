package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading the summary `android_get_package_info` returns out of `dumpsys package`.
 *
 * The fixture is the sample app's dump, captured on an API 34 emulator: resolver tables first,
 * the package's own section after them, runtime permissions under `User 0`.
 */
class PackageInfoSummaryTest {

    private val dumpsys = checkNotNull(javaClass.getResource("/dumpsys/package-api34-sample-app.txt")).readText()
    private val summary = PackageInfoSummary.parse("spock.adb.sample", dumpsys)

    @Test
    fun `version, uid and sdk levels are read from the package section`() {
        assertEquals("1.2.0-sample", summary.versionName)
        assertEquals("7", summary.versionCode)
        // appId, not the uid= printed under the declared permission above it.
        assertEquals("10192", summary.uid)
        assertEquals("24", summary.minSdk)
        assertEquals("36", summary.targetSdk)
    }

    @Test
    fun `install times keep the time of day`() {
        assertEquals("2026-09-30 14:29:45", summary.firstInstallTime)
        assertEquals("2026-09-30 14:29:45", summary.lastUpdateTime)
    }

    @Test
    fun `the launcher activity is the one with a MAIN and LAUNCHER filter`() {
        assertEquals("spock.adb.sample/.MainActivity", summary.launcherActivity)
    }

    @Test
    fun `components are counted once each, however many filters they have`() {
        // MainActivity, DeepLinkActivity (two filters), NavigationActivity, RecipesActivity.
        assertEquals(4, summary.components.activities)
        assertEquals(0, summary.components.services)
        // ConstraintProxy x4, ProfileInstallReceiver, TracingReceiver, PushReceiver, RescheduleReceiver,
        // DiagnosticsReceiver, ConstraintProxyUpdateReceiver — each listed under several actions.
        assertEquals(10, summary.components.receivers)
        assertEquals(1, summary.components.providers)
    }

    @Test
    fun `runtime permissions are user 0's, and install permissions are not among them`() {
        val names = summary.permissions.map { it.name }

        assertEquals(6, names.size, names.toString())
        assertTrue(summary.permissions.none { it.granted })
        assertTrue("android.permission.INTERNET" !in names)
    }

    @Test
    fun `the summary is a fraction of the dump and says how to get the rest`() {
        val text = summary.describe()

        assertTrue(text.length < 3_000, "summary is ${text.length} chars:\n$text")
        assertTrue(dumpsys.length > 4 * text.length, "the dump is ${dumpsys.length} chars")
        assertTrue(text.contains("Version: 1.2.0-sample (7)"), text)
        assertTrue(text.contains("SDK: min 24, target 36"), text)
        assertTrue(text.contains("Launcher activity: spock.adb.sample/.MainActivity"), text)
        assertTrue(text.contains("Runtime permissions granted: none"), text)
        assertTrue(text.contains("Runtime permissions denied: android.permission.ACCESS_COARSE_LOCATION, "), text)
        assertTrue(text.contains("4 activities, 0 services and 10 receivers with an intent filter; 1 provider"), text)
        assertTrue(text.contains("raw: true"), text)
    }

    @Test
    fun `a denied permission the system will not ask for again says so`() {
        val text = PackageInfoSummary.parse(
            "com.example.app",
            """
            Packages:
              Package [com.example.app] (c3d4):
                appId=10188
                User 0: ceDataInode=1 installed=true
                  runtime permissions:
                    android.permission.CAMERA: granted=false, flags=[ USER_SET|USER_FIXED ]
                    android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET ]
            """.trimIndent(),
        ).describe()

        assertTrue(text.contains("Runtime permissions granted: android.permission.RECORD_AUDIO"), text)
        assertTrue(text.contains("Runtime permissions denied: android.permission.CAMERA (won't ask again)"), text)
    }

    @Test
    fun `services and providers in their own resolver tables are counted`() {
        // Shaped as Settings prints them on the same emulator: a permission after the filter.
        val summary = PackageInfoSummary.parse(
            "com.android.settings",
            """
            Service Resolver Table:
              Non-Data Actions:
                  android.service.quicksettings.action.QS_TILE:
                    d87d4c1 com.android.settings/.development.qstile.DevelopmentTiles${'$'}ShowLayout filter 3ee9466 permission android.permission.BIND_QUICK_SETTINGS_TILE
                      Action: "android.service.quicksettings.action.QS_TILE"
                    ab073a7 com.android.settings/.development.qstile.DevelopmentTiles${'$'}GPUProfiling filter a885054 permission android.permission.BIND_QUICK_SETTINGS_TILE
                      Action: "android.service.quicksettings.action.QS_TILE"

            Provider Resolver Table:
              Non-Data Actions:
                  android.content.action.SEARCH_INDEXABLES_PROVIDER:
                    4747daf com.android.settings/.search.SettingsSearchIndexablesProvider filter 83f05bc
                      Action: "android.content.action.SEARCH_INDEXABLES_PROVIDER"

            Registered ContentProviders:
              com.android.settings/.search.SettingsSearchIndexablesProvider:
                Provider{4747daf com.android.settings/.search.SettingsSearchIndexablesProvider}
              com.android.settings/.slices.SettingsSliceProvider:
                Provider{bd2234b com.android.settings/.slices.SettingsSliceProvider}
            """.trimIndent(),
        )

        assertEquals(2, summary.components.services)
        // The provider in both lists is one provider.
        assertEquals(2, summary.components.providers)
        assertEquals(0, summary.components.activities)
        assertNull(summary.launcherActivity)
    }

    @Test
    fun `a dump with none of the fields says unknown rather than failing`() {
        val text = PackageInfoSummary.parse("com.example.app", "").describe()

        assertTrue(text.contains("Version: unknown"), text)
        assertTrue(text.contains("UID: unknown"), text)
        assertTrue(text.contains("Launcher activity: none"), text)
        assertTrue(text.contains("Runtime permissions denied: none"), text)
    }
}
