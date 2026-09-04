# Changelog

## [Unreleased]

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
