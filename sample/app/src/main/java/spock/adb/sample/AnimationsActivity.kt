package spock.adb.sample

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.TextView

/**
 * The three animation scales as the app reads them, and an animator whose measured length shows
 * what they do: with animations off, a 1 s `ValueAnimator` ends at once. Set from Home ›
 * Developer options or by an agent with `android_set_animations`, then Refresh.
 */
class AnimationsActivity : SampleActivity() {

    private lateinit var scales: TextView
    private lateinit var timing: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Animations") {
            note(
                "Change the scales in Spock ADB › Home › Developer options, or call " +
                    "android_set_animations, then Refresh. Scale 0 makes the 1 s animation end at once.",
            )
            button("Refresh") { showScales() }
            scales = output()
            button("Run 1 s animation") { runAnimation() }
            timing = output("Not run yet.")
        }
    }

    /** Read again on every return, e.g. from the device's own Developer options screen. */
    override fun onResume() {
        super.onResume()
        showScales()
    }

    private fun showScales() {
        val lines = KEYS.map { key ->
            "$key: ${Settings.Global.getString(contentResolver, key) ?: "not set (1×)"}"
        }
        // areAnimatorsEnabled is API 26; minSdk is 24.
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "ValueAnimator.areAnimatorsEnabled(): ${ValueAnimator.areAnimatorsEnabled()}"
        } else {
            "ValueAnimator.areAnimatorsEnabled(): needs Android 8.0"
        }
        val text = (lines + enabled).joinToString("\n")
        Log.i(SampleApp.TAG, "Animation scales: ${text.replace('\n', ';')}")
        scales.text = text
    }

    private fun runAnimation() {
        timing.text = "Running…"
        val started = SystemClock.elapsedRealtime()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val elapsed = SystemClock.elapsedRealtime() - started
                        Log.i(SampleApp.TAG, "1 s animation took $elapsed ms")
                        timing.text = "Asked for $DURATION_MS ms, took $elapsed ms."
                    }
                },
            )
            start()
        }
    }

    private companion object {
        const val DURATION_MS = 1000L
        val KEYS = listOf(
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        )
    }
}
