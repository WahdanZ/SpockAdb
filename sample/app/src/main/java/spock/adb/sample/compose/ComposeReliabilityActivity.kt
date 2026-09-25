package spock.adb.sample.compose

import android.os.Bundle
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import spock.adb.sample.R
import spock.adb.sample.SampleActivity

/**
 * Fixtures for the element tools' refusals, scroll-container resolution, the accessibility
 * audit, capture failures and bounded waits, hybrid tag detection, the capture summary and what is
 * in view — one tab each, so a capture of one tab is not muddied by another's fixtures. Actions
 * with an expected result share the Window tab, which has room. The calls to run and
 * what each should answer are in docs/COMPOSE-SUPPORT-PLAN.md, "Device checks", and behind each
 * card's info button.
 *
 * Headings describe what to try without repeating a fixture's own text: element tools match
 * text by substring, so a heading that quoted it would become one more candidate.
 *
 * The app bar stays the AppCompat one rather than a Compose `TopAppBar`: its title and Up button
 * are the Views outside Compose that make this a hybrid screen, which fixture 9 depends on.
 */
class ComposeReliabilityActivity : SampleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Compose reliability"
        setContent { SampleTheme { ReliabilityScreen() } }
    }
}

private enum class FixtureTab(val title: String, val tag: String, val icon: ImageVector) {
    TAPS("Taps", "tab_taps", Icons.Filled.ThumbUp),
    SCROLL("Scroll", "tab_scroll", Icons.AutoMirrored.Filled.List),
    AUDIT("Audit", "tab_audit", Icons.Filled.Search),
    BUSY("Busy", "tab_busy", Icons.Filled.Refresh),
    HYBRID("Hybrid", "tab_hybrid", Icons.Filled.Build),
    WINDOW("Window", "tab_window", Icons.Filled.AccountBox),
    FOLD("Fold", "tab_fold", Icons.Filled.KeyboardArrowDown),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ReliabilityScreen() {
    var tab by rememberSaveable { mutableStateOf(FixtureTab.TAPS) }
    var exposeTags by rememberSaveable { mutableStateOf(true) }
    // Above every tab, so a tap that lands somewhere unexpected shows wherever you are.
    var lastEvent by rememberSaveable { mutableStateOf(NOTHING_TAPPED) }
    var sheet by remember { mutableStateOf<Fixture?>(null) }
    val showInfo: (Fixture) -> Unit = { sheet = it }

    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = exposeTags },
        // The AppCompat app bar above has already taken the system bars' space.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                // Fixed, not scrollable: a tab clipped at the screen edge is an audit finding (too small, or
                // no visible label) and missing from the capture. Icons carry no description, since the
                // label already names the tab and a description would be one more string to match.
                TabRow(selectedTabIndex = tab.ordinal) {
                    FixtureTab.entries.forEach {
                        Tab(selected = tab == it, onClick = { tab = it }, modifier = Modifier.testTag(it.tag)) {
                            Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(it.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(2.dp))
                                Text(it.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
                LastTapBar(lastEvent)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                FixtureTab.TAPS -> TapsTab(showInfo) { lastEvent = it }
                FixtureTab.SCROLL -> ScrollTab(showInfo)
                FixtureTab.AUDIT -> AuditTab(showInfo)
                FixtureTab.BUSY -> BusyTab(showInfo) { lastEvent = it }
                FixtureTab.HYBRID -> HybridTab(exposeTags, showInfo) { exposeTags = it }
                FixtureTab.WINDOW -> WindowTab(showInfo) { lastEvent = it }
                FixtureTab.FOLD -> FoldTab(showInfo) { lastEvent = it }
            }
        }
    }

    sheet?.let { FixtureSheet(it) { sheet = null } }
}

/** Where every tapped control reports itself; it flashes on each new tap. Not clickable, so never an audit finding. */
@Composable
private fun LastTapBar(lastEvent: String) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(lastEvent) {
        if (lastEvent != NOTHING_TAPPED) {
            flash = true
            delay(FLASH_MILLIS)
            flash = false
        }
    }
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (flash) colors.tertiaryContainer else colors.secondaryContainer, label = "flash")

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(shape = CircleShape, color = container, contentColor = colors.onSecondaryContainer) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Last tap: $lastEvent", modifier = Modifier.testTag("last_event"))
            }
        }
    }
}

