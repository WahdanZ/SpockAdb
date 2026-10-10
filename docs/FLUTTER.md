# Spock ADB for Flutter apps

Spock ADB works on a Flutter app the way it works on any Android app: Home, Storage, Logcat, the
Timeline, Diagnose, the UI Tree and the MCP tools all act on the selected device and app. On top
of that it understands what a Flutter app puts on the device, and — while the app runs under
`flutter run` — reads its Dart VM Service by itself, so Flutter's own errors, routes, frames and
failed requests sit next to the device's log and state on one clock.

Android only for now. iOS simulator support is planned ([PLAN-FLUTTER.md](PLAN-FLUTTER.md), P6).

- [What Spock adds](#what-spock-adds)
- [The Flutter session](#the-flutter-session)
- [Diagnose's `flutter` section](#diagnoses-flutter-section)
- [Timeline, HTTP recording and rebuilds](#timeline-http-recording-and-rebuilds)
- [Home: Flutter route and App back stack](#home-flutter-route-and-app-back-stack)
- [Limits](#limits)
- [Security: VM Service addresses](#security-vm-service-addresses)
- [Spock vs the Flutter plugin vs Dart MCP](#spock-vs-the-flutter-intellij-plugin-vs-dart-mcp)
- [Running Spock MCP and Dart MCP together](#running-spock-mcp-and-dart-mcp-together)

## What Spock adds

These need no connection to the app, and work for any Flutter build:

- **Home** tells a Flutter app from its APK — `libflutter.so` in the installed APKs, split APKs
  included — and shows *Flutter debug*, *profile* or *release* beside the version. Debug is the
  JIT build; profile and release are told apart by the package's `DEBUGGABLE` flag alone, so a
  release build made debuggable reads as profile. The APK is listed with `unzip`, which Android
  ships from Android 10; on older devices the app reads as not Flutter.
- **Storage** reads `shared_prefs/FlutterSharedPreferences.xml` the way Dart does: the
  `flutter.`-prefixed keys show their real type — a double as a double, a `List<String>` as a
  *string list* — not the encoded strings `shared_preferences` writes, and an edit is written back
  in the same encoding. A Hive box (`*.hive`) shows as a key/type/value table; a SQLite database
  (sqflite's `databases/*.db`) as its tables, row counts and first rows, read on the device with
  `sqlite3` where it exists. Hive and SQLite are read-only.
- **Spock Logcat** has a *Flutter* view: Dart output (`print`, `debugPrint`), the Flutter engine,
  and platform channel errors.
- **Diagnose** reports an unhandled Dart exception once, tells a missing plugin from a platform
  channel error, and names the method and channel when a native handler threw — a checked
  exception in a Kotlin handler makes Dart see `MissingPluginException` although the plugin is
  registered.
- **Element tools** find Flutter widgets by `Semantics(identifier:)`, which Flutter publishes as
  the Android resource id: pass it as `testTag`. Around a button it makes two nodes, an unlabelled
  parent with the id and the clickable child; a tap by id lands on the child. A screen that is one
  drawn surface (semantics off) says so instead of returning an empty tree.

## The Flutter session

Select the device and the app in Spock and run the app with `flutter run` (or `flutter attach`).
Nothing is pasted. Spock connects by itself when you select the app, when its process starts, and
when you press **Diagnose** or an agent asks for a report:

1. **Find it.** On Flutter 3.47.5 the Dart Tooling Daemon that `flutter run` starts lists the app;
   Spock reads the daemons' registry files directly (no Dart plugin needed) and keeps those whose
   workspace is the project open in the IDE. On Flutter 3.22, which has no such listing, Spock
   reads the VM's `Dart VM service is listening on …` line from the app's own pid in logcat.
2. **Prove it is the app.** Two flavors of one project share a package name, and two emulators of
   one image share a model name, so a name is not enough. Spock asks the VM for its pid and start
   time and keeps it only if that pid is the selected app's process on the selected device and the
   VM started just after that process. `verifiedBy` says how: `dtd+pid+start`, `dtd+pid` (the pid
   alone, when the device's `/proc` refuses), or `logcat-pid`.
3. **Connect through DDS only.** Spock's first call asks whether it reached the Dart Development
   Service (DDS) that `flutter run` puts in front of the VM; an address on the device is
   redirected there. A VM with no DDS is left at once: a client holding it would make a later
   `flutter attach` fail. While a Flutter tool is attaching, Spock does not touch the VM at all,
   which is why on 3.22 the session comes about 10–15 seconds after the VM starts; on 3.47.5 it
   comes within seconds.

Spock follows a hot restart onto the new isolate, and reports the end of a session — the UI
isolate exiting (on Android, Back at the root activity destroys the engine), `flutter run`
quitting, another app selected — in the Timeline.

`flutter_app_status` (MCP, read-only) says whether there is a session and why not: how the attach
went, how the app was verified, DDS or not, the build mode, the UI isolate, the Dart version, HTTP
recording and the device clock. The Flutter version reads "unknown": no framework extension
reports it. See [docs/MCP.md](MCP.md#flutter_app_status).

## Diagnose's `flutter` section

**Spock Screen › Diagnose**, `android_get_debug_context` and `android_diagnose_current_screen`
carry a `flutter` section for a Flutter app — and none for any other app. The full schema is in
[docs/MCP.md](MCP.md#android_get_debug_context). In short:

- **`attach`** — how the attach went, in words: connected, not running, a release build, still
  starting, frozen in the background, running without a debugger session, several apps that
  cannot be told apart, nothing found, or a failure. Never "no errors".
- **`errors`** — Flutter's framework errors since Spock connected (layout overflows, exceptions in
  `build()` and tap handlers), grouped, with a count; those DDS replayed from before Spock
  connected are counted apart. `errors.source` names where they were read:
  - *Flutter.Error events from the VM Service* — structured errors are on (the debug default).
  - *Structured errors are off* — Flutter printed them to logcat (the first in full, then
    `Another exception was thrown: …`), and Spock read them from the app's pid there.
  - *A profile build has no inspector* — logcat as well; a profile build prints repeats without
    their message, so those are one group of their own.
  - *Logcat: structured errors are on, yet no Flutter.Error arrived* — the fallback read.
  - *Both* — some arrived as events, others in logcat; each group's `source` says which.
- **Cross-layer problems** — a Dart error and a native failure (a crash, a
  `MissingPluginException`, a failed channel handler, DartMessenger's "Uncaught exception in binary
  message listener") moments apart are **one** `flutterCrossLayer` problem naming both layers,
  with each layer's own problem in `parts`.
- **`nearbyLogs`** — the log problems within two seconds of one of an error's occurrences, on the
  device's measured clock, listed right after the error in `likelyProblems`. An error seen at the
  start and the end of ten minutes is not paired with a warning in the middle.
- **`frames`** — build and raster times. A verdict only in a profile build; a debug build says the
  times are not representative.
- **`http`** — failed `dart:io` requests (4xx, 5xx, no response), while HTTP recording is on.
- **`notes`** — what the section could not see. An app that replaced `FlutterError.onError`
  (Crashlytics, Sentry) sends framework errors to neither the VM Service nor logcat; the note says
  so rather than reporting silence as health.

## Timeline, HTTP recording and rebuilds

**Timeline.** While a session is live and **Record device events** is on, the Timeline records the
app's framework errors (`flutter_error`), the route each `Navigator` call names (`navigation`),
bursts of slow frames in profile builds (`flutter_frame`; none in debug, where every frame is
slow), failed requests (`http`), and the session's start and end. They are placed on the host's
clock with the device clock Spock measures per session — logcat prints device local time and the
VM prints UTC, so the zone is applied too. Agents read them with `android_get_debug_timeline`.

**HTTP recording.** Dart records HTTP requests only when asked. Spock switches that on for a
session it opened **over DDS** in a **debug or profile** build, and switches it back off when it
disconnects. Recording someone else switched on is left alone; if `flutter run` dies before Spock
can switch it off, the next session on the same process takes it over. **Settings → Tools → Spock
ADB → Record Flutter HTTP traffic automatically** turns this off. Only `dart:io` traffic is visible
(`package:http`, dio); `cupertino_http`, `cronet_http` and native SDKs are not, and requests made
before Spock connected are not recorded.

**Rebuild window.** `android_get_recomposition_counts` and the UI Inspector's **Recompositions**
tab record Flutter widget rebuilds for a debug build with a session over DDS: each widget location
with its count and `lib/…:line:column`, most built first. The flag behind it,
`trackRebuildDirtyWidgets`, is shared with the IDE's rebuild counts and DevTools, so Spock switches
it on only if it was off, and off afterwards only if it switched it on, on the same isolate, and
nothing else wrote it meanwhile — the result says which. If `flutter run` dies during a recording
while the app runs on, Spock cannot switch it off then; the next session on the same process
(`flutter attach`) does, if the flag is still on and no other write of it was announced since,
and the Timeline and `flutter_app_status` say so. Double-clicking a row of the Recompositions tab
opens its `lib/…` file at the line, also when the IDE has only the app's `android/` folder open:
Spock looks for the app's `pubspec.yaml` (with a `flutter:` section) in the project's folder,
its modules' folders, and the folder above one named `android` — nowhere else. The whole-tree rebuild that switching it on
forces is not counted. Diagnose never records rebuilds.

## Home: Flutter route and App back stack

While Spock has a session on the selected app, Home's **This screen** shows a **Flutter route**
row, and **App back stack** lists the app's **Flutter routes** in their own section beside its
**Activities**. Spock reads them from the app's own `Navigator` with VM Service object reads — no
code is run in the app — and never attaches a session for them.

- **Keyed** (Home's row): the current route of the app's root navigator, found through its
  `GlobalKey`. A nested navigator without a key cannot be seen this way; the tooltip says so.
- **Exact** (App back stack): clicking it also counts every mounted navigator in the heap. Exactly
  one, the keyed one, with the same stack before and after the count → the stack is exact, bottom
  to top, root and current marked.
- **Unavailable**, with the reason, otherwise: several keyed navigators, an unkeyed one beside the
  keyed one, a transition in progress, or a stack that changed during the check. Spock does not
  guess which navigator is showing.

A route with no name shows its class (`DialogRoute (unnamed)`), never an invented path. The heap
check pauses the app briefly — **174–699 ms** measured on an Android 14 emulator — and the popup
reports how long it took. Without a session, App back stack is the plain activity list and nothing
is read from the VM.

## Limits

- **Build modes.** Debug: everything. Profile: the session, logcat errors, frames, HTTP, Timeline;
  no inspector, so no structured errors and no rebuild window. Release: no VM Service, so no
  session — Home, Storage, Logcat, the UI Tree and the device tools still work.
- **Structured errors.** On by default in debug. Off (`--dart-define=flutter.inspector.structuredErrors=false`,
  a no-debug launch, an IDE toggle), errors are read from logcat instead. On Flutter 3.22, toggling
  the inspector's switch at runtime did not move where errors went.
- **Apps started without `flutter run`.** No session, by design: a client on a VM with no DDS
  blocks a later `flutter attach` (spike S10). `attach` says the app runs without a debugger
  session; on 3.47.5 a later `flutter attach` is found by the next Diagnose.
- **Android 14 freezer.** An app frozen in the background answers nothing; Spock reports it as
  frozen and connects once it comes back to the foreground.
- **Validated** on an Android 14 (API 34) emulator with Flutter **3.22.2** and **3.47.5**, using
  [the sample app](../sample/flutter_app/README.md). Not yet checked: a physical phone, an app run
  from the Flutter plugin inside the IDE, and the Dart Tooling Daemon's registry on Linux and
  Windows (read from best-guess locations; when none is found, Spock uses the logcat route).
- **One UI isolate.** Add-to-app and multi-view apps are detected, not supported.
- **iOS** is not supported yet (planned: simulator, MCP tools only).

## Security: VM Service addresses

A VM Service address carries a token, and whoever holds it can run code in the app. Spock never
stores or logs one: addresses are redacted from every report, Timeline row, audit entry, log line
and MCP answer (they read `<VM Service address>`), including the percent-encoded form inside
DevTools links. Spock only connects to loopback addresses, and closes the `adb forward` it made
when the session ends.

## Spock vs the Flutter IntelliJ plugin vs Dart MCP

Each tool sees one half. Use them together; Spock does not rebuild the widget half.

| Question | Flutter plugin / DevTools | Dart MCP server | Spock ADB |
|---|---|---|---|
| Widget tree, selected widget, layout | ✅ Inspector | ✅ `widget_inspector` | — |
| Hot reload / restart | ✅ | ✅ `hot_reload`, `hot_restart` | — (follows a restart) |
| Code analysis, pub, tests | ✅ | ✅ `analyze_files`, `pub` | — |
| Tap / type by widget key | — | ✅ `flutter_driver_command` | by `Semantics(identifier:)` |
| Framework errors | ✅ console | ✅ `get_runtime_errors` | ✅ beside logcat and native crashes |
| Logcat, native crashes, ANRs | — | — | ✅ |
| Native dialogs above Flutter (permissions, share sheets, pickers) | — | — | ✅ accessibility tree, element tools |
| Permissions, push, deep links, process death, network, proxy | — | — | ✅ |
| App storage (prefs, Hive, SQLite) | — | — | ✅ |
| Activities and Flutter routes together | — | — | ✅ App back stack |
| One clock across Dart and Android | — | — | ✅ Diagnose, Timeline |

## Running Spock MCP and Dart MCP together

Both servers can be configured side by side; both talk to the app through DDS, which takes
several clients. For Claude Code, the project's `.mcp.json`:

```json
{
  "mcpServers": {
    "spock-adb": {
      "command": "<the IDE's java>",
      "args": [
        "-cp", "<the Spock ADB plugin jar>",
        "spock.adb.mcp.stdio.SpockAdbStdioLauncher",
        "<IDE config>/spock-adb/mcp-stdio.properties"
      ]
    },
    "dart": {
      "command": "dart",
      "args": ["mcp-server"]
    }
  }
}
```

- **Spock's entry** is the stdio entry from [docs/MCP.md](MCP.md#stdio); let the IDE write it
  (**Copy Config → Install into this project**). It holds no token.
- **Dart's entry**: `dart mcp-server` ships with the Dart SDK. Checked here with the Dart 3.13.4 of
  Flutter 3.47.5; its tool names change between SDKs, so check `dart mcp-server --help` and the
  [Dart MCP server docs](https://pub.dev/packages/dart_mcp_server) for yours, and for how it
  connects to a running app.
- If you give Dart MCP a VM Service address yourself, give it the one `flutter run` prints (DDS):
  any client on a VM with no DDS blocks `flutter attach`, whichever tool it is.

Which to call:

| The question | Call |
|---|---|
| Is Spock connected to the app, and if not, why? | `flutter_app_status` |
| What is wrong on this screen? | `android_diagnose_current_screen` — its `flutter` section and `likelyProblems` |
| Did a native crash or a channel failure cause the Dart error? | the same; look for `flutterCrossLayer` |
| What happened just before the bug? | `android_get_debug_timeline` |
| Which widget is this, what are its properties? | Dart MCP `widget_inspector` |
| Apply a code change | Dart MCP `hot_reload` / `hot_restart` |
| A permission dialog or share sheet is in front | Spock: `android_find_ui_element`, `android_tap_element` |
| Grant or revoke a permission, send a push, open a deep link | Spock: `android_grant_permission`, `android_send_push_message`, `android_open_deep_link` |
| What is in the app's prefs or database? | Spock: `android_list_app_storage`, `android_read_app_storage` |
| Which widgets rebuild too often? | Spock: `android_get_recomposition_counts` (debug build) |
