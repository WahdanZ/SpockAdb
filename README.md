<p align="center">
  <img src="images/banner.png" alt="Spock ADB — Android device debugging without leaving the IDE" width="100%">
</p>

<p align="center">
  <a href="https://github.com/WahdanZ/SpockAdb/actions"><img src="https://github.com/WahdanZ/SpockAdb/workflows/Build/badge.svg" alt="Build status"></a>
  <a href="https://plugins.jetbrains.com/plugin/11591-spock-adb"><img src="https://img.shields.io/jetbrains/plugin/v/11591-spock-adb" alt="JetBrains Marketplace version"></a>
  <a href="https://plugins.jetbrains.com/plugin/11591-spock-adb"><img src="https://img.shields.io/jetbrains/plugin/d/11591-spock-adb" alt="Downloads"></a>
</p>

# Spock ADB

## Android debugging without leaving the IDE

<!-- Plugin description -->
**Inspect, control, and debug your Android app and device directly from Android Studio or IntelliJ IDEA — without constantly switching to the terminal, Device Manager, Settings, or external tools.**

Spock ADB brings ADB workflows into one shared tool window that keeps a selected device and app as the active target across Device, Storage, Logcat, Commands, UI Inspector, and Background Work — plus a built-in MCP server exposing 58 strongly typed Android tools for Claude Code, Claude Desktop, Cursor, and other AI clients.
<!-- Plugin description end -->

**One IDE · One device target · Fewer ADB commands**

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/11591-spock-adb"><strong>Install from JetBrains Marketplace</strong></a> ·
  <a href="docs/MCP.md"><strong>View Documentation</strong></a>
</p>

<p align="center">
  <img src="images/spock-adb-overview.png" alt="Spock ADB tool window" width="100%">
</p>

---

## Contents

