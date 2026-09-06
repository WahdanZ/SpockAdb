# Changelog

## [Unreleased]

### Added

- **The in-IDE AI assistant.** A new `Assistant` tab: ask about the connected device and the
  model uses the plugin's own tools to answer, rather than describing what is usually true of
  Android. It runs against the same `ToolContext` the MCP transports do — one device selection,
  one project resolution, one confirmation dialog — and every call lands in the same activity
  history as `spock-assistant`. Destructive tools still ask per call in the IDE's own modal,
  never as an inline chat approval, and tools switched off in Tool Access refuse here too.
  "Attach debugging context" runs `android_get_debug_context` once at the start of a
  conversation, so the model begins with the activity, the UI semantics and recent logcat in
  hand. Ctrl+Enter sends, Esc stops at the next step rather than mid-request, and closing the
  tool window cancels a turn in flight
- **Everything the assistant reads leaves your machine** — your questions and every tool result,
  screenshots and logcat included, go to the configured provider. That is stated in the panel
  and in Settings, not only in the docs, because it is the one consequence a developer cannot
  undo afterwards. Configure it under `Settings → Tools → Spock ADB → AI Assistant`; the API key
  field is write-only, and a stored key is never rendered back into it
- `docs/AI.md`, with the privacy section first
- **The Devices tab now says when AI agents are targeting a different phone.** The agent's
  `android_select_device` choice and the tool window dropdown are independent, so a developer
  could be watching one device while an agent cleared app data on another. Shown only on a
  mismatch — a permanent "these agree" banner would train you to stop reading it
- The MCP panel header reports live stdio sessions, and only when there are any. Sockets, not
  clients: neither transport knows who is calling until the client says so
- The UI Inspector's "Compose test tags are not exposed" note is now a banner with a Copy
  Modifier button. As a grey line beside the framework name it read as trivia, so it was missed
  by exactly the people it is for — it is the difference between an agent that can address
  elements by `testTag` and one reduced to matching visible text
- Docked below ~500px, the MCP panel's detail pane becomes a collapsible section instead of a
  28% split that is too small to read and too big to spare
- **The assistant core** behind it: `AgentLoop` runs the model ⇄ tool cycle against the same
  `ToolRegistry` the MCP transports use, so there is one definition of what an agent may do and
  one safety model. A declined call is reported back to the model in words rather than ending
  the conversation. The loop is capped at 25 iterations, which is the only guard against a
  surprise bill in this version
- `AnthropicClient` and `OpenAiCompatibleClient` on `java.net.http` and Gson — no SDK, no new
  dependency, and no change to the supported IDE range. Provider errors are surfaced verbatim
  and never retried: retrying a rejected request spends money to be rejected again
- The API key lives in `PasswordSafe` and nowhere else — never in the settings XML, the audit
  history or the log
- **Per-tool access control.** `Settings → Tools → Spock ADB → Tool Access` lists every tool
  grouped by safety level, with "Enable all" and "Read-only only". Confirmation alone could not
  express this: it sees one call at a time, and `android_push_file` and `android_pull_file` are
  individually reasonable but compose into reading any file on the machine. A disabled tool is
  still listed and still described, and refuses when called naming itself and where the switch
  is, so an agent is told it was turned off rather than hunting for a tool it can see
  documented. The refusal is audited like any other call — an agent reaching for something it
  was denied is the entry most worth reviewing. Both ways in consult the same setting
- **The activity history now survives a restart.** Calls are appended as newline-delimited JSON
  under the IDE config directory, capped by the existing "keep the most recent N requests"
  setting and written off the calling thread in batches, so an agent never waits on a disk
  write. A file truncated by a crash costs one record rather than the history, and a failure to
  persist is logged rather than failing the tool call that was being recorded

### Fixed

- **Restart with Debugger crashed instead of falling back on newer Android Studio.**
  `AndroidJavaDebugger.attachToClient` has gained and lost a trailing parameter across releases,
  and the plugin was written to try the new shape and fall back to the old one — but the fallback
  was unreachable. jOOR reports a missing method as a `ReflectException` *caused by*
  `NoSuchMethodException`, and the check that decided "this IDE has a different API" looked only
  at the throwable it was handed, never at what that wrapped. Every miss was therefore treated as
  a real error, so the compatibility path never ran and the developer got
  `RuntimeException: ReflectException: NoSuchMethodException: No similar method attachToClient`.
  The check now walks the cause chain, cycle-guarded because it runs on the EDT. If neither known
  shape fits, the attach is driven from the signature the class actually declares, and if that
  fails too the message names the real signature instead of a reflection library — so the next
  report of this carries what is needed to fix it. `BackwardCompatibleGetter` moved to its own
  file, free of IntelliJ types, so the rule is covered by tests rather than only by inspection
