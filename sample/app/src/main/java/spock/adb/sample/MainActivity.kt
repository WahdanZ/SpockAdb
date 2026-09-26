package spock.adb.sample

import android.os.Bundle
import android.os.Process
import androidx.appcompat.app.AppCompatActivity
import spock.adb.sample.background.BackgroundWorkActivity
import spock.adb.sample.compose.ComposeInspectorActivity
import spock.adb.sample.compose.ComposeReliabilityActivity
import spock.adb.sample.compose.RecompositionActivity
import spock.adb.sample.fragments.NavigationActivity

/** The hub: one entry per group of Spock ADB features. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = packageManager.getPackageInfo(packageName, 0)
        screen("Spock Sample") {
            note(
                "A playground for every Spock ADB feature. Select \"$packageName\" in the tool " +
                    "window header, then open the screen for the feature you are testing.",
            )
            output("version ${info.versionName}  pid ${Process.myPid()}")

            heading("Device tab")
            open("Activity stack — Current Activity, Activity Stack", StackActivity::class.java)
            open("Fragments — Current Fragment, back stack", NavigationActivity::class.java)
            open("Deep links — Open Deep Link", DeepLinkActivity::class.java)
            open("Push messages — Send push message", PushActivity::class.java)
            open("Permissions — Grant / Revoke", PermissionsActivity::class.java)
            open("Process death, Don't keep activities, Restart", ProcessDeathActivity::class.java)
            open("Network — HTTP proxy, Wi-Fi, mobile data", NetworkActivity::class.java)

            heading("Other tabs")
            open("Storage — SharedPreferences, DataStore, cache", StorageActivity::class.java)
            open("Logcat — levels, stack traces, crash", LogcatActivity::class.java)
            open("Debug context — failures for android_get_debug_context", DebugContextActivity::class.java)
            open("Diagnose — Diagnose Current Screen, android_diagnose_current_screen", DiagnoseActivity::class.java)
            open("UI Inspector — Compose screen", ComposeInspectorActivity::class.java)
            open("UI Inspector — Compose reliability fixtures", ComposeReliabilityActivity::class.java)
            open("UI Inspector — Recomposition counts", RecompositionActivity::class.java)
            open("UI Inspector — Views screen", ViewsInspectorActivity::class.java)
            open("Background Work — jobs, WorkManager, alarms", BackgroundWorkActivity::class.java)
        }
    }
}
