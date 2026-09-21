package spock.adb.sample.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        RunLog.record(context, "Alarm fired: ${intent.action}")
    }

    companion object {
        const val ACTION_EXACT = "spock.adb.sample.ALARM_EXACT"
        const val ACTION_INEXACT = "spock.adb.sample.ALARM_INEXACT"
        const val ACTION_REPEATING = "spock.adb.sample.ALARM_REPEATING"

        /**
         * One of each kind the Background Work tab tells apart: exact wall-clock, inexact elapsed,
         * and repeating.
         */
        fun schedule(context: Context): String {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            val inOneHour = System.currentTimeMillis() + 60 * 60 * 1000L
            if (exactAllowed) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, inOneHour, pending(context, ACTION_EXACT))
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, inOneHour, pending(context, ACTION_EXACT))
            }
            alarms.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 20 * 60 * 1000L,
                pending(context, ACTION_INEXACT),
            )
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_HALF_HOUR,
                pending(context, ACTION_REPEATING),
            )
            return if (exactAllowed) "3 alarms set (one exact)" else "3 alarms set (exact not permitted, so all inexact)"
        }

        fun cancel(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            listOf(ACTION_EXACT, ACTION_INEXACT, ACTION_REPEATING).forEach { alarms.cancel(pending(context, it)) }
        }

        private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