- **`android_take_screenshot` never worked inside Android Studio.** It called
  `IDevice.getScreenshot()`, which the IDE ships as a stub that fails with "This method is not
  used in Android Studio", so every call returned that message instead of an image. Capture now
  goes through `screencap -p` on the shell, like every other tool. The bytes come back base64
  encoded because ddmlib's shell channel decodes its output as text and would otherwise corrupt
  the PNG, and the result is checked for a PNG signature so a `FLAG_SECURE` screen is reported
  as such rather than returned as a broken image
- **Every project-dependent MCP tool failed whenever two projects were open.** The tool
  context resolved the project with `openProjects.singleOrNull { !it.isDisposed }`, so a
  second open project turned `android_get_current_activity`, `android_get_activity_stack`,
  `android_get_current_fragments` and the default logcat package filter into "No project is
  open" — a message that was both wrong and unactionable. Resolution now follows the same
  rule as device resolution: use the selected project, or the only one open, and otherwise
  **refuse to guess** and name the candidates. Picking the focused window instead would be
  wrong exactly when it matters most, with an agent working while the developer looks
  elsewhere
- **Starting and stopping the MCP server ran on the EDT.** Starting binds two sockets and
  writes the stdio endpoint descriptor; stopping waits for live stdio sessions to end before
  releasing their threads. Stopping the server from the MCP panel with a client attached
  therefore froze the tool window until that wait expired. Both transitions now run on a
  pooled thread, the controls show the transition and are disabled while it runs, and
  Restart chains stop → start rather than issuing them together
- `McpServerService.start()` is idempotent: starting an already-running server returns the
  bound port instead of replacing the HTTP server and stranding the previous stdio bridge's
  threads

### Added

- **`android_get_debug_context`** — the whole triage bundle in one call: current activity, the
  UI semantics tree with its framework identified, recent logcat, and optionally a screenshot.
  Assembling those separately cost three or four round trips, and by the time the last landed
  the screen could have moved on, so the bundle described no single moment. A failing section
  reports its failure in place and the rest still come back — a screenshot blocked by
  `FLAG_SECURE` must not cost you the crash sitting beside it in logcat
- **`android_push_file` and `android_pull_file`.** Device paths are restricted to `/sdcard`,
  `/storage` and `/data/local/tmp`, which is deliberately stricter than `adb`: it will hand over
  anything the shell user can read, and an agent that can be talked into pulling another app's
  database is an exfiltration path wearing a debugging tool's clothes. The local destination of
  a pull is **not** a parameter — a tool that writes where its caller asks lets anything holding
  the MCP token drop a file anywhere on the filesystem — so pulls land in one known directory
  and the tool reports where. The source of a push is restricted to the open project or
  that same directory, so the pair cannot be composed into a read of any file on the
  machine. Transfers are capped at 50 MB in both directions
- **`android_start_screen_recording` and `android_stop_screen_recording`**, one session per
  device, capped at three minutes. Recording stops with `SIGINT` rather than `SIGKILL` so
  `screenrecord` writes the MP4 index on the way out — a killed recording leaves a file no
  player will open — and the remote file is deleted only once the pull has succeeded
- `android_select_project` — says which open project later calls are about, so the ambiguity
  above names a fix the agent can actually perform. Unnecessary with a single project open

### Internal

- `StubbedIDeviceApiTest` fails the build when anything calls an `IDevice` method Android
  Studio leaves unimplemented. Each throws "This method is not used in Android
  Studio" at runtime while compiling and unit-testing cleanly, because a test that builds its
  own `AndroidDebugBridge` gets stock ddmlib where they all work. It scans compiled bytecode
  rather than source, since Kotlin's property syntax hides the call — `device.screenshot` is a
  call to `getScreenshot()` that no text search would find. This is the bug that shipped in
  `android_take_screenshot`
