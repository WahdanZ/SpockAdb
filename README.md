[![Build](https://github.com/WahdanZ/SpockAdb/workflows/Build/badge.svg)](https://github.com/WahdanZ/SpockAdb/actions)
[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/11591-spock-adb)](https://plugins.jetbrains.com/plugin/11591-spock-adb)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/11591-spock-adb)](https://plugins.jetbrains.com/plugin/11591-spock-adb)

# Spock ADB

<!-- Plugin description -->
Control and debug Android devices directly from Android Studio or IntelliJ IDEA.

Spock ADB puts common ADB workflows in one tool window: app actions, storage, Logcat, shell commands, UI inspection, and an optional MCP server for AI coding tools.
<!-- Plugin description end -->

<p align="center">
  <img src="images/spock-adb-overview.png" alt="Spock ADB tool window" width="100%">
</p>

## Features

- Select the target device and app once for every tool
- Open the current Activity or Fragment
- Restart, force stop, debug, clear data, or uninstall an app
- Manage runtime permissions and developer options
- Browse and edit app storage for debuggable apps
- View focused Logcat output with useful filters
- Run ADB shell commands inside the IDE
- Inspect Views and Jetpack Compose UI
- Find basic accessibility issues
- Send text and open deep links on the device
- Connect AI tools through the built-in Android MCP server

## Installation

Install [Spock ADB from the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/11591-spock-adb).

Or open:

`Settings → Plugins → Marketplace → search for "Spock ADB"`

Spock ADB supports Android Studio 2023.2+ and IntelliJ IDEA 2023.2+ with the Android plugin.

## Quick start

1. Open an Android project.
2. Connect a device or start an emulator.
3. Open the **Spock ADB** tool window.
4. Select a device and app.
5. Choose **Device**, **Storage**, **Logcat**, **Commands**, **UI Inspector**, or **MCP Server**.

> Storage editing and Clear Cache require a debuggable app.

## Android MCP server

Spock ADB can give Claude Code, Claude Desktop, Cursor, and other MCP clients structured access to a connected Android device.

The server is disabled by default. Start it from the **MCP Server** tab, then copy or install the generated client configuration. Sensitive and destructive operations require approval.

See [MCP documentation](docs/MCP.md) for setup, supported tools, and safety details.

## Troubleshooting

- **No device:** check that `adb devices` can see it.
- **Unauthorized device:** accept the USB debugging prompt.
- **No app detected:** wait for Gradle sync or select an installed package.
- **Storage unavailable:** use a debuggable build.
- **UI Inspector fails:** unlock the device and make sure no secure window is open.

## Documentation

- [MCP setup and tools](docs/MCP.md)
- [IDE compatibility](docs/COMPATIBILITY.md)
- [Release history](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)

## Development

```bash
./gradlew runIde
./gradlew test
./gradlew detekt
./gradlew verifyPlugin
./gradlew buildPlugin
```

## License

Spock ADB is available under the [Apache License 2.0](LICENSE).
