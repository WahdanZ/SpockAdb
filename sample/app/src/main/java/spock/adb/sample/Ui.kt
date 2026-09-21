package spock.adb.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Screens are built in code rather than XML so each one reads top to bottom in a single file:
 * what it shows, and which plugin feature it is there to exercise.
 */
class ScreenBuilder(private val activity: Activity) {

    val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
    }

    fun heading(text: String) = add(
        TextView(activity).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        },
    )

    /** Grey explanatory text: which plugin feature this part of the screen is for. */
    fun note(text: String) = add(
        TextView(activity).apply {
            this.text = text
            alpha = 0.7f
            setPadding(0, 0, 0, dp(4))
        },
    )

    fun button(text: String, onClick: () -> Unit): Button = add(
        Button(activity).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        },
    )

    fun open(text: String, target: Class<out Activity>) = button(text) {
        activity.startActivity(Intent(activity, target))
    }

    /** Monospaced text the screen rewrites to show state. */
    fun output(initial: String = ""): TextView = add(
        TextView(activity).apply {
            text = initial
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(8))
        },
    )

    fun <T : View> add(view: T): T {
        column.addView(view)
        return view
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}

fun Activity.screen(title: String, build: ScreenBuilder.() -> Unit) {
    this.title = title
    val builder = ScreenBuilder(this).apply(build)
    setContentView(ScrollView(this).apply { addView(builder.column) })
}
