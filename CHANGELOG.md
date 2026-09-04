<!-- Keep a Changelog guide -> https://keepachangelog.com -->

## [Unreleased]
### Fixed
- **"Don't Keep Activities" could never be clicked.** The checkbox was marked `enabled="false"` in the UI form, so wiring its action listener achieved nothing — it was disabled before the listener could ever fire. Now enabled and functional
- **Typo in the permissions panel**: "Revoke / Grant Premission" → "Permission"
- "Dont Keep Activities" → "Don't Keep Activities"; "Open DeepLink" → "Open Deep Link"; trailing space trimmed from "Current Backstack Activities"
- The MCP status line ran two labels together ("MCP Server stopped Not accepting connections."); the detail now reads as a separate, dimmed clause
- The MCP details pane took 40% of the panel while empty. It favours the list now, and says what to select instead of showing blank space
- The Command Center output pane was blank with no indication anything worked; it now shows example commands until the first output arrives
- The Command Center "Copy output" icon rendered as a camera, which read as "screenshot"
- The Logcat search box had no label, unlike Preset and Level

### Added
- **UI Inspector tab.** The Compose semantics work was previously reachable only through MCP — useful to an AI agent and invisible to the developer. The same machinery is now a tool window tab: browse and search the semantics tree, see whether the screen is Views/Compose/hybrid, inspect every node's test tag, text, content description, bounds and interactive flags, and run the accessibility audit. Reachable from Find Action as "Open UI Inspector"
- **Jetpack Compose is a first-class target for UI inspection and automation.** Compose has no View hierarchy, so `Activity → View hierarchy` is the wrong model for a Compose screen. The new semantics-based UI tree reads the accessibility tree — where Compose publishes its semantics — so one implementation covers Views, Compose and hybrid screens, with **no Compose dependency and no pinned Compose version**
- `android_get_ui_tree` reports whether the screen is Views, Compose or hybrid, and every node's test tag, text, content description, bounds, and clickable/enabled/scrollable/checked/selected state
- **Semantics-first interaction**: `android_tap_element`, `android_long_press_element`, `android_scroll_to_element`, `android_input_text_into_element` and `android_find_ui_element` resolve elements by testTag → content description → text, deriving the tap point from the matched node's own bounds. Coordinates are now explicitly the fallback — `android_tap`'s description says so
- Compose puts the click handler on the parent of the text node, so element taps walk up to the nearest interactive ancestor automatically
- **Assertions** so an agent can verify rather than infer from pixels: `android_assert_visible`, `android_assert_enabled`, `android_assert_text`
- `android_accessibility_audit` — unlabelled interactive elements, touch targets below the 48dp minimum, ambiguous duplicate labels and unlabelled images, each with a fix appropriate to the framework (`Modifier.semantics` for Compose, not `android:contentDescription`)
- Framework detection ignores layout containers: every Compose app has an `android.widget.FrameLayout` decor view, so counting any `android.widget.*` class as "Views" would report every pure-Compose screen as hybrid
- Limitations documented rather than papered over: Compose test tags need the app to set `testTagsAsResourceId`, the current Navigation Compose route is not observable over ADB, and the Layout Inspector's Compose protocol is deliberately not used because it requires unstable internals

### Fixed
- **Compatibility**: a `sequence { }` builder compiled to a coroutine state machine referencing `kotlin.coroutines.jvm.internal.SpillingKt`, which is absent from the Kotlin stdlib bundled with 2023.1 IDEs — a `NoSuchClassError` at runtime, since the plugin uses the IDE's bundled stdlib. Caught by Plugin Verifier and rewritten as a plain recursive walk

### Added
- **Tools catalogue in the MCP panel.** The panel reported only a count ("Tools: 36"), which told you nothing about what pressing Start would expose. There is now a Tools tab listing every tool grouped by safety level — destructive first, since those are the ones worth reading — searchable, filterable to destructive only, and showing each tool's description and argument schema. It works while the server is stopped, so the catalogue can be reviewed before turning it on
- **MCP Server panel** as a tool window tab rather than a buried setting: status, transport and tool count, Start/Stop/Restart, Copy Config, and a link to Settings
- **Live MCP activity monitor.** Every tool call as it happens with a safety marker (`✓` read-only, `⚡` action, `⚠` destructive), outcome and duration; select one to see arguments, result, client, target device, and — for destructive calls — whether you approved or denied it. Copy request or response
- **Searchable, bounded MCP request history** across tool name, arguments and result, filterable by tool and outcome, with a configurable size in `Settings → Tools → Spock ADB`
- **Every major operation is now an IntelliJ Action**, so it is discoverable through Find Action and bindable in `Settings → Keymap → Spock ADB`. Actions are context-aware: they disable themselves and explain why ("no Android device connected", "no application ID could be resolved from this project")
- **No default keyboard shortcuts are shipped**, deliberately — a binding that is free in one keymap is taken in another, and silently claiming a combination the developer already uses is worse than shipping none. A test enforces that none creep in
- `Settings → Tools → Spock ADB` shows which shortcuts are currently bound, read live from `KeymapManager`, with an "Edit in Keymap" button. IntelliJ's Keymap remains the single source of truth — no custom shortcut engine
- `android_uninstall_app` MCP tool, marked destructive

