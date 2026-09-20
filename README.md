[![Build](https://github.com/WahdanZ/SpockAdb/workflows/Build/badge.svg)](https://github.com/WahdanZ/SpockAdb/actions)
[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/11591-spock-adb)](https://plugins.jetbrains.com/plugin/11591-spock-adb)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/11591-spock-adb)](https://plugins.jetbrains.com/plugin/11591-spock-adb)

# Spock ADB

<!-- Plugin description -->
Full control of your Android device directly from Android Studio or IntelliJ IDEA — without leaving the IDE.

Spock ADB brings the Android workflows you normally reach for through `adb` into one shared tool window: inspect the selected app and device, jump to the current Activity or Fragment, manage lifecycle and permissions, browse and edit app storage, control connectivity, stream Logcat, run shell commands, inspect Views and Jetpack Compose UI, and send text or deep links to the device.

It also includes an **Android MCP server** for Claude Code, Claude Desktop, Cursor, and other MCP clients. Agents get 50 strongly typed Android tools instead of unrestricted shell access, while destructive operations remain approval-gated and visible in the IDE.

Works in **Android Studio** and **IntelliJ IDEA**.
<!-- Plugin description end -->

<p align="center">
  <img src="/images/spock-adb-overview.png" alt="Spock ADB unified Android tool window" width="100%">
</p>

---

## Why Spock ADB?

Android debugging usually means jumping between the IDE, Device Manager, Logcat, terminal commands, app-specific debug screens, and sometimes a proxy application.

Spock ADB keeps those workflows together and keeps the **selected device and selected app visible**, so an action never silently targets whichever device or package happened to be discovered first.

The plugin is designed around three ideas:

- **Fast from the IDE** — common ADB workflows are one click or one action away.
- **Explicit targets** — device and app context are shared across the tool window.
- **Safe automation** — MCP clients get structured tools, an audit trail, and confirmation for destructive changes.

---

## Version 4 highlights

Version 4 is the biggest evolution of Spock ADB so far. Instead of treating each 4.0.x release as a separate feature set, the 4.x line can be seen as one larger upgrade focused on a **unified Android debugging workspace, safer device control, richer app inspection, and MCP-powered automation**.

### Unified debugging workspace

- One shared tool window for **Device**, **Storage**, **Logcat**, **Commands**, **UI Inspector**, and **MCP Server**
- Shared **device + app context** across tabs and actions
- Responsive tabs with overflow under **More**
- Persistent status feedback with action result and duration
- Card-based Device UI that adapts to narrow and wide tool-window layouts
- Quick Actions that can be pinned, reordered, and remembered
- Search across actions and settings

### Richer device and app control

- Jump directly to the current **Activity** or **Fragment**
- Inspect app and system back stacks
- Restart, force stop, attach debugger, and simulate process death
- Clear cache without clearing app data
- Clear data / restart / uninstall with confirmation
- Runtime permission management based on what the device actually reports
- Wi-Fi and mobile-data state with read-back verification
- HTTP proxy controls with active-state detection and remembered proxy history
- Developer options for taps, layout bounds, activity retention, and animation scales
- Input text and deep-link launching from the IDE

### App storage browser and editor

- Browse the selected app's data tree, including `shared_prefs`, `files`, `databases`, `cache`, and DataStore
- Edit **SharedPreferences** and **Preferences DataStore** values using typed fields
- Add, edit, or remove keys
- Search files and keys
- Track unsaved changes before Apply
- Detect stale files before writing
- Read the file back after writing instead of assuming the operation succeeded
- Revert the last apply
- Export and import stored state
- Preserve unknown XML and protobuf fields
- Keep encrypted or unsupported storage formats read-only instead of decoding them incorrectly

### MCP for Android debugging

- Built-in MCP server for Claude Code, Claude Desktop, Cursor, and other MCP clients
- Both **HTTP** and **stdio** transports using the same protocol, tool registry, safety model, and audit trail
- Structured Android tools instead of unrestricted shell access
- Read-only inspection for device info, packages, app storage, Logcat, screenshots, and UI trees
- Actions for launching, tapping, text input, and deep links
- Approval-gated destructive or sensitive operations
- `android_get_debug_context` to collect Activity, UI semantics, recent Logcat, and an optional screenshot in one call
- File push / pull with restricted safe paths
- Screen recording
- Explicit project selection when multiple IDE projects are open
- MCP activity table with **Time · Tool · Access · Result · Duration**
- Request details, response details, target device, and approval outcome visible in the IDE

### Better debugging tools

- Logcat scoped to the selected app
- Presets for crashes, ANRs, errors, and network logs
- PID-based filtering instead of package-name text matching
- ADB Command Center with timeout, cancellation, favourites, history, output search, and execution status
- UI Inspector for both **Views** and **Jetpack Compose**
- Accessibility checks for missing labels, duplicate labels, and small touch targets
- Working screenshot capture inside Android Studio
- Safer and more reliable Fragment detection on modern Android versions

### Safety, stability, and compatibility

- Safer shell argument quoting to prevent command injection
- Confirmation for destructive operations
- Better handling of multiple devices and multiple open projects
- Persisted device selection
- Background-thread execution for ADB and MCP server operations that should not block the IDE
- Fixes for debugger compatibility across newer Android Studio versions
- Resource-leak and crash fixes
- Marketplace descriptor validation in CI
- JetBrains Plugin Verifier coverage across the supported IDE range
- Android Studio and IntelliJ IDEA support from the 2023.2 generation onward

For individual release details, see [CHANGELOG.md](CHANGELOG.md).

---

## One tool window, one target

Spock ADB now uses one shared tool window instead of separate pieces that can drift onto different device or app state.

The header keeps the active **device** and **package** together. The tabs below it reuse that same target, and tabs that do not fit move under **More** instead of wrapping into several rows.

The result of the latest action also stays visible with its duration, so success or failure does not disappear in a notification balloon.

---

## Device

The Device tab is organized into collapsible cards that adapt to the width of the tool window.

### App information

See what the selected package actually is:

- Package name
- Version and build
- Process / running state
- UID

This is especially useful when debug and release builds look identical on the device.

### Navigate

Jump straight from the running app to code:

- **Current activity**
- **Current fragment**
- **App back stack**
- **All activities**

Fragment lookup uses the selected package rather than simply trusting whichever app is currently on top.

### App lifecycle

Common lifecycle actions stay close together:

- Restart app
- Restart with debugger
- Force stop
- Simulate process death

### Danger zone

Destructive actions are visually separated and confirmed:

- Clear app data
- Clear app data and restart
- Uninstall app

### Permissions

Inspect the runtime permissions reported by the device itself and:

- Manage permissions individually
- Grant all
- Revoke all

The plugin no longer relies on an old hard-coded Android permission list, so newer runtime permissions are included.

### Device connectivity

Read and change actual device state:

- Wi-Fi status and connected network
- Mobile-data state
- HTTP proxy
- Active proxy state

Proxy changes are read back from the device instead of assuming that a successful shell exit means the setting really changed.

Previously used proxies are remembered locally for quick reuse.

### Developer options

Control common debugging settings without opening Android Settings:

- Don't keep activities
- Show taps
- Show layout bounds
- Window animation scale
- Transition animation scale
- Animator duration scale
- Reset animation scales

### Send to device

- Input text
- Open a deep link

Values sent through the shell are quoted safely before execution.

### Quick Actions and search

Pin the actions you use repeatedly to the top of the Device tab and reorder them.

The **Search actions…** field filters actions and settings by name or tooltip and temporarily opens matching sections without destroying your saved expansion state.

---

## App storage

<p align="center">
  <img src="images/app-storage.png" alt="Spock ADB app storage browser and preference editor" width="100%">
</p>

Browse the selected app's data directly from the IDE.

The storage tree can show directories such as:

- `shared_prefs`
- `files`
- `files/datastore`
- `databases`
- `cache`
- other files created by the app

Directories are loaded lazily as they are expanded so a large cache does not make the whole panel expensive to open.

### Edit SharedPreferences and Preferences DataStore

Supported preference files open as a typed editor with values such as:

- boolean
- int / long
- float / double
- string
- string set
- bytes

You can:

- add or edit a key
- remove a key
- search files
- search keys
- see unsaved-change counts
- **Apply changes**
- **Revert last apply**
- **Export**
- **Import**

Before writing, Spock ADB checks that the file has not changed since it was read. It checks again after stopping the app and reads the result back after the write rather than trusting the command blindly.

Unknown XML elements and unknown protobuf fields are preserved.

`EncryptedSharedPreferences` are shown read-only, and Proto DataStore files using an app-specific schema are reported as unsupported instead of being decoded incorrectly.

> App storage editing and Clear Cache require a **debuggable** app because they use `run-as`.

---

## Clear Cache

Clear only the selected app's internal:

- `cache/`
- `code_cache/`

This keeps login state, databases, SharedPreferences, and the rest of the app's data intact.

Spock ADB deliberately uses `run-as` rather than relying on device-specific `pm clear --cache-only` behavior that can be unsafe on older Android versions.

---

## Logcat

![Logcat tab](images/logcat.png)

Logcat is scoped to the selected app by default.

- Presets for **Current app**, **Errors only**, **Crashes**, **ANRs**, and **Network**
- Process-ID filtering instead of package-name text matching
- Level, tag, text, and regex filters
- Crash and ANR highlighting
- Pause, clear, copy, export, and auto-scroll

An invalid regex is treated as invalid instead of silently falling back to an unfiltered log.

---

## ADB Command Center

![ADB Command Center](images/command-center.png)

Run commands that normally follow `adb shell` without leaving the IDE.

The command panel includes:

- target device shown beside the command
- timeout
- real cancellation
- completion / failure / cancellation state with duration
- de-duplicated command history
- favourites
- searchable output

Potentially destructive commands are identified before execution.

---

## UI Inspector

![UI Inspector](images/ui-inspector.png)

Inspect what is currently on screen using the accessibility / semantics tree, including **Jetpack Compose**.

Spock ADB identifies the screen as:

- Views
- Jetpack Compose
- hybrid

For each node you can inspect information such as:

- test tag
- text
- content description
- bounds
- clickable / focusable / enabled state
- other interaction flags

The built-in accessibility audit can flag:

- unlabelled interactive controls
- touch targets smaller than 48dp
- duplicate labels

The suggested fix is framework-aware, so Compose screens get Compose guidance instead of View-only XML advice.

---

## MCP Server

<p align="center">
  <img src="/images/mcp-server.png" alt="Spock ADB MCP server activity panel" width="100%">
</p>

Give an AI coding agent structured access to a connected Android device.

The MCP server is **off by default** and starts only when you choose to run it.

### Transports

Spock ADB supports:

- **HTTP**
- **stdio**

Both transports use the same protocol implementation, tool registry, device services, safety rules, and activity log.

The stdio client configuration does not embed the authentication token; it connects through the local endpoint managed by the plugin.

### Tooling

The MCP toolset covers workflows such as:

- device and package information
- current Activity / Fragment / back stack
- Logcat
- screenshots
- UI semantics
- taps and text input
- deep links
- app lifecycle
- permissions
- app storage reads and preference edits
- HTTP proxy state
- file push / pull with restricted paths
- screen recording
- bundled debug context

`android_get_debug_context` can collect the current Activity, UI semantics, recent Logcat, and optionally a screenshot in one call so the pieces describe the same debugging moment.

### Safety model

| Access | Examples | Behaviour |
|---|---|---|
| ✓ **Read-only** | device info, packages, storage reads, Logcat, screenshots, UI tree | runs automatically |
| ⚡ **Actions** | launch, tap, input text, deep link | runs automatically |
| ⚠ **Destructive / sensitive changes** | clear data, uninstall, revoke permissions, edit preferences, proxy changes, arbitrary shell | approval required where defined by the safety model |

The activity panel records:

**Time · Tool · Access · Result · Duration**

Selecting a request shows its details, arguments, result, target and approval outcome.

See [docs/MCP.md](docs/MCP.md) for setup, the complete tool list, safety details, and example workflows.

---

## IDE actions and keyboard shortcuts

<p align="center">
  <img src="/images/ide-actions.png" alt="Spock ADB actions in the Android Studio Tools menu" width="100%">
</p>

Core operations are exposed as IntelliJ Actions, so they can be found through **Find Action**, the **Tools → Spock ADB** menu, and `Settings → Keymap → Spock ADB`.

Examples include:

- Open Current Activity
- Open Current Fragment
- Show App Back Stack
- Show Activity Stack
- Restart App
- Restart App With Debugger
- Force Stop App
- Test Process Death
- Clear App Data
- Clear App Data and Restart
- Uninstall App
- Open Devices
- Open Logcat
- Open ADB Command Center
- Open UI Inspector
- Open MCP Server Panel
- Open Developer Options on Device
- Start / stop / restart MCP server
- Copy MCP client configuration

**No default keyboard shortcuts are claimed.** Assign the combinations that fit your keymap.

Actions disable themselves when their required context is missing and report why.

---

## Supported IDEs

| IDE | Versions |
|---|---|
| **Android Studio** | 2023.2 (Iguana) and later |
| **IntelliJ IDEA** | 2023.2 and later, with the Android plugin installed |

Releases are checked with JetBrains Plugin Verifier across the supported IDE matrix.

See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for details.

> `Restart App With Debugger` depends on Android execution APIs that differ across IDE versions. Spock ADB contains compatibility handling and hides the action where the required execution tooling is unavailable.

---

## Installation

**JetBrains Marketplace:** [Spock ADB](https://plugins.jetbrains.com/plugin/11591-spock-adb)

Or from the IDE:

`Settings → Plugins → Marketplace → search "Spock ADB"`

---

## Quick start

1. Open an Android project.
2. Connect a device or start an emulator.
3. Open the **Spock ADB** tool window.
4. Select the target device.
5. Confirm or choose the target app/package in the shared header.
6. Use **Device**, **Storage**, **Logcat**, **Commands**, **UI Inspector**, or **MCP Server**.
7. Optional: start the MCP server and copy either the **stdio** or **HTTP** client configuration into your MCP client.

---

## Troubleshooting

**The device list is empty.**  
Check that `adb devices` can see the device. Spock ADB refreshes its device state when the tool window becomes active.

**A device is `unauthorized`.**  
Accept the USB-debugging prompt on the device.

**The application ID cannot be determined.**  
Open an Android project and let Gradle sync complete, or choose another installed app where the UI allows it.

**App storage or Clear Cache says the app is not debuggable.**  
Those features use `run-as`, which Android exposes only for debuggable builds.

**Current Fragment reports nothing.**  
Make sure the selected package is the app whose fragment hierarchy you want to inspect.

**UI Inspector cannot dump the UI.**  
`uiautomator` may fail while the screen is off, while a secure `FLAG_SECURE` window is visible, or while the UI is rapidly changing.

**Compose test tags are missing.**  
The app needs to expose them through semantics, for example with `testTagsAsResourceId` where appropriate.

**A proxy was removed but the input still contains the previous value.**  
The input remembers proxy history for reuse; check **Active proxy** to see what the device is actually using.

---

## Documentation

| Document | Contents |
|---|---|
| [docs/MCP.md](docs/MCP.md) | MCP setup, tools, transports, safety model, and workflows |
| [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) | IDE support and verification matrix |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Development and release process |
| [CHANGELOG.md](CHANGELOG.md) | Full release history |

---

## Development

```bash
./gradlew runIde         # launch a sandboxed IDE with the plugin
./gradlew test           # unit tests
./gradlew detekt         # static analysis
./gradlew verifyPlugin   # Plugin Verifier
./gradlew buildPlugin    # build the installable ZIP
```

---

## Demo

[![Demo video](http://img.youtube.com/vi/x_WX_Pznqos/0.jpg)](http://www.youtube.com/watch?v=x_WX_Pznqos)

---

## License

```text
Copyright 2019 Ahmed Wahdan

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