- `McpSmokeTest` calls every read-only tool against a real device through a running server.
  The live checks are opt-in via `SPOCK_MCP_URL` and `SPOCK_MCP_TOKEN`, so an ordinary
  `./gradlew test` skips them, but its coverage assertion always runs — a read-only tool cannot
  be added without deciding how it is smoke-tested

## [4.0.2] - 2026-09-04

### Added

- **stdio transport for the MCP server**, served by the same `McpProtocol`, `ToolRegistry`,
  safety model, device services and audit trail as the HTTP transport — a tool call arriving
  over stdio is confirmed, recorded and logged exactly as the same call over HTTP
- `Tools → SpockAdb → Copy MCP Client Configuration (stdio)`. **It contains no credential**:
  the client is pointed at an endpoint descriptor, and the token stays in that `600` file
- `McpStdioServer` — newline-delimited JSON-RPC framing, request cancellation via
  `notifications/cancelled` (the call is interrupted and its response suppressed), and a
  worker pool so a cancellation arriving behind a slow tool call is still read
- `McpBridgeServer` — a Unix domain socket in a `700` directory, falling back to loopback TCP
  where `AF_UNIX` is unavailable or the path is too long for `sun_path`. Every connection
  presents the token on both transports; one that never does is closed after ten seconds, and
  the session pool is bounded, so nothing that reaches the endpoint can hold threads open
- `SpockAdbStdioLauncher` — the process an MCP client spawns. A dependency-free Java byte
  relay with no knowledge of MCP, so nothing about the protocol is implemented twice. It
  claims the real stdout and redirects `System.out` to stderr, so no log line can corrupt the
  protocol stream

[Unreleased]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.1...HEAD
## [4.0.1] - 2026-09-04

### Added

- `scripts/verify-marketplace-descriptor.sh` — gates the built descriptor before it reaches JetBrains Marketplace: the plugin id must be `com.wahdan.com.wahdan.spockAdb`, there must be no `until-build` cap, `since-build` must match `pluginSinceBuild`, and the version must match the release tag. Runs in `build.yml` on every pull request and in `release.yml` immediately before `publishPlugin`

### Documentation

- `docs/COMPATIBILITY.md` records why the Marketplace served 1.0.2 to modern IDEs for four years: 2.0.x shipped an `until-build` cap that silently expired the release, and 3.0.x renamed the plugin id so it could never reach listing 11591
- `CONTRIBUTING.md` no longer claims a release is live "within a few minutes" — a green `release.yml` means uploaded, not approved and served — and says how to confirm which version an IDE is actually offered

## [4.0.0] - 2026-09-04

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

### Added

- `BaseAction` now declares `ActionUpdateThread.BGT` and disables its actions when no project is open
- Tests for `ShellOutputReceiver` chunking and trailing-newline handling, and for the device state enums

### Compatibility

- Lowered `sinceBuild` from `253` (Panda canary only) to `231` (Hedgehog 2023.1.1), adding support for all stable Android Studio releases from 2023 onward
- Compile target updated to Android Studio Meerkat (2025.1.1) — latest stable build
- `untilBuild` remains open-ended so new releases are accepted without a plugin update

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

### Removed

- Debug `println` statements from `SpockAdbViewer` and `GetApplicationBackStackCommand`
- Large commented-out dead code blocks in `SpockAdbViewer`, `ConnectDeviceOverIPCommand`, and `CheckBoxDialog`
- Duplicate empty `setting.addActionListener {}` in `SpockAdbViewer`

### Internal

- Fixed exception message `"Bazinga!!"` in `GetApplicationPermission` → professional message
- Renamed `kippAppProcess` → `killAppProcess` (typo fix) in `ProcessDeathCommand`

## [3.0.1]

### Compatibility

- Lowered `sinceBuild` from `253` to `231`, intended to support Android Studio Hedgehog (2023.1.1) and later

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

[Unreleased]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.2...HEAD
[4.0.2]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.1...v4.0.2
[4.0.1]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.0...v4.0.1
[4.0.0]: https://github.com/WahdanZ/SpockAdb/compare/v3.0.1...v4.0.0
[3.0.1]: https://github.com/WahdanZ/SpockAdb/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.3...v3.0.0
[2.0.3]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.2...v2.0.3
[2.0.2]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.1...v2.0.2
[2.0.1]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.9...v2.0.0
[1.0.9]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.0...v1.0.7
[1.0.0]: https://github.com/WahdanZ/SpockAdb/commits/v1.0.0
