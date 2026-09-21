package spock.adb.sample.background

import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * The device conditions as the app itself sees them, to check the plugin's Device conditions
 * against: what the plugin says it set, and what the app's own APIs report.
 */
object AppConditions {

    fun describe(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        return buildString {
            appendLine("Device idle (Doze): ${power.isDeviceIdleMode}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
                appendLine("Standby bucket:     ${bucketName(bucket)} ($bucket)")
            }
            appendLine("Battery:            $level%, ${if (plugged == 0) "unplugged" else "plugged in"}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val exact = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                append("Exact alarms:       ${if (exact) "allowed (bucket stays at Working set or higher)" else "not allowed"}")
            }
        }
    }

    private fun bucketName(bucket: Int) = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
        else -> "Other"
    }
}