### Fixed
- **Logcat preset and level dropdowns showed raw enum constants** (`CURRENT_APP` instead of "Current app") — the combos had no renderer
- **The logcat filter row was clipped and the status text truncated.** The toolbar, filters and status shared a single `BorderLayout` row, so in a docked tool window the controls were cut off. Split into an actions row, a wrapping filter row, and a status bar of its own
- **Controls no longer run off the edge of a narrow tool window.** A `WrapLayout` reflows them instead — plain `FlowLayout` reports a single row's height and is clipped rather than wrapped
- **Logcat columns now line up**: rows render in a monospace font, so timestamp, PID and level are scannable
- Destructive commands are flagged in the Command Center *as you type*, not only in a dialog after pressing Run

### Added
- **Logcat panel.** Live logcat in its own tool window tab, pre-scoped to the app in the open project. Presets for Current app, Errors only, Crashes, ANRs and Network; filtering by level, tag and plain-text or regex search; crash and ANR highlighting; pause/resume, clear, copy and export; auto-scroll. Filtering is by process ID rather than by matching the package name against message text, which both misses lines and returns unrelated ones
- **ADB Command Center.** Run any shell command against the selected device with a real timeout and a Cancel button that actually stops the command. Command history that de-duplicates and reorders rather than piling up, favourites, searchable output, copy command, copy output and clear
- **Commands can now be cancelled at all.** `ShellOutputReceiver` hard-coded `isCancelled()` to `false`, so no long-running command could ever be interrupted. The new `CancellableShellReceiver` streams output line by line and honours cancellation, which is what makes both live logcat and the Cancel button possible
- Destructive shell commands are classified and confirmed before running, sharing `DangerousCommands` with the MCP `android_run_adb_command` tool so a command needing confirmation for an AI agent needs it in the UI too
- The tool window is now three tabs — Devices, Logcat, Commands — with the device chosen in Devices targeted by all of them
- README now documents the MCP server, the Logcat panel and the Command Center, and links the docs; the Marketplace description mentions MCP and IntelliJ IDEA support

### Fixed
- **Compatibility**: `FileSaverDescriptor(String, String)` does not exist before 2025.1 and would have thrown `NoSuchMethodError` on Android Studio 2023.1/2024.2 and IntelliJ IDEA 2023.1. Caught by Plugin Verifier; the deprecated-but-present overload is used instead

### Added
- **Android MCP server.** Spock ADB can expose the connected device to MCP-compatible AI agents (Claude Code, Claude Desktop, Cursor). 26 strongly typed tools rather than a generic shell passthrough, so an agent can reason about what an operation means and you can audit it: devices, packages, app lifecycle, permissions, current Activity/Fragment, activity stack, logcat, processes, battery, network, deep links, input, tap/swipe/keys, screenshots as MCP image content, and the uiautomator UI hierarchy. Start it from `Tools → SpockAdb`; it is **off by default**
- **MCP safety model.** Every tool declares read-only, safe-action or destructive. Destructive tools (clear app data, revoke permission, arbitrary shell) always ask before acting, default to denied, bring the IDE forward, and are written to `idea.log`. A test enforces that no destructive tool can report success without a confirmation
- `android_run_adb_command` exists as a deliberately awkward escape hatch: it requires a stated reason, has a bounded timeout and capped output, and refuses catastrophic commands (`rm -rf /`, factory reset, `mkfs`, raw `dd`) before the confirmation dialog is even shown
- The MCP layer owns no ADB logic — it resolves devices and runs commands through the same services the tool window uses, so there is one implementation of every device operation
- Transport is the JDK's own `com.sun.net.httpserver`, bound to loopback with a per-install bearer token compared in constant time. Android Studio does not ship IntelliJ's built-in web server, and this avoids adding Netty or Ktor
- `docs/MCP.md` documents the architecture, the safety model, example agent workflows, and what is not implemented yet

