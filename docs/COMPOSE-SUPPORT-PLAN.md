# Jetpack Compose support plan for Spock ADB

Research date: 23 September 2026. Status: first Phase 1 increment implemented locally; later work remains open.

## Product direction

Make Spock ADB the place to inspect a running Compose screen, reproduce a problem, verify the result, and turn the workflow into a regression test. Build on the existing UI Inspector, device controls, Logcat, and MCP tools.

“Compose” here means Android Jetpack Compose, inferred from the repository. Desktop, iOS, and web Compose Multiplatform targets are outside this ADB plan.

Priorities below are engineering recommendations based on repository inspection and official Android documentation. They are not validated demand from developer interviews. Before investing in advanced integrations, ask five Android developers to complete an element-selection, scrolling, and state-restoration task; record failures and the steps they repeat manually.

## Existing foundation and verified gaps

The repository already contains UI-tree inspection, tag/text/description matching, tapping, long press, scrolling, text entry, assertions, accessibility checks, and a Compose sample screen. Extend those features rather than announce Compose support as entirely new.

| Observed implementation | Proposed change |
|---|---|
| `UiTreeSearch.findOne` returns the first match. | Mutating actions require a unique scoped match; return candidates when ambiguous. |
| `UiNode.label` falls back to a test tag; the audit uses this as an accessible label. | Separate an automation identifier from text a screen reader can announce. |
| Touch-target audit compares bounds with 48 pixels. | Read effective display density, convert pixels to dp, and disclose uncertainty about effective touch regions. |
| `bounds.isVisible` only checks positive area. | Distinguish tree presence, viewport intersection, and verified action results; do not claim occlusion detection from bounds alone. |
| Scroll chooses the first scrollable node and searches only before each swipe. | Support a selected container and direction; inspect after the final swipe too. |
| Text input taps a target and runs shell `input text`. | Define append versus replace, confirm focus where observable, and report unsupported input instead of claiming success. |
| Missing tags are described as proof the app omitted `testTagsAsResourceId`. | Say “No exposed tags observed”; missing tags can also reflect the current subtree or lack of tagged nodes. |
| Hybrid tag detection checks all nodes. | Preserve node provenance so a View resource ID does not prove Compose tags are exposed. |

Evidence: `src/main/kotlin/spock/adb/uitree/{UiNode,UiSelector,UiTreeParser,AccessibilityAudit}.kt`, `mcp/tools/{ComposeUiTools,ComposeActionTools}.kt`, and `command/ProcessDeathCommand.kt`. These are static inspection findings, not results of executing the app.

## Research constraints that shape the design

