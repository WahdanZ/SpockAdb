package spock.adb.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView

/**
 * Shows the last push message [PushReceiver] was handed, so Send push message's verdict can be
 * checked against what the app actually received.
 *
 * The receiver is guarded exactly like Firebase Messaging's. This debug build is delivered to as
 * the app itself (run-as), with no root, on any device:
 *  - Any device, this debug build: the plugin says "accepted", and the payload shows here
 *  - A release build of this app without root: the plugin says "refused", and nothing changes
 *  - Title/body set: a notification is posted too, as the SDK would for a background app
 *  - A value with ' or a newline: check it arrives unchanged below
 */
class PushActivity : SampleActivity() {

    private lateinit var last: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Push messages") {
            note(
                "Send one with the plugin's Send to device → Push message → Compose…, or " +
                    "android_send_push_message. No root needed: a debug build is sent to as the app itself.",
            )
            button("Refresh") { render() }
            last = output()
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        PushReceiver.listener = { runOnUiThread { render() } }
    }

    override fun onPause() {
        PushReceiver.listener = null
        super.onPause()
    }

    private fun render() {
        last.text = PushReceiver.lastReceived(this) ?: "Nothing received yet."
    }
}
