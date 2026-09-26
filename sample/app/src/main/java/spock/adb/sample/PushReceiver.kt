package spock.adb.sample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Stands in for Firebase Messaging's receiver: same action, same signature permission, so a send
 * is accepted or refused here exactly where it would be for a real app. No Firebase dependency or
 * google-services.json needed.
 */
class PushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        @Suppress("DEPRECATION") // Bundle.get: the value type is whatever the sender chose.
        val lines = extras?.keySet()?.sorted()?.map { key -> "$key = ${extras.get(key)}" }.orEmpty()
        val text = "received at ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}\n" +
            lines.joinToString("\n")
        Log.i(SampleApp.TAG, "Push message received: ${lines.joinToString("; ")}")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST, text).apply()

        if (extras?.getString("gcm.n.e") == "1") notify(context, extras.getString(TITLE), extras.getString(BODY))
        listener?.invoke()
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

    companion object {
        private const val PREFS = "push"
        private const val LAST = "last"
        private const val CHANNEL = "push"
        private const val NOTIFICATION_ID = 86
        private const val TITLE = "gcm.notification.title"
        private const val BODY = "gcm.notification.body"

        /** Set while [PushActivity] is on screen, so a delivery shows without pressing Refresh. */
        @Volatile
        var listener: (() -> Unit)? = null

        fun lastReceived(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null)
    }
}