- [Stop jumping between tools](#stop-jumping-between-tools)
- [Everything you need for Android debugging](#everything-you-need-for-android-debugging)
  - [📱 Device Control](#device-control)
  - [🗂 App Storage](#app-storage)
  - [📜 Logcat, without the noise](#logcat)
  - [⌨️ ADB Command Center](#adb-command-center)
  - [🔍 UI Inspector](#ui-inspector)
  - [🛠 Background Work](#background-work)
  - [🤖 Android debugging for AI agents](#ai-agents)
- [Why use Spock ADB?](#why-use-spock-adb)
- [Built for real debugging workflows](#built-for-real-debugging-workflows)
- [Get started](#get-started)
- [Documentation](#documentation)
- [License](#license)

---

## Stop jumping between tools

A normal Android debugging session can quickly become:

`Android Studio → Terminal → adb → Logcat → Device Settings → back to Android Studio`

Spock ADB brings those workflows into **one shared tool window** and keeps the selected **device + app** as the active target.

| Task                       | Without Spock ADB                        | With Spock ADB                    |
|----------------------------|------------------------------------------|-----------------------------------|
| Find current Activity      | `adb shell dumpsys...` + search output   | **Open Current Activity**         |
| Find current Fragment      | dumpsys / debug code / manual inspection | **Open Current Fragment**         |
| Restart / force stop       | Terminal ADB commands                    | **One action**                    |
| Test process death         | Manual ADB commands                      | **One action**                    |
| Manage permissions         | `pm grant` / `pm revoke`                 | **Visual permission controls**    |
| Inspect SharedPreferences  | `run-as` + shell + file editing          | **Browse & edit directly**        |
| Inspect DataStore          | Pull/read files manually                 | **Built-in typed editor**         |
| App-only Logcat            | Find PID + build filters                 | **App scope automatically**       |
| Investigate crashes / ANRs | grep/filter Logcat manually              | **Built-in filters**              |
| Inspect background work    | Read large `dumpsys` job/alarm output    | **Jobs and alarms in tables**     |
| Inspect UI hierarchy       | `uiautomator dump` + XML inspection      | **Visual UI Inspector**           |
| Check accessibility        | Manual inspection                        | **Built-in accessibility checks** |
| Send a deep link           | `adb shell am start ...`                 | **Open Deep Link**                |
| Change HTTP proxy          | `settings put/get ...`                   | **Proxy controls + verification** |
| Run ADB commands           | Leave the IDE for Terminal               | **ADB Command Center**            |
| Give AI access to Android  | Custom scripts / shell access            | **Structured MCP tools**          |

### The result

Workflows that normally require **several commands, copy/paste operations, PID lookups, and context switches** become one or two actions without leaving your editor.

---

# Everything you need for Android debugging

<a id="device-control"></a>
### 📱 Device Control

Control the selected app and device without memorizing ADB commands.

**Activity & Fragment navigation · Back stack · Restart · Force stop · Debugger · Process death · Permissions · Connectivity · Proxy · Developer options · Deep links**

<p align="center">
  <img src="images/devices.png" alt="Spock ADB device and app controls" width="88%">
</p>

---

<a id="app-storage"></a>
### 🗂 App Storage

Inspect your app's private storage directly from the IDE.

Browse:

`shared_prefs/` · `files/` · `databases/` · `cache/` · `DataStore`

Edit supported **SharedPreferences and Preferences DataStore** values using typed fields instead of manipulating files through `adb shell run-as`.

Changes are checked, written, and read back instead of assuming the command worked.

<p align="center">
  <img src="images/app-storage.png" alt="App storage browser" width="88%">
</p>

---

<a id="logcat"></a>
### 📜 Logcat, without the noise

Logcat starts with the **selected app**, not the entire device.

Choose the scope you actually need:

**App** — only your application's processes
**Related** — your app + relevant Android system components
**All** — the full device log

Then narrow it further with:

**Errors · Crashes · ANRs · Network · Search · Regex**

No manual PID lookup.
No giant `adb logcat | grep ...` commands.

<p align="center">
  <img src="images/logcat.png" alt="Focused Logcat inside Spock ADB" width="88%">
</p>

---

<a id="adb-command-center"></a>
### ⌨️ ADB Command Center

Still need the shell?

Run ADB commands inside the IDE with:

**Device targeting · History · Favourites · Timeout · Cancellation · Searchable output · Execution status**

You keep the power of ADB without constantly opening another terminal.

<p align="center">
  <img src="images/command-center.png" alt="ADB Command Center" width="88%">
</p>

---

<a id="ui-inspector"></a>
### 🔍 UI Inspector

Inspect the UI currently running on the device.

Supports:

**Android Views · Jetpack Compose · Hybrid screens**

Inspect text, content descriptions, bounds, test tags, interaction state, and other semantics.

Spock ADB can also detect common accessibility problems such as:

**Missing labels · Duplicate labels · Touch targets below 48dp**

<p align="center">
  <img src="images/ui-inspector.png" alt="View and Compose UI inspector" width="88%">
</p>

---

<a id="background-work"></a>
### 🛠 Background Work

See what's actually scheduled to run on the device.

Inspect:

**Scheduled jobs (JobScheduler & WorkManager) · Pending alarms**

Run a supported job on demand instead of waiting for the system to trigger it, and inspect — with a reset — the device conditions that gate background work:

**Doze · App Standby buckets · Battery state**

<p align="center">
  <img src="images/background_task.png" alt="Background Work: scheduled jobs, alarms, and device conditions" width="88%">
</p>

---

<a id="ai-agents"></a>
### 🤖 Android debugging for AI agents

Spock ADB includes a built-in **MCP server** for tools such as Claude Code, Claude Desktop, Cursor, and other MCP clients.

Instead of giving an AI agent unrestricted shell access, Spock ADB exposes **58 structured Android debugging tools**.

Agents can inspect things such as:

**Activity · Fragment · UI tree · Logcat · Screenshots · Storage · Background work · Device state**

and perform actions such as:

**Launch · Tap · Input text · Deep links · App lifecycle operations**

Sensitive or destructive operations can require approval, and MCP activity remains visible inside the IDE.

<p align="center">
  <img src="images/mcp-server.png" alt="Spock ADB MCP server activity panel" width="88%">
</p>

See [MCP documentation](docs/MCP.md) for setup, the complete tool list, and safety details.

---

# Why use Spock ADB?

**Without Spock**

Android Studio
↓
Terminal
↓
Find the device
↓
Find the package
↓
Remember the ADB command
↓
Parse the output
↓
Switch back to the IDE

**With Spock**

Android Studio
↓
Select device + app
↓
**Run the action**

---

## Built for real debugging workflows

Spock ADB isn't an ADB command cheat sheet.

It provides a shared debugging workspace where **Device, Storage, Logcat, Commands, UI Inspector, Background Work, and MCP** all use the same selected Android device and application.

That means less setup, fewer targeting mistakes, and less time spent fighting your tools.

---

## Get started

1. Install **[Spock ADB](https://plugins.jetbrains.com/plugin/11591-spock-adb)** from the JetBrains Marketplace.
2. Connect a device or start an emulator.
3. Open the **Spock ADB** tool window.
4. Select your device and application.
5. Debug.

> No Android project handy? `./gradlew -p sample :app:installDebug` builds the bundled [sample app](sample/README.md) — every plugin feature has a labeled screen to try it against.

**Spend less time operating ADB. Spend more time debugging your app.**

---

## Documentation

[MCP setup & tools](docs/MCP.md) · [IDE compatibility](docs/COMPATIBILITY.md) · [Release history](CHANGELOG.md) · [Contributing](CONTRIBUTING.md)

## License

Spock ADB is available under the [Apache License 2.0](LICENSE).