### Added
- Unit test suite for ADB output parsing (21 tests): activity, back stack, application back stack and fragment `dumpsys` parsers
- `detekt` static analysis wired into the build with a baseline of the existing 178 findings, so new code cannot add to the debt
- CI now runs `test` and `detekt` on every push and pull request, and uploads the reports

### Fixed
- **Build**: the test source set did not compile — JUnit was never on the test classpath, and CI only ran `buildPlugin`, so this went unnoticed
- **Build**: `./gradlew detekt` was documented but the detekt plugin was never applied
- **API level detection**: `getApiVersion()` read `ro.build.version.release` (the marketing version, e.g. "8.1.0"), which fails integer parsing and pushed modern devices down the pre-Honeycomb back stack parsing path. Replaced with `apiLevel()`, reading `ro.build.version.sdk` and preferring ddmlib's cached value
- **Crash**: `GetApplicationBackStackCommand` called `tasks.last()` on a possibly empty list, throwing `NoSuchElementException` on a truncated `dumpsys` dump

### Compatibility
- **The plugin now works in IntelliJ IDEA, not just Android Studio.** The descriptor declared `<depends>com.intellij.modules.androidstudio</depends>`, which made the Marketplace report the plugin as incompatible with IntelliJ IDEA. Every Android API the plugin uses ships inside the `org.jetbrains.android` plugin rather than the Android Studio platform, so the dependency bought no API guarantees and only restricted reach
- **Fixed a `NoSuchMethodError` on Android Studio 2023.1 through 2024.x.** `sinceBuild` was `231` while compiling against platform `251`. Kotlin emits compatibility-bridge overrides that `invokespecial` into every default method a platform interface had at compile time, so `AdbDrawerViewer` — the tool window factory, and therefore the plugin's entry point — referenced `ToolWindowFactory.manage` and `isApplicableAsync`, neither of which exists on 231. The tool window could not open. Fixed with `-Xjvm-default=all`
- **Declared the missing Java plugin dependency.** The plugin navigates to sources through `JavaPsiFacade` and `PsiShortNamesCache` without declaring `com.intellij.modules.java`, which Plugin Verifier reported as a compatibility problem on every target
- Restored Plugin Verifier and wired it into CI, now covering Android Studio 231/242/251 and IntelliJ IDEA 231/251. All five report `Compatible`
- `Restart App With Debugger` is feature-detected at runtime and hidden on IDEs without the Android Studio execution tooling, instead of failing with `NoClassDefFoundError`
- Replaced the deprecated `ToolWindowManagerListener.stateChanged()` override and the deprecated `AndroidVersion.getApiLevel()` call
- Added `docs/COMPATIBILITY.md` documenting the supported range, the verification matrix and how to change it

### Fixed
- **EDT freeze**: `AndroidSdkUtils.getDebugBridge()` blocks while ADB starts, and was called both when opening the tool window and inside every menu action — all on the EDT. The bridge is now resolved lazily on a pooled thread
- **Threading**: device connect/disconnect callbacks arrive on ddmlib threads and mutated the Swing combo box model directly. Device lists are now read off the EDT and delivered on it
- **Threading**: `grantOrRevokeAllPermissions` would have issued one `pm grant`/`pm revoke` per permission on the EDT; it now runs entirely on a pooled thread
- **Crash**: selecting in the device combo box indexed straight into the device list, throwing `ArrayIndexOutOfBoundsException` when the model reported index `-1` after the last device disconnected. Also now ignores `DESELECTED` events, which fired the handler twice
- **Crash**: a persisted setting naming an action that no longer exists made `SpockAction.valueOf` throw from the constructor, preventing the tool window from opening at all. Unknown entries are ignored
- **Hang**: `ProcessDeathCommand` passed a timeout of `0` to `executeShellCommand`, which means "wait forever" in ddmlib — an unresponsive device hung a pooled thread permanently
- **Leak**: `project.messageBus.connect()` was called without a parent `Disposable`, so the tool window listener was never released
- **Leak**: each menu action constructed its own `AdbControllerImp`, registering another global ADB device-change listener, and disposed it immediately after starting async work — cancelling the work before it ran. A single project-scoped `SpockAdbService` now owns the controller
- **Silent failures**: exceptions were swallowed and surfaced as `e.message ?: "not found"`, so a null-message exception showed the user the words "not found" and left no stack trace anywhere. Failures are now logged to `idea.log` as well as reported
- **Dishonest success**: `connectDeviceOverIp` reported "connected to $ip" while its command was an empty stub that did nothing
- **Compatibility**: `project.service<T>()` inlines a call to `ServicesKt.serviceNotFoundError`, absent before 2023.3, which would have thrown `NoSuchMethodError` on the oldest supported builds. Replaced with the non-inline `getService` in both services