/** A tab's cards, spaced evenly; [scroll] only where the tab may scroll as a whole. */
@Composable
private fun CardColumn(scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
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

/**
 * Ambiguous, identical, shared-target and disabled tap targets. All five must stay on screen
 * together on a phone: a fixture scrolled out of view is missing from the capture, and its
 * check would fail as not found instead of the refusal it tests.
 */
@Composable
private fun TapsTab(onInfo: (Fixture) -> Unit, onEvent: (String) -> Unit) {
    CardColumn(scroll = true) {
        FixtureCard(Fixtures.ONE_LABEL, onInfo) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("a", "b").forEach { form ->
                    Column(Modifier.weight(1f).testTag("form_$form")) {
                        Text("Form ${form.uppercase()}")
                        Button(onClick = { onEvent("form ${form.uppercase()} button") }, modifier = Modifier.testTag("form_${form}_button")) {
                            Text("Save")
                        }
                    }
                }
            }
        }

        FixtureCard(Fixtures.TITLE_AND_BUTTON, onInfo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Discard changes?", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onEvent("confirm button") }, modifier = Modifier.testTag("confirm_button")) {
                    Text("Discard")
                }
            }
        }

        FixtureCard(Fixtures.IDENTICAL_ROWS, onInfo) {
            Column(Modifier.testTag("identical_rows")) {
                repeat(2) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onEvent("an identical row") }
                            .semantics { contentDescription = "Archive this item" }
                            .padding(8.dp),
                    ) { Text("Archive") }
                }
            }
        }

        FixtureCard(Fixtures.ROW_AND_ICON, onInfo) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onEvent("report row") }
                    .semantics { contentDescription = "Share report" }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(android.R.drawable.ic_menu_share), contentDescription = "Share report")
                Column(Modifier.testTag("report_label")) { Text("Weekly report") }
            }
        }

        FixtureCard(Fixtures.DISABLED, onInfo) {
            Button(onClick = { onEvent("disabled button (should never happen)") }, enabled = false, modifier = Modifier.testTag("disabled_button")) {
                Text("Publish")
            }
        }
    }
}

