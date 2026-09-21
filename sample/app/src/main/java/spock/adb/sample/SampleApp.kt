package spock.adb.sample

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        processStartedAt = SystemClock.elapsedRealtime()
        // One line per process start, so Restart / Force stop / Process death show up in Logcat.
        Log.i(TAG, "Process started: pid=${Process.myPid()}")
    }

    companion object {
        const val TAG = "SpockSample"

        /** When this process started, to show whether a restart really made a new one. */
        var processStartedAt = 0L
            private set
    }
}
