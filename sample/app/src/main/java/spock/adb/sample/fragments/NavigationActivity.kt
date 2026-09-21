package spock.adb.sample.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import spock.adb.sample.R
import spock.adb.sample.SampleActivity

/**
 * A Navigation host three fragments deep, with a child fragment inside the last one, so Current
 * Fragment and the fragment back stack have real nesting to report.
 */
class NavigationActivity : SampleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)
        // Up and Back both pop the fragment back stack (see SampleActivity); the title names the
        // destination so it is clear which fragment Current Fragment should report.
        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        host.navController.addOnDestinationChangedListener { _, destination, _ ->
            title = "Navigation — ${destination.label}"
        }
    }
}

/** A fragment that is a heading, a note and some buttons. */
abstract class SimpleFragment(private val heading: String, private val note: String) : Fragment() {

    protected lateinit var column: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = heading; textSize = 22f })
            addView(TextView(context).apply { text = note; alpha = 0.7f })
        }
        build()
        return column
    }

    protected abstract fun build()

    protected fun button(text: String, onClick: () -> Unit) {
        column.addView(Button(requireContext()).apply { this.text = text; isAllCaps = false; setOnClickListener { onClick() } })
    }
}

class HomeFragment : SimpleFragment("Home", "Current Fragment should report HomeFragment.") {
    override fun build() = button("Go to List") { findNavController().navigate(R.id.toList) }
}

class ListFragment : SimpleFragment("List", "The back stack is now Home → List.") {
    override fun build() {
        listOf("1", "42", "99").forEach { id ->
            button("Open item $id") { findNavController().navigate(R.id.toDetail, bundleOf("itemId" to id)) }
        }
    }
}

class DetailFragment : SimpleFragment(
    "Detail",
    "Home → List → Detail, with ChildFragment nested inside. Also reachable as spocksample://nav/detail/{itemId}.",
) {
    override fun build() {
        column.addView(TextView(requireContext()).apply { text = "itemId = ${arguments?.getString("itemId")}" })
        val host = FrameLayout(requireContext()).apply { id = View.generateViewId() }
        column.addView(host)
        if (childFragmentManager.findFragmentByTag(CHILD) == null) {
            childFragmentManager.commit { add(host.id, ChildFragment(), CHILD) }
        }
    }

    private companion object {
        const val CHILD = "child"
    }
}

class ChildFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        TextView(requireContext()).apply {
            text = "ChildFragment, hosted by DetailFragment's childFragmentManager"
            setPadding(0, 32, 0, 0)
        }
}