### Fixed
- **"Don't Keep Activities" did nothing.** The checkbox displayed the current device setting but was never given an action listener: clicking it moved the tick, left the device unchanged, and silently reverted on the next refresh. It now toggles `always_finish_activities` like the other developer options

### Fixed
- **The device list came up empty.** `AndroidSdkUtils.getDebugBridge()` opens with `assertIsDispatchThread()` — it drives a progress task while ADB boots, so it is designed for the EDT and throws anywhere else. Moving it to a pooled thread made every device lookup fail. `DebugBridgeProvider` now reads the already-running bridge via `AndroidDebugBridge.getBridge()`, which is safe from any thread, and only delegates to `AndroidSdkUtils` on the EDT when ADB still has to be started
- **The failure was silent.** The device list is read on a background thread and delivered to a UI callback; when that read threw, the callback never ran, so the dropdown stayed empty with nothing shown and nothing logged. `DeviceLister` now reports every failure to `idea.log` and degrades — one unreadable device no longer discards the whole list
- **An empty dropdown now explains itself** instead of looking like a broken plugin, and says what to do next
- **`refresh()` did not refresh.** It re-registered the ADB listener without re-reading devices, so an empty dropdown could only be recovered by reopening the project. It now re-reads the list, and the tool window does so every time it becomes visible
- The tool window listener is registered after the controller is assigned; it calls into a `lateinit` property, so an early state change could have thrown `UninitializedPropertyAccessException`

### Device management
- The device dropdown now shows model, Android version, API level, architecture, and whether the device is an emulator or a handset, instead of just the raw ddmlib name. Offline, unauthorized and bootloader devices are labelled as such
- **The selected device is persisted between sessions.** `AppSetting.selectedDevice` has existed since settings were introduced but was never read or written
- Menu actions now ignore devices that cannot accept commands, and say why when none are usable (for example "Pixel 7 is unauthorized"), rather than failing part-way through a command
- When no device was previously selected the plugin now prefers an online device rather than whichever happened to be first
- Device metadata is read on a background thread; `IDevice.getProperty` blocks, so this must never happen while the dropdown is being rendered
- Confirmation prompts name the target device, so it is unambiguous which of several attached devices an action will affect

### Security
- **Shell injection via the device.** ADB commands are built by string interpolation and run through the device shell. The two fields the user types into were interpolated inside hand-written quotes — `input text '$p'` and `am start ... -d "$p"` — so a value containing the matching quote character closed it early and everything after ran as shell on the connected device. Pasting a crafted deep link was enough. All interpolated values now go through `ShellQuote.quote`, which single-quotes and escapes embedded quotes
- Every other interpolation site was hardened the same way: package names, activity components, permission names and animation scales
- **Confirmation for destructive operations.** Uninstall, Clear App Data, Clear App Data & Restart and Revoke All Permissions were single clicks with no prompt, sitting beside read-only actions. Each now asks first, and names the target device so it is unambiguous which of several attached devices will be affected

### Added
- `BaseAction` now declares `ActionUpdateThread.BGT` and disables its actions when no project is open
- Tests for `ShellOutputReceiver` chunking and trailing-newline handling, and for the device state enums

### Removed
- `GetConnectedDevicesCommand`, which was unused and contained an unchecked cast that would have thrown on a null bridge

### Changed
- Device selection is now tracked by serial number rather than object identity, so it survives a device reconnect handing out a new `IDevice` instance
- Application ID resolution reads through the stable `AndroidModel` interface instead of the Gradle-specific `GradleAndroidModel`, and now prefers application modules over libraries — in a multi-module project the previous code used an arbitrary facet and could resolve the wrong module or none at all
- A project with no resolvable application ID now reports an actionable message; previously the nullable result was passed through `.toString()`, producing the literal string `"null"` and the misleading error `Application null not installed`
- ADB output parsing extracted from the command classes into pure functions in `spock.adb.parser` (`ActivityParser`, `BackStackParser`, `ApplicationBackStackParser`, `FragmentDumpParser`), so it can be tested without a connected device

