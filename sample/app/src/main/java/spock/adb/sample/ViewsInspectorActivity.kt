package spock.adb.sample

import android.content.Context
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

/**
 * The same kinds of elements as the Compose screen, built with Views and resource ids, for the
 * UI Inspector's View path and for Input Text on the Device tab.
 *
 * The last two rows are for the Inspector's Jump to Source: a label inflated from a layout, found
 * by its `android:id`, and a View class of the app's own, found by its class. The ids above them
 * are found through `R.id` in this file. The checks are in docs/COMPOSE-SUPPORT-PLAN.md,
 * *Source navigation checks*.
 */
class ViewsInspectorActivity : SampleActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Views inspector") {
            note("Focus the field and use the plugin's Input Text to type into it.")
            add(EditText(this@ViewsInspectorActivity).apply { id = R.id.views_name_field; hint = "Type here" })
            add(CheckBox(this@ViewsInspectorActivity).apply { id = R.id.views_terms; text = "I agree" })
            val result = output()
            button("Submit") { result.text = "Submitted" }
            note("An image button with no contentDescription, for the Accessibility Audit:")
            add(ImageButton(this@ViewsInspectorActivity).apply { setImageResource(android.R.drawable.ic_menu_share) })
            note("For Jump to Source in the UI Inspector:")
            add(layoutInflater.inflate(R.layout.views_source_row, column, false))
            add(SourceBadgeView(this@ViewsInspectorActivity))
        }
    }
}

/**
 * A View class of the app's own, which Jump to Source can only find by its class.
 *
 * It reports its own class name to accessibility; a TextView subclass otherwise reports
 * `android.widget.TextView`, and the Inspector would have nothing of the app's to look up. Its text
 * is joined from parts at run time — not a template, which the search would match as a pattern — so
 * nothing matches it and the search falls through to the class.
 */
class SourceBadgeView(context: Context) : TextView(context) {
    init {
        text = listOf("Drawn", "by", javaClass.simpleName).joinToString(" ")
    }

    override fun getAccessibilityClassName(): CharSequence = SourceBadgeView::class.java.name
}
