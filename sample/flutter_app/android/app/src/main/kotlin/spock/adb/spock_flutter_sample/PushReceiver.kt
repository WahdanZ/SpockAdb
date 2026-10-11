package spock.adb.spock_flutter_sample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Stands in for Firebase Messaging's receiver: same action, same signature permission, no
 * google-services.json. Stores the last message where the shared_preferences plugin reads it:
 * file `FlutterSharedPreferences`, key `flutter.last_push`.
 */
class PushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        @Suppress("DEPRECATION") // Bundle.get: the value type is whatever the sender chose.
        val lines = extras?.keySet()?.sorted()?.map { key -> "$key = ${extras.get(key)}" }.orEmpty()
        val text = "received at ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}\n" +
            lines.joinToString("\n")
        Log.i(TAG, "Push message received: ${lines.joinToString("; ")}")
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .edit().putString("flutter.last_push", text).apply()
        if (extras?.getString("gcm.n.e") == "1") {
            notify(context, extras.getString("gcm.notification.title"), extras.getString("gcm.notification.body"))
        }
    }

    private fun notify(context: Context, title: String?, body: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Push messages", NotificationManager.IMPORTANCE_DEFAULT))
            android.app.Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        manager.notify(
            NOTIFICATION_ID,
            builder.setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val TAG = "SpockFlutterSample"
        const val CHANNEL = "push"
        const val NOTIFICATION_ID = 86
    }
}
