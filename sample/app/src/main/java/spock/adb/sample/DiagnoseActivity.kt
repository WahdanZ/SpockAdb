package spock.adb.sample

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit

/**
 * One screen with something for every line of Diagnose Current Screen: an activity opened from
 * the hub (so the activity stack has two entries), a fragment inside it (Open Fragment), a
 * permission to deny (the Permissions line), a failed request in the log (a likely problem and
 * View Related Logs), and an unlabelled button (the accessibility count, and Inspect UI).
 */
class DiagnoseActivity : SampleActivity() {

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(SampleApp.TAG, "Camera permission ${if (granted) "granted" else "denied"}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Diagnose current screen") {
            note(
                "Press the buttons below, then run Tools › Spock ADB › Diagnose Current Screen (or " +
                    "the Diagnose tab, or android_diagnose_current_screen). Expect: DiagnoseActivity " +
                    "on screen, above MainActivity in the stack; " +
                    "PaymentFragment under fragments; CAMERA under denied permissions; an HTTP 500 " +
                    "and a repeated error in likelyProblems; one unlabelled button in the UI line.",
            )
            button("Ask for the camera — press Don't allow") { askCamera.launch(Manifest.permission.CAMERA) }
            button("Pay: server answers HTTP 500") {
                Log.e(SampleApp.TAG, "POST https://api.sample.spock.adb/payment -> HTTP 500 Internal Server Error")
            }
            button("Same error 10 times") { repeat(REPEATS) { Log.e(SampleApp.TAG, "Coupon lookup failed") } }
            note("An unlabelled icon button, for the accessibility count:")
            add(ImageButton(this@DiagnoseActivity).apply { setImageResource(android.R.drawable.ic_menu_camera) })
            add(FrameLayout(this@DiagnoseActivity).apply { id = FRAGMENT_CONTAINER })
        }
        if (savedInstanceState == null) {
            supportFragmentManager.commit { replace(FRAGMENT_CONTAINER, PaymentFragment()) }
        }
    }

    private companion object {
        const val REPEATS = 10
        /**
         * Fixed rather than View.generateViewId(): a restored fragment looks its container up
         * by the id saved before process death, and a generated one differs in the new process.
         */
        const val FRAGMENT_CONTAINER = 0x0D1A_6000
    }
}

/** The fragment Diagnose should list, and Open Fragment should open. */
class PaymentFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, PADDING, 0, 0)
            addView(TextView(context).apply { text = "PaymentFragment"; textSize = TITLE_SIZE })
            addView(
                Button(context).apply {
                    text = "Pay"
                    isAllCaps = false
                    setOnClickListener { Log.w(SampleApp.TAG, "Pay pressed with no card on file") }
                },
            )
        }

    private companion object {
        const val PADDING = 32
        const val TITLE_SIZE = 18f
    }
}
