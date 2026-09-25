package spock.adb.sample.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One step of a check: an MCP call or shell command to copy, or an instruction to follow. */
internal class Step(val text: String, val copyable: Boolean = true)

internal class Check(val steps: List<Step>, val expected: String)

/**
 * A fixture card's heading and the checks its info sheet shows.
 *
 * [title] and [summary] are on screen whenever the tab is, so neither may contain any fixture's
 * text or description: element tools match by substring, and a heading that did would become one
 * more candidate. The [checks] quote fixture text freely — they only exist inside the sheet, which
 * is not composed while it is closed.
 *
 * The checks restate docs/COMPOSE-SUPPORT-PLAN.md, "Device checks"; change the two together.
 */
internal class Fixture(val number: String, val title: String, val summary: String, val checks: List<Check>)

private fun call(text: String) = Step(text)
private fun instruction(text: String) = Step(text, copyable = false)

internal object Fixtures {
    val ONE_LABEL = Fixture(
        "1", "One label, two forms", "A shared label in two containers",
        listOf(
            Check(listOf(call("""android_tap_element {text: "Save"}""")), "Refused, two Save candidates."),
            Check(
                listOf(call("""android_tap_element {text: "Save", containerTag: "form_a"}""")),
                "Taps that form's button; Last tap names it.",
            ),
            Check(
                listOf(call("""android_tap_element {testTag: "form_b_button"}""")),
                "Taps that form's button; Last tap names it.",
            ),
            Check(
                listOf(call("""android_assert_enabled {text: "Save"}""")),
                "Row 19. FAIL, not PASS: the selector is ambiguous, and both Save candidates are listed rather than " +
                    "one checked. Nothing is tapped.",
            ),
        ),
    )
    val TITLE_AND_BUTTON = Fixture(
        "2", "A title and its button", "One word, two meanings",
        listOf(
            Check(
                listOf(call("""android_tap_element {text: "Discard"}""")),
                "Refused, naming Discard changes? and Discard; suggests exact.",
            ),
            Check(listOf(call("""android_tap_element {text: "Discard", exact: true}""")), "Taps confirm_button."),
        ),
    )
    val IDENTICAL_ROWS = Fixture(
        "3", "Two identical rows", "Nothing can tell them apart",
        listOf(
            Check(
                listOf(call("""android_tap_element {text: "Archive"}""")),
                "Refused: no selector field can tell the candidates apart.",
            ),
        ),
    )
    val ROW_AND_ICON = Fixture(
        "4", "A row and its icon, one description", "Two matches, one target",
        listOf(
            Check(
                listOf(call("""android_tap_element {contentDescription: "Share report"}""")),
                "One tap on the row, not a refusal.",
            ),
            Check(
                listOf(call("""android_tap_element {text: "Weekly report", containerTag: "report_label"}""")),
                "Refused: action target is outside the selected container.",
            ),
        ),
    )
    val DISABLED = Fixture(
        "5", "A disabled button", "Refused before anything is sent",
        listOf(
            Check(
                listOf(call("""android_tap_element {testTag: "disabled_button"}""")),
                "Refused as disabled; nothing dispatched.",
            ),
        ),
    )
    val FEED = Fixture(
        "6a", "A feed of carousels", "Nested lists; the outermost wins",
        listOf(
            Check(
                listOf(call("""android_scroll_to_element {testTag: "feed_end", containerTag: "feed", maxSwipes: 20}""")),
                "Found; the feed is swiped directly.",
            ),
            Check(
                listOf(
                    call("""android_scroll_to_element {testTag: "feed_end", containerTag: "feed_section", maxSwipes: 20}"""),
                ),
                "Found; the feed wins over the carousels inside it.",
            ),
        ),
    )
    val SIBLING_LISTS = Fixture(
        "6b", "Two unrelated lists", "Neither holds the other",
        listOf(
            Check(
                listOf(call("""android_scroll_to_element {text: "Right 35"}""")),
                "Refused: several scrollable containers.",
            ),
            Check(listOf(call("""android_scroll_to_element {text: "Right 35", containerTag: "list_right"}""")), "Found."),
        ),
    )
    val AUDIT = Fixture(
        "7", "Accessibility faults", "Exactly two should be found",
        listOf(
            Check(
                listOf(call("android_accessibility_audit {}")),
                "Two findings: audit_unlabelled (no accessible text, despite its tag) and audit_small_target " +
                    "(24dp, below 48dp). Nothing for audit_list or its rows. The coverage note says 1 control at " +
                    "a scroll container's edge was skipped: the half-visible fifth row.",
            ),
        ),
    )
    val NEVER_IDLE = Fixture(
        "8", "A screen that never settles", "Captures fail while it runs",
        listOf(
            Check(
                listOf(call("""android_tap_element {testTag: "busy_switch"}"""), call("android_get_ui_tree {}")),
                "Capture fails as DUMP_REFUSED (\"could not get idle state\"), after about 12 s on an API 34 " +
                    "emulator; TIMED_OUT where uiautomator waits past 30 s. The switch turns itself off after 30 s.",
            ),
            Check(
                listOf(
                    call("""android_tap_element {testTag: "busy_switch"}"""),
                    call("""android_wait_for_element {testTag: "busy_switch", until: "unchecked", timeoutMs: 45000}"""),
                ),
                "PASS after about 33 s, once the switch turns itself off: 3 observations on an API 34 emulator, " +
                    "the first 2 refused while the ticker ran, which the result counts.",
            ),
            Check(
                listOf(
                    call("""android_tap_element {testTag: "busy_switch"}"""),
                    call("""android_wait_for_element {testTag: "busy_switch", until: "unchecked", timeoutMs: 10000}"""),
                ),
                "FAIL: timed out after 1 observation, about 13 s. The first capture always runs to completion, and " +
                    "the ticker made it a refused one; the result says it took about 13 s, past the 10.0 s limit, " +
                    "and counts 1 refused capture. Let the switch turn itself off before the next check.",
            ),
        ),
    )
    val HYBRID = Fixture(
        "9", "Views and Compose together", "The toolbar is a View",
        listOf(
            Check(
                listOf(call("""android_tap_element {testTag: "expose_tags_switch"}"""), call("android_get_ui_tree {}")),
                "Hybrid screen, no exposed Compose tags observed: the toolbar's View IDs do not count.",
            ),
            Check(listOf(call("""android_tap_element {text: "Expose test tags"}""")), "Turns the tags back on."),
        ),
    )
    val KNOWN_GAP = Fixture(
        "10", "Known gap: a View inside Compose", "A documented gap, not a pass",
        listOf(
            Check(
                listOf(
                    instruction("With test tags off (fixture 9):"),
                    call("""android_tap_element {text: "Show a View inside Compose"}"""),
                    call("android_get_ui_tree {}"),
                ),
                "Still reports Compose tags as visible, because of the AndroidView ID. Expected until the gap " +
                    "is closed.",
            ),
        ),
    )
    val SECOND_WINDOW = Fixture(
        "11", "A dialog on top", "Dialog or activity: which is read",
        listOf(
            Check(
                listOf(instruction("With the dialog closed:"), call("android_get_ui_tree {}")),
                "First line: Observed on <serial>, window spock.adb.sample, <ISO-8601 instant> (host clock, " +
                    "<n> s capture), viewport <W>x<H> rot 0, <dpi> dpi, source: uiautomator accessibility dump " +
                    "of the active window. Second line starts Limits: and, tags being exposed, names the " +
                    "AndroidView gap. Rotation and dpi match the tab's own This app sees line.",
            ),
            Check(
                listOf(call("""android_tap_element {testTag: "open_dialog"}"""), call("android_get_ui_tree {}")),
                "Unverified which window is dumped — record it. If the dialog: a viewport smaller than the " +
                    "display with an at (x,y) origin, and dialog_close in the tree. If the activity: the same " +
                    "viewport as before and no dialog_close. Either way window is the package that owns the " +
                    "dumped window.",
            ),
        ),
    )
    val ROTATION = Fixture(
        "12", "Rotation", "Turn the device, then capture",
        listOf(
            Check(
                listOf(instruction("Rotate the device, then:"), call("android_get_ui_tree {}")),
                "rot equals the rotation the tab shows (1 or 3 for landscape); the viewport's width and " +
                    "height swap.",
            ),
        ),
    )
    val DENSITY = Fixture(
        "13", "Density", "Change dpi, then capture",
        listOf(
            Check(
                listOf(
                    call("adb shell wm density 320"),
                    call("android_accessibility_audit {}"),
                    instruction("Afterwards:"),
                    call("adb shell wm density reset"),
                ),
                "Summary says 320 dpi, and the coverage note says touch-target estimates use 320dpi.",
            ),
        ),
    )
    val BELOW_FOLD = Fixture(
        "15", "In the tree, in view, or neither", "A short column with more below",
        listOf(
            Check(
                listOf(call("""android_assert_visible {testTag: "below_fold"}""")),
                "FAIL: nothing matched, pointing to android_scroll_to_element. The row is left out of the capture " +
                    "while it is scrolled out of the column, so it is absent rather than outside the viewport.",
            ),
            Check(
                listOf(call("""android_tap_element {testTag: "below_fold"}""")),
                "Refused as no match, pointing to android_scroll_to_element; nothing dispatched, Last tap unchanged.",
            ),
            Check(
                listOf(call("""android_find_ui_element {testTag: "half_visible"}"""), call("android_get_ui_tree {}")),
                "One match, partly in the viewport, cut at its scroll container's edge; how much is out of view is " +
                    "unknown. The tree marks it [partly in viewport, clipped by scroll container] and its text " +
                    "[may be clipped by scroll container]; nothing else in the card is marked.",
            ),
            Check(
                listOf(call("""android_tap_element {testTag: "half_visible"}""")),
                "Tapped at the centre of its part in view, not of its bounds; Last tap names the half-visible row.",
            ),
            Check(
                listOf(
                    call("""android_scroll_to_element {testTag: "below_fold"}"""),
                    call("""android_assert_visible {testTag: "below_fold"}"""),
                    call("""android_tap_element {testTag: "below_fold"}"""),
                    instruction("Afterwards, switch tabs and back to scroll the column to the top."),
                ),
                "Found after about 5 swipes; PASS, ending (occlusion not checked); Last tap names the row below " +
                    "the fold.",
            ),
        ),
    )
    val ARRIVES_AND_LEAVES = Fixture(
        "16", "Something arrives, something leaves", "Shown or removed 3 s after a button",
        listOf(
            Check(
                listOf(
                    call("""android_tap_element {testTag: "start_arrival"}"""),
                    call("""android_wait_for_element {testTag: "wait_appears", until: "visible", timeoutMs: 10000}"""),
                ),
                "PASS: visible, within the viewport. A dump takes 2–7 s on an API 34 emulator, so the timer usually " +
                    "fires during the first one: 1 observation, about 5 s.",
            ),
            Check(
                listOf(
                    call("""android_tap_element {testTag: "start_arrival"}"""),
                    call("""android_wait_for_element {testTag: "wait_appears", until: "visible", timeoutMs: 1000}"""),
                ),
                "A verdict from 1 observation, which always completes: a dump takes 2–7 s, so the result says the " +
                    "first observation ran past the 1.0 s limit. PASS if the timer fired during the dump, else FAIL: " +
                    "timed out, nothing matched.",
            ),
            Check(
                listOf(
                    call("""android_tap_element {testTag: "start_departure"}"""),
                    call("""android_wait_for_element {testTag: "wait_disappears", until: "gone"}"""),
                ),
                "PASS: gone, nothing in the tree matches, after 1 observation. The button puts it back.",
            ),
            Check(
                listOf(call("""android_wait_for_element {testTag: "wait_never_there", until: "gone"}""")),
                "PASS on the first observation: gone is met at once by something that was never there.",
            ),
        ),
    )
    val CHANGES_STATE = Fixture(
        "17", "A button and a switch that change", "Enabled or turned on 3 s after a button",
        listOf(
            Check(
                listOf(
                    call("""android_tap_element {testTag: "start_enable"}"""),
                    call("""android_wait_for_element {testTag: "wait_enable_target", until: "enabled"}"""),
                ),
                "PASS: enabled, after 1 or 2 observations.",
            ),
            Check(
                listOf(
                    call("""android_tap_element {testTag: "start_flip"}"""),
                    call("""android_wait_for_element {testTag: "wait_toggle_target", until: "checked"}"""),
                ),
                "PASS: checked, after 1 or 2 observations.",
            ),
        ),
    )
    val OUTCOMES = Fixture(
        "18", "An action and its result", "Checked afterwards, never sent twice",
        listOf(
            Check(
                listOf(
                    instruction("Start fresh: switch tabs and back, which resets the card."),
                    call("""android_tap_element {testTag: "order_button", expectTestTag: "order_status"}"""),
                ),
                "VERIFIED, not an error: order_status visible after 1 or 2 observations, and not there before the " +
                    "tap. Presses: 1 + 0, and Last tap names the order button.",
            ),
            Check(
                listOf(
                    instruction("Straight after the check above:"),
                    call("""android_tap_element {testTag: "order_button", expectTestTag: "order_status"}"""),
                ),
                "INCONCLUSIVE, an error: order_status was already visible before the tap, so seeing it proves " +
                    "nothing. The tap was dispatched once and not repeated: Presses: 2 + 0.",
            ),
            Check(
                listOf(
                    call(
                        """android_tap_element {testTag: "inert_button", expectTestTag: "inert_result", """ +
                            """expectTimeoutMs: 3000}""",
                    ),
                ),
                "NOT OBSERVED, an error: dispatched once and not repeated. The second count goes up by exactly 1.",
            ),
            Check(
                listOf(
                    instruction("In the assistant, with expectTimeoutMs: 60000, press Stop once the tap is sent:"),
                    call(
                        """android_tap_element {testTag: "inert_button", expectTestTag: "inert_result", """ +
                            """expectTimeoutMs: 60000}""",
                    ),
                ),
                "CANCELLED while checking; the tap was dispatched once. The second count goes up by exactly 1.",
            ),
            Check(
                listOf(instruction("The same, but press Stop within a second, before the tap is sent.")),
                "CANCELLED while finding the target; nothing dispatched, both counts unchanged.",
            ),
        ),
    )
    val LOST_DEVICE = Fixture(
        "14", "A lost device", "Unplug, then capture",
        listOf(
            Check(
                listOf(
                    instruction("Disconnect the device (adb disconnect, or unplug), then:"),
                    call("android_get_ui_tree {}"),
                    instruction("Then press Capture UI in the Inspector."),
                ),
                "MCP: DEVICE_UNAVAILABLE naming the serial and pointing to android_list_devices. Inspector: " +
                    "the same failure, telling the person to reconnect or choose another device, with no tool " +
                    "name. Its status line after a good capture ends · viewport <W>x<H> rot <r> · <dpi> dpi.",
            ),
        ),
    )
}