1. **ADB sees an accessibility representation, not every composable or internal state value.** Compose has merged and unmerged semantics trees; tests and accessibility consume them differently. Do not label an XML dump as the complete Compose tree or promise a merged/unmerged toggle without a separate data source. [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
2. **Test tags need explicit exposure for UI Automator.** Offer the documented `testTagsAsResourceId = true` setup and preserve the original resource/tag value. Tags do not replace accessible labels. [Testing interoperability](https://developer.android.com/develop/ui/compose/testing/interoperability)
3. **Automated accessibility checks are only part of verification.** Compose provides Accessibility Test Framework integration through `enableAccessibilityChecks()` from Compose 1.8.0; pair this with manual testing. [Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing)
4. **State restoration deserves a dedicated workflow.** `rememberSaveable`, `SavedStateHandle`, and `StateRestorationTester` address different state-saving needs. A visual before/after comparison cannot identify which API the app used. [Save UI state](https://developer.android.com/develop/ui/compose/state-saving)
5. **Performance and visual regression have established tools.** Use Layout Inspector for recomposition investigation, composition tracing for timing, and screenshot testing for visual changes. Spock should prepare reproducible evidence and integrate with these workflows. [Performance tooling](https://developer.android.com/develop/ui/compose/performance/tooling), [Screenshot testing](https://developer.android.com/training/testing/ui-tests/screenshot)

## Prioritized developer features

| Priority | Developer need | Feature and useful result | Delivery approach |
|---|---|---|---|
| P0 | “Tap the correct Save button.” | Scoped selectors, exact tag matching, candidate list, action-specific eligibility, and outcome checks. | Extend existing ADB tools. |
| P0 | “Why can’t I find this composable?” | Explain observed tag exposure, duplicate matches, lazy content, and limitations of accessibility data; copy setup or diagnostic snippets. | Inspector diagnostics; label possible causes as hypotheses. |
| P0 | “Can users understand and operate this screen?” | Correct label and density checks; findings show evidence and a suggested fix. | Improve current audit; separate accessibility findings from automation advice. |
| P1 | “Find an item inside this list or pager.” | Container-scoped vertical/horizontal scrolling, bounded retries, no-progress detection, and final observation. | ADB gestures plus repeated observations. |
| P1 | “Does this form work with the keyboard?” | Focus, append/replace intent, keyboard dismissal, IME-action testing, and resulting-state assertions. | ADB subset first; instrumentation for reliable advanced editing. |
| P1 | “Does my state survive leaving the app?” | Guided before/after checks for rotation, background/return, and process recreation. | Existing lifecycle tools with verified transitions. |
| P1 | “Will large text or a small window break this?” | Capture selected scenarios for font scale, theme, rotation, and supported window sizes; compare evidence. | Capability-probed device settings and emulator controls. |
| P1 | “Turn this reproduction into a test.” | Record actions performed through Spock, add explicit assertions, replay, export Kotlin test drafts. | Shared scenario format; Compose or UI Automator export. |
| P2 | “Why is this screen slow?” | Capture a repeatable interaction and guide the developer into traces or benchmark tests. | Documented tooling integration; no invented recomposition metrics. |
| P2 | “Inspect unmerged semantics or custom actions.” | Optional instrumentation adapter with declared capabilities. | Separate feasibility spike and project opt-in. |

Avoid adding a separate Compose tab initially. Extend the existing Inspector with element details, selector diagnostics, before/after captures, and a scenario list. The same workflows should help hybrid apps.

## Delivery phases and acceptance criteria

### Phase 1 — Trustworthy inspection and interaction

- Introduce a shared observation model: device, app/package, timestamp, viewport, density, data source, and capability limits.
- Preserve raw resource IDs and distinguish them from inferred Compose tags. Treat framework detection as evidence-based and allow unknown.
- Add package/container scoping and exact tag matching while preserving existing MCP compatibility through optional arguments or versioned tools.
- Require one eligible target for an action. Resolve click, long-click, scroll, and editable targets independently.
- Add bounded waiting for appearance, disappearance, and state changes; cancellation must work during acquisition and retries.
- Separate “command dispatched” from “expected UI result verified.” Do not automatically repeat a potentially completed tap after an uncertain response.
- Correct audit label handling and density conversion; describe a clean result as “No issues detected by these checks.”

Acceptance: two Save buttons never cause an arbitrary tap; tagged but unlabelled controls remain audit findings; equivalent targets produce consistent density-aware results; disconnected devices and timeouts produce explicit errors. A present node is not automatically described as fully visible or unobscured.

### Phase 2 — Practical Compose debugging scenarios

- Add nested scrolling and final-swipe verification.
- Implement text-editing capabilities explicitly. For Unicode, selection, replacement, or IME operations unsupported by the chosen backend, provide a clear limitation or instrumentation path.
- Let the developer select expected retained fields before a lifecycle check. Compare selected values and controls after recovery.
- For process-death checks: background the app, verify process exit, restore the intended task where supported, then verify the expected screen. Report an unverified transition as inconclusive. Keep force-stop and data clearing separate from process-restoration testing.
- Add selected configuration scenarios. Record original settings, restore them on completion or cancellation, and surface a recovery action after interruption. Probe each device capability instead of assuming universal shell support.
- Bundle screenshots, relevant tree changes, timestamps, and scoped logs into a local reproduction report. Mask password fields and let users review other sensitive content before sharing.

Acceptance: a target found on the last allowed swipe succeeds; nested lists use the requested container; a demonstration field using `remember` loses state where expected and a suitable `rememberSaveable` field restores; unsupported scenarios report unsupported, not passed.

### Phase 3 — Reusable tests and visual evidence

- Record Spock-issued actions with selectors and expected outcomes, not only coordinates. Add bounded waits and replay from an explicit starting state.
- Export UI Automator tests for device-level flows and Compose tests for app-owned UI, including missing setup instructions and assumptions.
- Do not blindly translate accessibility XML nodes into Compose test matchers: the test semantics tree can differ. Compile and run representative generated tests in the sample project.
- Add optional accessibility test scaffolding compatible with the project's dependencies.
- Export screenshot pairs and configuration metadata first. Add automated baseline comparison only with controlled rendering conditions and deliberate baseline approval.

Acceptance: an exported sample scenario compiles and passes; a deliberately changed assertion fails; repeated labels remain scoped; screenshot baselines are never silently replaced.

### Phase 4 — Advanced integration decision

Run a bounded spike for an optional instrumentation adapter. Evaluate merged/unmerged semantics, custom actions, reliable text entry, and state-restoration test execution using public APIs. Record supported Compose versions, test-runner requirements, and process/session limitations.

Proceed only if developers need capabilities the default ADB path cannot provide. Keep arbitrary runtime state mutation, a replacement Layout Inspector, and a new preview engine outside the initial scope. Recomposition counts alone must not be presented as proof of a performance defect.

## Architecture and verification

Keep acquisition, selection, interaction, assertions, and scenario execution in shared services consumed by the Inspector and MCP tools. Reuse `ToolRegistry`, existing device targeting, cancellation, and action policies. Avoid duplicating workflows inside the assistant.

The default backend remains ADB. Every advanced backend declares capabilities so the UI and MCP can return supported, unsupported, or inconclusive results consistently.

Expand the sample app with repeated labels, nested lazy lists, dialogs, hybrid screens, password and Unicode fields, retained/lost state examples, and large-font layouts. Verify using parser/selector fixtures plus emulator scenarios across representative API levels, densities, and tagged/untagged builds. Include disconnects, transient animations, and cancellation. Run the repository's normal tests, static checks, and plugin compatibility checks for implementation releases.

Suggested planning envelope for one developer: Phase 1, 1–2 weeks; Phase 2, 2–3 weeks; Phase 3, 2–3 weeks; Phase 4, a 3–5 day feasibility spike before any commitment. These are provisional estimates, excluding unexpected platform compatibility work.

The recommended first release is Phase 1 plus container-scoped scrolling and one state-restoration scenario. Measure completion of the sample workflows, ambiguous-action refusals, and reproducible failure reports before expanding the feature set.

## Implementation progress

First increment, 23 September 2026:

- [x] Refuse ambiguous tap, long-press, and text-input matches. Ambiguity is counted by resolved action target, so matches landing on one control count once. Refusals list each candidate's label, class, ID, package, and bounds, suggest `exact`, and say plainly when no selector field can separate the candidates.
- [x] Add optional package/container scoping and case-sensitive exact tag matching without changing legacy substring defaults.
- [x] Resolve action-specific targets and reject disabled controls or targets outside the selected container.
- [x] Resolve nested scrollables to the outermost container (a scrollable `containerTag` is used directly); refuse only unrelated sibling containers; honor package/container scope. This is not Phase 2 nested-scroll support: the swipe is still vertical-only, so an inner horizontal carousel cannot be scrolled on purpose.
- [x] Separate command dispatch from verified UI outcomes in action results.
- [x] Separate accessible labels from automation identifiers; estimate touch targets using effective density and disclose skipped checks. Bare scroll containers are not required to carry their own label.
- [x] Correct hybrid tag detection for View IDs outside Compose, and missing-tag guidance. Known gap: View IDs from `AndroidView` interop *inside* a Compose host still count as exposed tags. The parser has no confirmed interop-boundary signal, and a device capture is needed to find one (see `UiTreeParser.composeNodes`).
- [x] Add selector, action-dispatch, density, parser, and accessibility regression tests, including nested and sibling scroll containers, target de-duplication, ambiguity messages, and scroll-container audit exemption.
- [x] Shared observation model (`UiObservation`): device serial, the package owning the dumped window (not claimed to be the foreground app), host-clock start and duration, display rotation, viewport (window clipped to the display), effective density, data source, and the capture's limits. Every MCP UI tool opens with its one-line summary and, where it makes a claim about the screen, one line of limits; the Inspector's status line shows viewport and density. Display size and density are best effort: a failed `wm` read leaves them unknown and says so.
- [x] Viewport-aware visibility (`ViewportVisibility`): every node is in the viewport, partly in it (with its share and the part in view), outside it, of zero area, or unclassified when the capture has no viewport. The viewport is narrowed by each scroll container above a node, and by nothing else. Nothing is described as fully visible or unobscured. `Bounds.isVisible` is now `hasArea`, which is all it ever checked. Element actions refuse a target out of view and point to `android_scroll_to_element`, and press a partly visible target at the centre of its part in view. `assert_visible` and `assert_text` pass only for a match in view, and are inconclusive without a viewport. `scroll_to_element` stops only at a match in view. `find_ui_element`, `get_ui_tree` and the Inspector say where each node is. A node whose bounds reach a scroll container's edge is flagged as possibly cut: its size is unknown, and the audit leaves it out of the size and label checks and counts it in the coverage note.
- [ ] Finish Phase 1 bounded waits and outcome assertions.
- [ ] Complete Phase 2 scrolling, input capability handling, lifecycle scenarios, and configuration recovery.
- [ ] Complete Phase 3 scenario export and Phase 4 feasibility work.

The first increment intentionally does not claim that shell text input supports arbitrary Unicode,
that positive-area bounds prove visibility, or that a dispatched action changed application state.

Validation for this increment, after the review fixes and the rebase onto master: `./gradlew test`
ran 897 tests with 9 skipped and no failures; `./gradlew detekt` and `./gradlew buildPlugin`
passed. IDE compatibility verification was attempted before the review fixes but stopped on an
invalid cached IntelliJ IDEA 2025.1 installation (missing core plugin), so compatibility is not
yet verified. Real-device smoke tests require an active test setup and are not claimed as passed.
The branch `feature/compose-reliability` is pushed; no pull request has been created.

### Device checks

Run against the sample app (`./gradlew -p sample :app:installDebug`), screen **UI Inspector —
Compose reliability fixtures**, on the tab named in the first column. Tapped controls report
themselves in the *Last tap* line above every tab, so a wrong tap is visible. Each fixture card's
info button opens its rows of this table, with each call one tap from the clipboard; the sheet's
text lives in `FixtureCards.kt`, so change the two together, and close the sheet before capturing.

| Fixture (tab) | MCP call | Expected |
|---|---|---|
| 1. One label, two forms (Taps) | `android_tap_element {text: "Save"}` | Refused, two `Save` candidates |
| | `{text: "Save", containerTag: "form_a"}` · `{testTag: "form_b_button"}` | Taps that form's button; *Last tap* names it |
| 2. Title and button (Taps) | `android_tap_element {text: "Discard"}` | Refused, naming `Discard changes?` and `Discard`; suggests `exact` |
| | `{text: "Discard", exact: true}` | Taps `confirm_button` |
| 3. Identical rows (Taps) | `android_tap_element {text: "Archive"}` | Refused: no selector field can tell the candidates apart |
| 4. Row and icon (Taps) | `android_tap_element {contentDescription: "Share report"}` | One tap on the row, not a refusal |
| | `{text: "Weekly report", containerTag: "report_label"}` | Refused: action target is outside the selected container |
| 5. Disabled button (Taps) | `android_tap_element {testTag: "disabled_button"}` | Refused as disabled; nothing dispatched |
| 6a. Feed of carousels (Scroll) | `android_scroll_to_element {testTag: "feed_end", containerTag: "feed", maxSwipes: 20}` | Found; the feed is swiped directly |
| | `{testTag: "feed_end", containerTag: "feed_section", maxSwipes: 20}` | Found; the feed wins over the carousels inside it |
| 6b. Sibling lists (Scroll) | `android_scroll_to_element {text: "Right 35"}` | Refused: several scrollable containers |
| | `{text: "Right 35", containerTag: "list_right"}` | Found |
| 7. Audit (Audit) | `android_accessibility_audit {}` | Two findings: `audit_unlabelled` (no accessible text, despite its tag) and `audit_small_target` (24dp, below 48dp). Nothing for `audit_list` or its rows. The coverage note says 1 control at a scroll container's edge was skipped: the half-visible fifth row |
| 8. Never idle (Busy) | `android_tap_element {testTag: "busy_switch"}`, then `android_get_ui_tree {}` | Capture fails as `DUMP_REFUSED` ("could not get idle state"), after about 12 s on an API 34 emulator; `TIMED_OUT` where `uiautomator` waits past 30 s. The switch turns itself off after 30 s |
| 9. Hybrid, tags off (Hybrid) | `android_tap_element {testTag: "expose_tags_switch"}`, then `android_get_ui_tree {}` | Hybrid screen, no exposed Compose tags observed: the toolbar's View IDs do not count. `{text: "Expose test tags"}` turns them back on |
| 10. Known gap (Hybrid, tags off) | `android_tap_element {text: "Show a View inside Compose"}`, then `android_get_ui_tree {}` | Still reports Compose tags as visible, because of the `AndroidView` ID. Expected until the gap is closed |
| 11. A second window (Window) | `android_get_ui_tree {}` with the dialog closed | First line `Observed on <serial>, window spock.adb.sample, <ISO-8601 instant> (host clock, <n> s capture), viewport <W>x<H> rot 0, <dpi> dpi, source: uiautomator accessibility dump of the active window.` Second line starts `Limits:` and, tags being exposed, names the `AndroidView` gap. Rotation and dpi match the tab's own *This app sees* line |
| | `android_tap_element {testTag: "open_dialog"}`, then `android_get_ui_tree {}` | **Unverified which window is dumped — record it.** If the dialog: a viewport smaller than the display with an `at (x,y)` origin, and `dialog_close` in the tree. If the activity: the same viewport as before and no `dialog_close`. Either way `window` is the package that owns the dumped window |
| 12. Rotation (Window) | Rotate the device, then `android_get_ui_tree {}` | `rot` equals the rotation the tab shows (1 or 3 for landscape); the viewport's width and height swap |
| 13. Density (Window) | `adb shell wm density 320`, then `android_accessibility_audit {}`; afterwards `adb shell wm density reset` | Summary says `320 dpi`, and the coverage note says touch-target estimates use 320dpi |
| 14. Lost device (any) | Disconnect the device (`adb disconnect`, or unplug) and call `android_get_ui_tree {}`; then press **Capture UI** in the Inspector | MCP: `DEVICE_UNAVAILABLE` naming the serial and pointing to `android_list_devices`. Inspector: the same failure, telling the person to reconnect or choose another device, with no tool name. Its status line after a good capture ends `· viewport <W>x<H> rot <r> · <dpi> dpi` |
| 15. In the tree, in view, or neither (Fold) | `android_assert_visible {testTag: "below_fold"}` | FAIL: nothing matched, pointing to `android_scroll_to_element`. The row is left out of the capture while it is scrolled out of the column, so it is absent rather than outside the viewport |
| | `android_tap_element {testTag: "below_fold"}` | Refused as no match, pointing to `android_scroll_to_element`; nothing dispatched, *Last tap* unchanged |
| | `android_find_ui_element {testTag: "half_visible"}`, then `android_get_ui_tree {}` | One match, "partly in the viewport, cut at its scroll container's edge; how much is out of view is unknown". The tree marks it `[partly in viewport, clipped by scroll container]` and its text `[may be clipped by scroll container]`; nothing else in the card is marked |
| | `android_tap_element {testTag: "half_visible"}` | Tapped at the centre of its part in view, not of its bounds; *Last tap* names the half-visible row |
| | `android_scroll_to_element {testTag: "below_fold"}`, `android_assert_visible {testTag: "below_fold"}`, `android_tap_element {testTag: "below_fold"}`; afterwards switch tabs and back to scroll the column to the top | Found after about 5 swipes; PASS, ending "(occlusion not checked)"; *Last tap* names the row below the fold |

Rows 1–5 were resolved offline with the plugin's selector against a `uiautomator` dump of the
Taps tab from an API 34 emulator, and matched the table. Nothing was dispatched through MCP.

Rows 7, 8 and 15 were run on an API 34 emulator (1080x2636, 480 dpi) with the plugin's own tool
classes — the audit, tree, find, assert, tap and scroll tools — sending every command to the
emulator through `adb shell`. Only the MCP transport and ddmlib were left out. Each result matched
the table, and each tap was confirmed by the *Last tap* line. Rows 6 and 9–14 have not been run on
a device yet. What the emulator showed:

- **Row 7 was a false pass.** Compose widens a clickable's reported bounds to the
  `ViewConfiguration` minimum touch target, so the 24dp `audit_small_target` box was dumped at
  48dp and never flagged. The sample now removes that widening around the box alone, and the dump
  reports it at 72px, 24dp. The list's fifth row is always half cut by the list's 240dp height. It
  was dumped as `[72,1542][1008,1662]`, reaching past the list's bottom edge (1590) yet still
  short of its real 56dp, and without its "Option 5" text. Stage 3's audit skips it and says so.
- **Row 8 did not reproduce as written.** With only the Compose ticker, dumps succeeded in 3.5–4.9 s
  while it ran: Compose sends accessibility events only once it sees an enabled accessibility
  service, and the dump's own connection does not count as one. A View ticker was added
  (`AndroidView` around a `TextView`), and since then every dump fails with "ERROR: could not get
  idle state." after 11.7–13.4 s, which the plugin reports as `DUMP_REFUSED`.
- **How Compose reports `below_fold`: absent.** A row scrolled wholly out of its `verticalScroll`
  column is not in the dump at all, and neither is any other row below the column's edge. So an
  off-screen Compose element comes back as *nothing matched*, not *outside the viewport*. The
  refusal and the failure both point to `android_scroll_to_element` for that reason. The
  outside-viewport answer still covers bounds laid out beyond a container, as a View dump or a
  future data source may report them.
- **How Compose reports a row cut by its container: whole, or cut by what is drawn over it.**
  `half_visible` (48dp, 24dp in view) was dumped at its full `[72,1146][1008,1290]` while its text
  child was cut to the column's edge at 1218. With another card directly below the column, the
  same row was dumped as `[72,1146][1008,1278]`, cut where that card begins. Its bounds are
  therefore not a reliable size, which is why a node reaching or crossing a scroll container's
  edge is flagged with an unknown share. After scrolling, `below_fold` sat exactly on the column's
  bottom edge at full size, and is flagged *may be clipped* for the same reason.
- **A zero-size node is absent too.** A tagged `Modifier.size(0.dp)` box never appeared in the
  dump, so the sample has no zero-area fixture: `ZERO_AREA` is covered by unit tests only.

Stage 3 validation: `./gradlew test` ran 1008 tests with 9 skipped and no failures; `detekt`,
`buildPlugin` and the sample's `assembleDebug` passed. `verifyPlugin` still stops on the broken
cached IntelliJ IDEA 2025.1 install. The Plugin Verifier CLI was therefore run directly against the
other four targets (Android Studio 2023.2.1.25, 2024.2.1.12 and 2025.1.1.14, IntelliJ IDEA
Community 2023.2.8), and all four reported Compatible, with only the deprecated usages already
known.
