package spock.adb.sample

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton

/**
 * The same kinds of elements as the Compose screen, built with Views and resource ids, for the
 * UI Inspector's View path and for Input Text on the Device tab.
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
        }
    }
}