/**
 * A fixture's number, title and one-line summary, with an info button, above the fixture itself.
 *
 * The info button is a 48dp `IconButton` with a label unique to the fixture, so the audit tab
 * gains no touch-target or duplicate-label finding from it.
 *
 * Kept compact on purpose: all five Taps cards must fit on one phone screen.
 */
@Composable
internal fun FixtureCard(
    fixture: Fixture,
    onInfo: (Fixture) -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = if (content == null) 4.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberBadge(fixture.number)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fixture.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        fixture.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onInfo(fixture) }) {
                    Icon(Icons.Outlined.Info, contentDescription = "How to test fixture ${fixture.number}")
                }
            }
            // Compose widens a target under 48dp to 48dp for touch, and the widened bounds cover
            // whatever they overlap. Without this gap the identical rows below would clip the info
            // button's reported bounds under 48dp.
            if (content != null) {
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

@Composable
private fun NumberBadge(number: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** What to call for [fixture] and what it should answer, with each call one tap from the clipboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FixtureSheet(fixture: Fixture, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${fixture.number}. ${fixture.title}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Close this sheet before capturing: the calls below quote the fixtures' own text, so while it is " +
                    "open they would match too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            fixture.checks.forEach { CheckBlock(it) }
        }
    }
}

@Composable
private fun CheckBlock(check: Check) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            check.steps.forEach { step ->
                if (step.copyable) CopyableCall(step.text) else Text(step.text, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(2.dp))
            Text("Expected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(check.expected, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CopyableCall(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        }) { Text(if (copied) "Copied" else "Copy") }
    }
}
