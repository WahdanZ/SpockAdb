<!-- Keep a Changelog guide -> https://keepachangelog.com -->

## [Unreleased]
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