/** A feed holding carousels, and two unrelated lists side by side. The tab itself does not scroll. */
@Composable
private fun ScrollTab(onInfo: (Fixture) -> Unit) {
    CardColumn {
        FixtureCard(Fixtures.FEED, onInfo, Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().weight(1f).testTag("feed_section")) {
                LazyColumn(Modifier.fillMaxSize().testTag("feed")) {
                    items((1..FEED_ROWS).toList()) { row ->
                        if (row % CAROUSEL_EVERY == 0) Carousel(row) else Text("Feed row $row", Modifier.padding(16.dp))
                    }
                    item { Text("Last row of the feed", Modifier.padding(16.dp).testTag("feed_end")) }
                }
            }
        }

        FixtureCard(Fixtures.SIBLING_LISTS, onInfo) {
            Row(Modifier.fillMaxWidth().height(160.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("left" to "Left", "right" to "Right").forEach { (tag, label) ->
                    LazyColumn(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .testTag("list_$tag"),
                    ) {
                        items((1..SIDE_LIST_ROWS).toList()) { Text("$label $it", Modifier.padding(8.dp)) }
                    }
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
            Box(
                Modifier.size(120.dp, 80.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("Card $row.$card", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * Exactly two findings expected: the unlabelled box and the small target. The list is clean; its
 * fifth row is always cut in half by the list's edge, which the audit skips rather than reports.
 */
@Composable
private fun AuditTab(onInfo: (Fixture) -> Unit) {
    var taps by remember { mutableIntStateOf(0) }

    CardColumn {
        FixtureCard(Fixtures.AUDIT, onInfo) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Color(0xFF7986CB)).clickable { taps++ }.testTag("audit_unlabelled"))
                    // Compose widens a clickable's reported bounds to the ViewConfiguration's minimum touch
                    // target, 48dp, so a plain 24dp box was dumped at 48dp and never flagged. Without the
                    // widening the dump shows what a finger actually has to hit.
                    CompositionLocalProvider(LocalViewConfiguration provides unwidened(LocalViewConfiguration.current)) {
                        Box(
                            Modifier.size(24.dp)
                                .background(Color(0xFFE57373))
                                .clickable { taps++ }
                                .semantics { contentDescription = "Dismiss" }
                                .testTag("audit_small_target"),
                        )
                    }
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
    }
}

/** [base] without the minimum touch target, so a small control's reported bounds are its own. */
@Composable
private fun unwidened(base: ViewConfiguration): ViewConfiguration = remember(base) {
    object : ViewConfiguration by base {
        override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
    }
}

/**
 * A UI that never goes idle. `uiautomator dump` waits for a quiet accessibility event stream
 * and gives up with "could not get idle state"; a ticking View sends a content-change event
 * on every update.
 *
 * Below it, the wait fixtures: things that change on their own a few seconds after a button.
 * They share this tab rather than having their own, because an eighth tab narrows every tab
 * under 48dp and fixture 7's audit would report them all.
 */
@Composable
private fun BusyTab(onInfo: (Fixture) -> Unit, onEvent: (String) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        // While it runs no capture works, so no element tool can reach the switch to stop it.
        if (busy) {
            delay(BUSY_MILLIS)
            busy = false
        }
    }

    CardColumn {
        FixtureCard(Fixtures.NEVER_IDLE, onInfo) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow("Keep the UI busy for 30 seconds", busy, "busy_switch") { busy = it }
                if (busy) BusyTicker()
            }
        }
        WaitCards(onInfo, onEvent)
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
    // Compose sends accessibility events only once it sees an accessibility service, and the dump's
    // own connection is not one, so the Compose ticker alone let captures through. A View sends
    // a content-change event whenever accessibility is on at all, including during a dump.
    AndroidView(
        factory = { TextView(it) },
        update = { it.text = "View ticker: ${(phase * 1_000).toInt()}" },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The toolbar above is AppCompat, a View with resource ids outside Compose, so this whole
 * screen is hybrid. The embedded TextView is the known gap: a View id *inside* Compose.
 */
@Composable
private fun HybridTab(exposeTags: Boolean, onInfo: (Fixture) -> Unit, onExposeTags: (Boolean) -> Unit) {
    var showInterop by remember { mutableStateOf(false) }

    CardColumn {
        FixtureCard(Fixtures.HYBRID, onInfo) {
            SwitchRow("Expose test tags", exposeTags, "expose_tags_switch", onExposeTags)
        }

        FixtureCard(Fixtures.KNOWN_GAP, onInfo) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

/**
 * What a capture's summary line says about the window, the viewport and the density. The app
 * shows its own view of the display, so the summary can be checked against something other than
 * the plugin: rotation here is the same quarter-turn count `uiautomator` writes.
 *
 * Below them, fixture 18: actions with an expected result. It shares this tab because the tab is
 * quiet and has room; an eighth tab would narrow every tab under 48dp.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowTab(onInfo: (Fixture) -> Unit, onEvent: (String) -> Unit) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val rotation = view.display?.rotation
    // The decor view is the window; its size is known once laid out, and changes with rotation.
    var window by remember { mutableStateOf(IntSize.Zero) }

    Box(Modifier.fillMaxSize().onGloballyPositioned { window = IntSize(view.rootView.width, view.rootView.height) }) {
        CardColumn {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(
                    "This app sees: rotation ${rotation ?: "unknown"}, ${configuration.densityDpi} dpi, " +
                        "window ${window.width}x${window.height} px",
                    modifier = Modifier.padding(16.dp).testTag("window_metrics"),
                )
            }
            FixtureCard(Fixtures.SECOND_WINDOW, onInfo) {
                Button(onClick = { dialogOpen = true }, modifier = Modifier.testTag("open_dialog")) { Text("Open a dialog") }
            }
            FixtureCard(Fixtures.ROTATION, onInfo)
            FixtureCard(Fixtures.DENSITY, onInfo)
            FixtureCard(Fixtures.LOST_DEVICE, onInfo)
            OutcomeCard(onInfo, onEvent)
        }
    }

    if (dialogOpen) {
        Dialog(onDismissRequest = { dialogOpen = false }) {
            // A dialog is its own composition, so the screen's testTagsAsResourceId does not reach it.
            Surface(
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("dialog_surface"),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This is a separate window. Capture now, then close it.")
                    Button(onClick = { dialogOpen = false }, modifier = Modifier.testTag("dialog_close")) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/**
 * In the tree, in the viewport, and in view are three different answers. A short scrolling column
 * holds a row cut in half by its bottom edge and, further down, a row that starts out of view.
 * The column has a fixed height, so both sit in the same place on any phone.
 */
@Composable
private fun FoldTab(onInfo: (Fixture) -> Unit, onEvent: (String) -> Unit) {
    CardColumn {
        FixtureCard(Fixtures.BELOW_FOLD, onInfo) {
            Column(
                Modifier.fillMaxWidth()
                    .height(FOLD_HEIGHT_DP.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .verticalScroll(rememberScrollState())
                    .testTag("fold_scroll"),
            ) {
                // Three 48dp rows, then a 48dp row starting 24dp above the column's 168dp bottom edge.
                repeat(FOLD_ROWS_ABOVE) { FoldRow("Spacer row ${it + 1}") }
                FoldRow("Cut by the edge", Modifier.testTag("half_visible").clickable { onEvent("half-visible row") })
                repeat(FOLD_ROWS_BELOW) { FoldRow("Spacer row ${FOLD_ROWS_ABOVE + it + 1}") }
                FoldRow("Last in the column", Modifier.testTag("below_fold").clickable { onEvent("row below the fold") })
            }
        }
    }
}

@Composable
private fun FoldRow(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(label)
    }
}

/**
 * Four things that change on their own [WAIT_MILLIS] after their button: one appears, one goes, a
 * button becomes enabled and a switch turns on. Each button resets its target and starts its timer
 * again, and switching tabs resets them all.
 *
 * The targets are tagged `wait_…`, and no other tag contains those, so each wait's selector has one
 * candidate.
 */
@Composable
private fun WaitCards(onInfo: (Fixture) -> Unit, onEvent: (String) -> Unit) {
    FixtureCard(Fixtures.ARRIVES_AND_LEAVES, onInfo) {
        DelayedChange("Show in 3 s", "start_arrival", onStart = { onEvent("arrival timer started") }) { changed ->
            if (changed) WaitChip("Arrived", "wait_appears")
        }
        DelayedChange("Remove in 3 s", "start_departure", onStart = { onEvent("departure timer started") }) { changed ->
            if (!changed) WaitChip("Leaving", "wait_disappears")
        }
    }
    FixtureCard(Fixtures.CHANGES_STATE, onInfo) {
        DelayedChange("Enable in 3 s", "start_enable", onStart = { onEvent("enable timer started") }) { changed ->
            Button(
                onClick = { onEvent("proceed button") },
                enabled = changed,
                modifier = Modifier.testTag("wait_enable_target"),
            ) { Text("Proceed") }
        }
        DelayedChange("Flip in 3 s", "start_flip", onStart = { onEvent("flip timer started") }) { changed ->
            // Also toggleable by hand; the timer only ever turns it on.
            var checked by remember(changed) { mutableStateOf(changed) }
            SwitchRow("Flips by itself", checked, "wait_toggle_target") { checked = it }
        }
    }
}

/**
 * A start button beside [target], which is shown with `changed = false` until [WAIT_MILLIS] after
 * the button was last pressed. Pressing it again puts the target back and restarts the timer.
 */
@Composable
private fun DelayedChange(
    label: String,
    tag: String,
    onStart: () -> Unit,
    target: @Composable (changed: Boolean) -> Unit,
) {
    var run by remember { mutableIntStateOf(0) }
    var changed by remember { mutableStateOf(false) }
    LaunchedEffect(run) {
        if (run > 0) {
            delay(WAIT_MILLIS)
            changed = true
        }
    }

    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FilledTonalButton(
            onClick = {
                changed = false
                run++
                onStart()
            },
            modifier = Modifier.testTag(tag),
        ) { Text(label) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { target(changed) }
    }
}

/**
 * An action and what it should lead to. One button shows its result [ORDER_MILLIS] after a press
 * and keeps showing it, so a second press finds the result already there; the other changes
 * nothing that is expected of it. Every press is counted, so a repeated dispatch shows. Switching
 * tabs resets both.
 */
@Composable
private fun OutcomeCard(onInfo: (Fixture) -> Unit, onEvent: (String) -> Unit) {
    var orders by remember { mutableIntStateOf(0) }
    var otherPresses by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(orders) {
        if (orders > 0) {
            delay(ORDER_MILLIS)
            placed = true
        }
    }

    FixtureCard(Fixtures.OUTCOMES, onInfo) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    orders++
                    onEvent("order button")
                },
                modifier = Modifier.testTag("order_button"),
            ) { Text("Place order") }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (placed) WaitChip("Order placed", "order_status")
            }
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    otherPresses++
                    onEvent("inert button")
                },
                modifier = Modifier.testTag("inert_button"),
            ) { Text("No effect") }
            Text("Presses: $orders + $otherPresses", modifier = Modifier.testTag("press_counts"))
        }
    }
}

@Composable
private fun WaitChip(text: String, tag: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.testTag(tag)) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

private const val NOTHING_TAPPED = "nothing yet"
private const val FLASH_MILLIS = 1_200L
private const val FEED_ROWS = 24
private const val CAROUSEL_EVERY = 4
private const val CAROUSEL_CARDS = 10
private const val SIDE_LIST_ROWS = 40
private const val AUDIT_ROWS = 20
private const val BUSY_MILLIS = 30_000L
private const val FOLD_HEIGHT_DP = 168
private const val FOLD_ROWS_ABOVE = 3
private const val FOLD_ROWS_BELOW = 6
private const val WAIT_MILLIS = 3_000L
private const val ORDER_MILLIS = 1_500L