### Fixed
- **Threading**: `currentBackStack` and `currentApplicationBackStack` now run ADB on a background thread and show popups on EDT; PSI lookups wrapped in `ReadAction.compute`
- **Crash**: `GetFragmentsCommand` — safe split access with `getOrNull` instead of hardcoded index, avoids `ArrayIndexOutOfBoundsException`
- **Crash**: `GetApplicationBackStackCommand` — safe array bounds (`getOrNull`) and removed `!!` force-unwrap on `find {}` result
- **Crash**: `Debugger` — replaced `client!!` with null-safe early return to avoid `NullPointerException` when the debug client is unavailable
- **Resource leak**: `AdbControllerImp` now implements `Disposable` and removes the `AndroidDebugBridge` device-change listener in `dispose()`
- **Resource leak**: `BaseAction` — `AdbControllerImp` is disposed immediately after the synchronous device-list read, instead of being incorrectly registered against `Project` as a long-lived disposable parent
- **Resource leak**: `AdbDrawerViewer` — `AdbControllerImp` is now registered against `toolWindow.disposable` instead of `Project`; the controller's lifetime correctly matches the tool window, not the entire project

### Changed
- `BaseAction` no longer uses `Disposer.register(project, controller)` — the controller is disposed explicitly after `connectedDevices()` returns, which is safe because the call is synchronous

### Removed
- Debug `println` statements from `SpockAdbViewer` and `GetApplicationBackStackCommand`
- Large commented-out dead code blocks in `SpockAdbViewer`, `ConnectDeviceOverIPCommand`, and `CheckBoxDialog`
- Duplicate empty `setting.addActionListener {}` in `SpockAdbViewer`

### Internal
- Fixed exception message `"Bazinga!!"` in `GetApplicationPermission` → professional message
- Renamed `kippAppProcess` → `killAppProcess` (typo fix) in `ProcessDeathCommand`

### Compatibility
- Lowered `sinceBuild` from `253` (Panda canary only) to `231` (Hedgehog 2023.1.1), adding support for all stable Android Studio releases from 2023 onward
- Compile target updated to Android Studio Meerkat (2025.1.1) — latest stable build
- `untilBuild` remains open-ended so new releases are accepted without a plugin update

## [3.0.1]
### Compatibility
- Lowered `sinceBuild` from `253` to `231`, intended to support Android Studio Hedgehog (2023.1.1) and later

> **Note:** this release did not work on the versions it claimed. The plugin was compiled
> against platform 251, which made its tool window factory reference platform methods absent
> on 231, so the tool window could not open on Android Studio 2023.1 through 2024.x. Fixed
> and verified in 4.0.0 — see `docs/COMPATIBILITY.md`.

## [3.0.0]
### Added
- Open Developer Options button in the developer panel
- Open Deep Link button — fire any URI intent directly from the IDE

### Fixed
- Activity detection on Android 13+: fallback from `mResumedActivity` to `topResumedActivity`
- Fragment detection: switch to `dumpsys activity top` and filter by visibility and parent to show only active fragments
- Threading violations: ADB shell commands now run on a background thread; UI updates posted back to EDT
- Stale listener bug: developer options listeners are removed before updating combo boxes to prevent duplicate ADB calls
- Replaced deprecated `createListPopupBuilder` API with `createPopupChooserBuilder`

### Changed
- Back stack activity detection on Android 11+: use `grep Hist` instead of legacy `sed` approach

## [2.0.3]
### Fixed
- Android Studio latest version compatibility

## [2.0.2]
### Fixed
- Android Studio latest version compatibility

## [2.0.1]
### Added
- Added button to open developer options
- Added button to open deep links

### Changed
- Don't Keep Activities only shows if setting is enabled or not (although setting seemed to change, the behaviour was maintained)

### Fixed
- Adds support for getting the backstack activities in Android 11

## [2.0.0]
### Added
- Get Current App BackStack (Activities and nested fragments)
- Add plugin actions e.g. GetCurrentFragment, RestartApp, etc.
- Allow choosing which buttons to show

### Fixed
- Support latest version of Android Studio
- Fix get current fragment
- Fix: if two instances of Android Studio are open, the plugin does not work properly

## [1.0.9]
### Changed
- The activity stack now shows activities by app package so the user can clearly see which package an activity belongs to
- The fragment stack can now show nested fragments and follows the same display rules as the activity stack command

## [1.0.8]
### Added
- Toggle on/off Wi-Fi or mobile data
- Add text to be input on the device

## [1.0.7]
### Added
- Restart app with debugger
- Uninstall and Clear App Data and Restart
- Toggle "Show Taps" setting
- Toggle "Show Layout Bounds" setting
- Toggle "Don't Keep Activities" setting
- Grant or Revoke all app permissions at once
- Change scale of Window Animation, Transition Animation, and Animator Duration

## [1.0.0]
### Added
- Navigate to current active Activity in your IDE
- Current BackStack Activities
- Navigate to current active Fragments
- Clear application data
- Enable and Disable Permissions of your application
- Kill or Restart Application
