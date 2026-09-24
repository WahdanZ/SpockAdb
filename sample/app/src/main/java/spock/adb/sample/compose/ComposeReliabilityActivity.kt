package spock.adb.sample.compose

import android.os.Bundle
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import spock.adb.sample.R
import spock.adb.sample.SampleActivity

/**
 * Fixtures for the element tools' refusals, scroll-container resolution, the accessibility
 * audit, capture failures and hybrid tag detection — one tab each, so a capture of one tab is
 * not muddied by another's fixtures. The calls to run and what each should answer are in
 * docs/COMPOSE-SUPPORT-PLAN.md, "Device checks".
 *
 * Headings describe what to try without repeating a fixture's own text: element tools match
 * text by substring, so a heading that quoted it would become one more candidate.
 */
class ComposeReliabilityActivity : SampleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Compose reliability"
        setContent { MaterialTheme { Surface { ReliabilityScreen() } } }
    }
}

private enum class FixtureTab(val title: String, val tag: String) {
    TAPS("Taps", "tab_taps"),
    SCROLL("Scroll", "tab_scroll"),
    AUDIT("Audit", "tab_audit"),
    BUSY("Busy", "tab_busy"),
    HYBRID("Hybrid", "tab_hybrid"),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ReliabilityScreen() {
    var tab by rememberSaveable { mutableStateOf(FixtureTab.TAPS) }
    var exposeTags by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = exposeTags }) {
        TabRow(selectedTabIndex = tab.ordinal) {
            FixtureTab.entries.forEach {
                Tab(
                    selected = tab == it,
                    onClick = { tab = it },
                    text = { Text(it.title) },
                    modifier = Modifier.testTag(it.tag),
                )
            }
        }
        when (tab) {
            FixtureTab.TAPS -> TapsTab()
            FixtureTab.SCROLL -> ScrollTab()
            FixtureTab.AUDIT -> AuditTab()
            FixtureTab.BUSY -> BusyTab()
            FixtureTab.HYBRID -> HybridTab(exposeTags) { exposeTags = it }
        }
    }
}

@Composable
private fun Section(title: String, hint: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The whole row toggles, so its label is a tap target. With test tags switched off the tag is
 * gone, and tapping by text is the only way back.
 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Ambiguous, identical, shared-target and disabled tap targets. */
@Composable
private fun TapsTab() {
    var lastEvent by remember { mutableStateOf("nothing yet") }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Outside the scrolling part, so the result of a tap stays on screen.
        Text("Last tap: $lastEvent", modifier = Modifier.padding(vertical = 8.dp).testTag("last_event"))

        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Section(
                "1. One label, two forms",
                "By the shared label: refused. containerTag form_a or form_b, or testTag form_a_button, picks one.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("a", "b").forEach { form ->
                    Column(Modifier.weight(1f).testTag("form_$form")) {
                        Text("Form ${form.uppercase()}")
                        Button(onClick = { lastEvent = "form ${form.uppercase()} button" }, modifier = Modifier.testTag("form_${form}_button")) {
                            Text("Save")
                        }
                    }
                }
            }

            Section(
                "2. A title and its button",
                "By the shared word: refused, naming both. exact: true picks the button.",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Discard changes?", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { lastEvent = "confirm button" }, modifier = Modifier.testTag("confirm_button")) {
                    Text("Discard")
                }
            }

            Section(
                "3. Two identical rows",
                "Same text and description, no tag: no selector field can separate them.",
            )
            Column(Modifier.testTag("identical_rows")) {
                repeat(2) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { lastEvent = "an identical row" }
                            .semantics { contentDescription = "Archive this item" }
                            .padding(8.dp),
                    ) { Text("Archive") }
                }
            }

            Section(
                "4. A row and its icon, one description",
                "By the description: two matches, one row, one tap. containerTag report_label: refused, the row is outside it.",
            )
            Row(
                Modifier.fillMaxWidth()
                    .clickable { lastEvent = "report row" }
                    .semantics { contentDescription = "Share report" }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(android.R.drawable.ic_menu_share), contentDescription = "Share report")
                Column(Modifier.testTag("report_label")) { Text("Weekly report") }
            }

            Section("5. A disabled button", "A tap is refused as disabled, not sent.")
            Button(onClick = { lastEvent = "disabled button (should never happen)" }, enabled = false, modifier = Modifier.testTag("disabled_button")) {
                Text("Publish")
            }
        }
    }
}

/** A feed holding carousels, and two unrelated lists side by side. The tab itself does not scroll. */
@Composable
private fun ScrollTab() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Section(
            "6a. A feed of carousels",
            "Scroll to testTag feed_end. containerTag feed swipes the feed itself; containerTag feed_section " +
                "(not scrollable) resolves to the feed, the outermost list, over the carousels inside it.",
        )
        Column(Modifier.fillMaxWidth().weight(1f).testTag("feed_section")) {
            LazyColumn(Modifier.fillMaxSize().testTag("feed")) {
                items((1..FEED_ROWS).toList()) { row ->
                    if (row % CAROUSEL_EVERY == 0) Carousel(row) else Text("Feed row $row", Modifier.padding(16.dp))
                }
                item { Text("Last row of the feed", Modifier.padding(16.dp).testTag("feed_end")) }
            }
        }

        Section(
            "6b. Two unrelated lists",
            "Neither contains the other, so a scroll without containerTag is refused. containerTag list_right picks one.",
        )
        Row(Modifier.fillMaxWidth().height(160.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("left" to "Left", "right" to "Right").forEach { (tag, label) ->
                LazyColumn(Modifier.weight(1f).fillMaxHeight().background(Color(0x14000000)).testTag("list_$tag")) {
                    items((1..SIDE_LIST_ROWS).toList()) { Text("$label $it", Modifier.padding(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Carousel(row: Int) {
    LazyRow(
        Modifier.fillMaxWidth().height(96.dp).testTag("carousel_$row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((1..CAROUSEL_CARDS).toList()) { card ->
            Box(Modifier.size(120.dp, 80.dp).background(Color(0xFFC5CAE9)), contentAlignment = Alignment.Center) {
                Text("Card $row.$card")
            }
        }
    }
}

/** Exactly two findings expected: the unlabelled box and the small target. The list is clean. */
@Composable
private fun AuditTab() {
    var taps by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section(
            "7. Accessibility audit",
            "Expect two findings: the blue box has a test tag but nothing to announce, and the red one " +
                "is labelled but 24dp. The list below is tagged and holds labelled rows, so it is not a finding.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Color(0xFF7986CB)).clickable { taps++ }.testTag("audit_unlabelled"))
            Box(
                Modifier.size(24.dp)
                    .background(Color(0xFFE57373))
                    .clickable { taps++ }
                    .semantics { contentDescription = "Dismiss" }
                    .testTag("audit_small_target"),
            )
            Text("Tapped $taps time(s)")
        }
        LazyColumn(Modifier.fillMaxWidth().height(240.dp).testTag("audit_list")) {
            items((1..AUDIT_ROWS).toList()) {
                // 56dp rather than exactly 48dp, so rounding at an odd density cannot make it a finding.
                Row(Modifier.fillMaxWidth().height(56.dp).clickable { taps++ }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Option $it")
                }
            }
        }
    }
}

/**
 * A UI that never goes idle. `uiautomator dump` waits for a quiet accessibility event stream
 * and gives up with "could not get idle state"; a ticking text sends a content-change event
 * on every update.
 */
@Composable
private fun BusyTab() {
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        // While it runs no capture works, so no element tool can reach the switch to stop it.
        if (busy) {
            delay(BUSY_MILLIS)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(
            "8. A screen that never settles",
            "While this runs, android_get_ui_tree should fail as refused (uiautomator never sees the UI idle), " +
                "or as timed out on a device that keeps waiting. It stops by itself after 30 seconds.",
        )
        SwitchRow("Keep the UI busy for 30 seconds", busy, "busy_switch") { busy = it }
        if (busy) BusyTicker()
    }
}

@Composable
private fun BusyTicker() {
    val transition = rememberInfiniteTransition(label = "busy")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phase",
    )
    Text("Ticking: ${(phase * 1_000).toInt()}", modifier = Modifier.testTag("busy_ticker"))
    LinearProgressIndicator(progress = { phase }, modifier = Modifier.fillMaxWidth())
}

/**
 * The toolbar above is AppCompat, a View with resource ids outside Compose, so this whole
 * screen is hybrid. The embedded TextView is the known gap: a View id *inside* Compose.
 */
@Composable
private fun HybridTab(exposeTags: Boolean, onExposeTags: (Boolean) -> Unit) {
    var showInterop by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(
            "9. Views and Compose together",
            "The toolbar is a View with resource ids. Turn test tags off: android_get_ui_tree should " +
                "report a hybrid screen with no exposed Compose test tags, because View ids outside Compose no longer count.",
        )
        SwitchRow("Expose test tags", exposeTags, "expose_tags_switch", onExposeTags)

        Section(
            "10. Known gap: a View inside Compose",
            "With tags off, show the embedded View below. Its id sits inside the Compose host, so the " +
                "plugin still reports Compose tags as exposed. This is a documented gap, not a pass.",
        )
        SwitchRow("Show a View inside Compose", showInterop, "interop_switch") { showInterop = it }
        if (showInterop) {
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        id = R.id.compose_interop_view
                        text = "A TextView embedded with AndroidView"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val FEED_ROWS = 24
private const val CAROUSEL_EVERY = 4
private const val CAROUSEL_CARDS = 10
private const val SIDE_LIST_ROWS = 40
private const val AUDIT_ROWS = 20
private const val BUSY_MILLIS = 30_000L
