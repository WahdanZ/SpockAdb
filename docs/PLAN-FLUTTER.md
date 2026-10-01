# Spock ADB for Flutter apps

Status: draft v2 · 2026-10-01 · lands in the **plugin** via the `epic/flutter` branch (epic #153), then `master`, before the standalone app.
Replaces the v1 draft in the `standalone` branch. Review findings are tagged **[FR#]** and summarised in the appendix at the end.

## Positioning — the device half, not the widget half

Flutter developers already have strong widget-level tools. Spock must not rebuild them.

| Already covered — **Spock does not build** | By |
|---|---|
| Widget inspector, property editor, debug paint, slow animations, perf overlay, rebuild counts | Flutter plugin for IntelliJ / Android Studio (embedded DevTools) |
| Agent tools: widget tree, selected widget, runtime errors, hot reload/restart, app logs, launch/stop | Official Dart MCP server (`dart mcp-server`) |
| Agent tap / type / scroll / wait by Key | Dart MCP `flutter_driver_command`; community `mcp_flutter` |
| Agent know-how: fix layout issues, fix runtime errors, widget/integration tests | Skills in `flutter/agent-plugins`, `dart-lang/skills` |

| Gap — **Spock builds** | Why nobody else covers it |
|---|---|
| Native screens on top of Flutter (permission dialogs, share sheets, pickers, WebViews) | flutter_driver sees only Flutter widgets; Spock's UI tree reads accessibility, which includes them |
| Device + OS control for Flutter apps on **Android and iOS simulator** (push, permissions, clear data, process death, network, proxy, deep links) | Dart MCP stops at the Flutter engine |
| Flutter-aware App Storage (SharedPreferences `flutter.` keys, iOS plist, Hive/sqflite files) | DevTools has no storage view |
| Cross-layer Diagnose + Timeline: Flutter errors + native crashes + plugin channel failures + device state on one clock | Each tool sees one layer |
| One MCP server for native **and** Flutter apps | Agents can run Spock **next to** Dart MCP; docs say how |

Decision: **no `spock_flutter` companion package in v1.** Its main job (tap/type by Key) duplicates
`flutter_driver`. Revisit only if a real gap appears.

## Decisions

| Question | Decision | Why / trade-off |
|---|---|---|
| Where | Plugin, normal PRs into `epic/flutter`, then `master` | Ships to existing users now. Standalone later reuses it. VS Code Flutter users wait. |
| Finding the running app | **DTD from the Dart plugin** (`DartToolingDaemonService`), optional `<depends optional="true">Dart</depends>` — only on a Flutter version with the DTD ConnectedApp service (to confirm in S12); Dart 3.4 / Flutter 3.22 has none | DTD lists every running app's **DDS** URI, so Spock never fights `flutter run` for the VM Service [FR1, FR8]. Older SDKs fall back, in order, to: the Flutter IntelliJ plugin's running-app state; the DDS URI named in the VM's refusal when Spock tries the direct URI (plausible, to confirm in S9/S10); a pasted URI. |
| Direct device URI (logcat / `log show`) | Read-only last resort, with a warning and a **Disconnect** button | While DDS owns the VM it is refused (S9). Connecting first blocks DDS and breaks `flutter attach` (S10) [FR1]. |
| VM Service transport | JDK `java.net.http.WebSocket`, Gson | No new runtime dependency [FR17]. |
| Flutter widgets for agents on Android | Existing `android_*` UI tools + doc: use `Semantics(identifier:)` (maps to resource-id) | Free: no plugin protocol; the app adds `Semantics(identifier:)` [FR5]. |
| Build modes | debug = all; profile = frames, logs, timeline, app control; release = app control only | [FR4] |
| iOS in v1 | Simulator only, macOS only, **MCP tools only** (no tool-window UI yet) | The viewer is built around `IDevice`; UI comes after demand [FR16]. |
| Tokens | Never stored; redacted in history, audit, timeline, logs; loopback `ws://` only | A VM Service token allows code execution [FR9]. |

## Phases

Order ships value early: P1 needs no VM Service and can release on its own.
Each phase is tagged **(must)** or **(should)** for priority.

### P0 — Flutter sample fixture (must) · ~3 days [FR20]
- [x] `sample/flutter_app`, **outside** the root Gradle build. Screens: login with `Semantics(identifier:)`, list → detail routes, layout overflow, deliberate exception, failing HTTP, `MissingPluginException` trigger, native permission request, push handler, SharedPreferences + Hive + sqflite writes, deep link.
- [x] Runs on Android emulator and iOS simulator via `flutter run`.
- **Gate:** both platforms launch; checklist in `sample/flutter_app/README.md`.

### P1 — Flutter-aware Android, no VM Service (must) · ~4 days → can release as 4.1
- [x] Detect a Flutter app (`libflutter.so` in the APK / `io.flutter` in `dumpsys package`); show "Flutter · debug/profile/release" on the Home app card.
- [x] App Storage: show SharedPreferences `flutter.` keys with decoded types; recognise Hive (`*.hive`) and sqflite (`*.db`) files.
  Seen in P0 (`FlutterSharedPreferences.xml`, shared_preferences 2.3): a double is stored as `<string>` with the Base64 prefix of "This is the prefix for Double." then `0.75`; a `List<String>` is the Base64 prefix of "This is the prefix for a list." followed by a Java-serialized `ArrayList`. Edits must write the same encoding back.
- [x] Logcat: a "Flutter" preset (`flutter` tag + app pid).
- [x] `LogProblemExtractor` / `LogcatTimelineClassifier`: `MissingPluginException`, `PlatformException`, Dart unhandled exception blocks (`[ERROR:flutter/runtime/dart_vm_initializer.cc…] Unhandled Exception:`), engine crashes (`libflutter.so` in tombstones).
  Seen in P0 (Flutter 3.22, debug, `flutter run` from the CLI, Android): **framework errors did not reach logcat** — overflow, `build()` and tap-handler errors went only to the VM Service as `Flutter.Error`, because structured errors were on and the app keeps Flutter's `FlutterError.onError`. Structured errors are a debug-only default (`!kIsWeb`) that a no-debug DAP launch (`=false`) or an IDE can switch off, which sends the errors to logcat; an app that replaces `FlutterError.onError` (Crashlytics) sends them to neither (spike S3, S7). Logcat sees only unhandled async errors. So P1 cannot report layout/build errors; Diagnose must say "Flutter framework errors need a VM Service connection (P4)" rather than "no errors".
- [x] Element tools: `Semantics(identifier:)` around a button makes **two** nodes — a parent with the resource-id but no label and `clickable=false`, and the button child with the label and `clickable=true`, 12 px inside it (seen in P0). Actions on an id node with no eligible ancestor land on its only eligible descendant inside its bounds, so tapping by id works instead of being refused.
- [x] UI tree: when the screen is one `FlutterView` with no children, show a hint ("enable semantics: open the app with TalkBack once, or call `SemanticsBinding.ensureSemantics()` in debug") and note `Semantics(identifier:)`.
- **Gate:** each P0 fixture screen shows the expected storage / log / UI-tree result; unit tests on recorded logcat and prefs fixtures.

### P2 — Spike (must) · ~3 days
- [ ] Read DTD URI from the Dart plugin; list apps through DTD `ConnectedApp`; connect to the DDS URI while `flutter run` is attached.
  Done: connecting to the DDS URI with `flutter run` attached (S1). Pending: DTD `ConnectedApp` — absent on Dart 3.4, needs a newer SDK (S12).
- [ ] Test both orders: Spock first then `flutter attach`; `flutter run` first then Spock [FR1].
  Done: `flutter run` first, then Spock through DDS (S1); both orders answered by source (S9, S10). Pending: empirical confirmation and the refusal text.
- [ ] Confirm events on current Flutter stable: `Flutter.Error` (needs `structuredErrors`), `Flutter.Frame`, `Flutter.RebuiltWidgets`, `Flutter.Navigation`.
  Done: `Flutter.Frame` live, and DDS's event replay (S6). Pending: `Flutter.Error`, `Flutter.RebuiltWidgets`, `Flutter.Navigation` payloads (S11).
- [ ] Confirm `simctl` behaviour: `push`, `privacy`, `get_app_container`, `io screenshot`, `ui appearance`, `location`.
  Pending: simulator runtime (S13).
- [ ] Profile mode, hot restart, quit/detach, several Flutter isolates, a launch from the Android Studio Flutter plugin, a paused isolate, `getHttpProfile` end to end, channel handler failures on a device.
  Pending (S14–S21).
- **Gate:** findings in `docs/FLUTTER-SPIKE.md`; this plan updated where an assumption failed.

### P3 — Target-neutral tool context (must) · ~3 days [FR2, FR15]
- [x] `sealed interface DeviceTarget { Android(ConnectedDevice), IosSimulator(udid, name, runtime) }` (not `Target`, which clashes with Kotlin's annotation); `ToolContext.requireTarget()`; `confirmDestructive(tool, summary, target)`. `requireDevice()` stays as a thin Android helper so existing tools don't change.
- [x] `DiagnosticProbe` → interface; current class becomes `AndroidProbe`. `SectionReport` / `LikelyProblem` unchanged.
- [x] `ToolSafetyTest`: namespace rule becomes `android_|ios_|flutter_`.
- **Gate:** `./gradlew test detekt verifyPlugin` green on all supported IDEs; no behaviour change.

### P4 — VM Service client + Flutter session (must) · ~4 days

#### P4a — client, session, pasted and logcat discovery (#159)
Library and tests only: no UI, no MCP tool, no IDE discovery.
- [x] `VmServiceClient` (`spock.adb.flutter.vmservice`): JDK `WebSocket` + Gson, one reader, dispatch by request id, fragment accumulation, events fanned out on a dedicated executor, per-call timeouts (5 s default, longer on request), `close()` fails pending calls, connection loss reported to listeners [FR17].
- [x] `FlutterSession`: connect from a candidate; discovery from a pasted URI (`VmServiceUri` reads `flutter run`'s `http`/`ws` forms, `https`/`wss`, DevTools `?uri=` / `#/?uri=` links, a console or logcat line; loopback only) and from logcat (`LogcatDiscovery`: the app's `Dart VM service is listening on` / `Observatory listening on` line, `adb forward` to the device port, removed on close) — a **direct** URI, so read-only: the session makes no writes through it [FR1, FR8].
- [x] Isolate selection: the isolate behind a `_flutter.listViews` view, else the one with `ext.flutter.inspector.structuredErrors`, else any `ext.flutter.*`; several → `IsolateSelection.Ambiguous` and `selectIsolate(id)`; re-select on `IsolateStart`/`IsolateExit`/`ServiceExtensionAdded` after hot restart; a paused isolate is `IsolateSelection.Paused` ("paused in the debugger") instead of a hanging call [FR12].
- [x] Side effects: `structuredErrors` is read on connect and followed through `Flutter.ServiceExtensionStateChanged` — never set; `httpEnableTimelineLogging` read, enabled if off on each new UI isolate, and switched off on close only where Spock switched it on [FR10]. Bool results read both as JSON booleans (dart:io) and `"true"`/`"false"` strings (Flutter).
- [x] History: events DDS replays on subscribe are marked `history` (timestamp before the connection) and repeats are dropped by a bounded LRU.
- [x] Token redaction in the library [FR9]: `VmServiceUri` never prints its token; `Redaction` scrubs addresses from text, error messages and every event (including stdout bytes and the `connectedVmServiceUri` / `activeDevToolsServerAddress` state changes). P5 still has to apply it wherever it stores or shows events (timeline, audit).
- [x] Tests: hand-written RFC 6455 fake server (test-only), VM Service JSON fixtures in the Flutter 3.22.2 shapes from the spike, recorded logcat lines [FR21].
- [x] Blocking work on the caller's thread (callers use pooled threads); events on the client's own thread; nothing on the EDT.
- [ ] Fixtures recorded from each further supported Flutter stable (only 3.22 shapes so far).
- **Gate:** `./gradlew test detekt` green; a device check by the lead with `FlutterSessionLiveCheck` (`SPOCK_VM_SERVICE_URI=<flutter run address> ./gradlew test --tests spock.adb.flutter.FlutterSessionLiveCheck --rerun -i`) against the P0 sample with `flutter run` attached, hot reload still working. **Passed 2026-10-01** on the Android 14 emulator with `flutter run` (3.22.2) attached: connected through DDS, history marked, hot restart followed onto the new isolate (and still worked in `flutter run`), `Flutter.Error`/`Flutter.Navigation` live, HTTP logging restored, no token in any event.

#### P4b — IDE discovery
- [x] DTD discovery (`spock.adb.flutter.dtd`; Flutter 3.47.5 confirmed in S12; first version not pinned): `DtdRegistry` reads the registry files (`~/Library/Application Support/Dart/dtd/<pid>`) leniently, keeps live pids (dead DTDs leave their file behind) and refuses non-loopback addresses; `Workspace` matches `workspaceRoot` to the project (equal, inside it or holding it; symlinks resolved, so `/tmp` is `/private/tmp`); `DtdClient` calls `ConnectedApp.getVmServices` on the P4a JSON-RPC core; `DtdDiscovery` keeps apps on the selected device's model (underscores and spaces alike) and of the project's pubspec `name`, ranked exact workspace first, as `VmServiceSource.DTD` DDS candidates. The DTD secret is redacted by value in every `toString` and exception. Covers `flutter run` from a terminal; apps run from the IDE appear only if the Flutter plugin registers them with the IDE's DTD (S22) [FR1, FR8].
- [ ] Linux and Windows registry directories (S23): best guesses from `applicationConfigHome('Dart')` — Linux `$XDG_CONFIG_HOME/{Dart,dart}/dtd`, `~/.config/{Dart,dart}/dtd`, `~/.dart-tool/dtd`; Windows `%APPDATA%\Dart\dtd` — read when present, **unverified**.
- [ ] Older SDKs (no `ConnectedApp`, e.g. 3.22): the Flutter IntelliJ plugin's running-app state, else logcat: while `flutter run` is attached the VM hands a direct-URI client to DDS (S9, confirmed), so the logcat URI is safe then; connecting before DDS starts (S10) is still open, so a direct connection stays read-only with a warning.
- [x] `FlutterSessionService` (project service): at most one `FlutterSession`, closed on a pooled thread when the project closes; `discover(device, applicationId, pasted)` tries DTD → pasted URI → logcat, logcat only when nothing else found the app; DTD filters by the pubspec package, logcat by the selected applicationId (flavors) [FR22]. Blocking by contract — P5's Diagnose and MCP call it from pooled threads.
- [ ] The Flutter plugin's running-app state as a source between DTD and the pasted URI.
- [ ] UI via `invokeLater` once there is UI (plugin rules).
- **Gate:** connects to the P0 sample through DTD (newer SDK) and through the fallbacks (3.22) with `flutter run` attached; an IDE-run app (S22); hot reload still works while connected. DTD half, on a machine with `flutter run` attached: `SPOCK_DTD_PROJECT=/path/to/flutter/project SPOCK_DTD_CONNECT=1 ./gradlew test --tests spock.adb.flutter.dtd.DtdLiveCheck --rerun -i` (optionally `SPOCK_DTD_DEVICE_MODEL=<ro.product.model>`) prints the live daemons and candidates, redacted, then connects a session to the first.

### P5 — Cross-layer Diagnose + Timeline (must) · ~7 days
- [ ] Diagnose gains Flutter sections **inside the existing report and tool** (`android_diagnose_current_screen`, Diagnose panel) when a session is live — no parallel Flutter tool:
  - Flutter errors (overflow, exceptions): from `Flutter.Error` while structured errors are on (P4 tracks the state); from logcat when off (the first error in full, then `Another exception was thrown: …`). An app that replaced `FlutterError.onError` reports to neither, so Diagnose never says "no errors" from the absence of events (spike S3, S7). Errors from before Spock connected are not recovered: `Flutter.Frame` volume evicts them from DDS's replay ring (spike S6).
  - Frames: a problem only in **profile** builds, budget from `_flutter.getDisplayRefreshRate`; info-only in debug ("not representative") [FR4]. `Flutter.Frame` arrives in engine batches and only for rendered frames; place each by its `startTime` (engine-monotonic µs) on a monotonic offset, not by the event timestamp (spike S6).
  - Rebuild storm: a recording window like `GetRecompositionCountsTool`, accumulating `Flutter.RebuiltWidgets`, then switched off — unless it was already on, since the flag is shared with the IDE's rebuild counts (debug only) [FR10].
  - HTTP failures from `getHttpProfile` (paged by `updatedSince`); document that `cupertino_http` / `cronet_http` are invisible [FR10].
  - Correlation: a Dart error next to a native crash or a `MissingPluginException` within the same window is reported as one likely problem.
- [ ] Classifier: a `MissingPluginException` preceded by DartMessenger's `Uncaught exception in binary message listener` (same pid, same window) is a channel handler failure — a checked exception thrown in the handler — not a missing plugin (spike S8; sample `error_channel_checked`).
- [ ] Timeline: new categories `FLUTTER_ERROR`, `FLUTTER_FRAME` (jank only), `HTTP`; place by event timestamp + `DeviceClock` offset on Android, host time on the simulator, and frames by `startTime` on a monotonic offset; update the `android_get_debug_timeline` schema and `docs/MCP.md` [FR18].
- [ ] New MCP tool: `flutter_app_status` only (connected?, URI source, build mode, isolate, Flutter version). Everything else rides existing tools.
- **Gate:** every P0 fixture screen yields its expected top `LikelyProblem` on Android; timeline export round-trips. The iOS simulator half of this gate moves to P6, which builds the iOS target.

### P6 — iOS simulator backend, MCP only (must) · ~6 days [FR16]
- [ ] `SimctlBridge` (macOS only; hidden elsewhere), `simctl list -j` parsing, Xcode version in diagnostics.
- [ ] Tools and safety class:

| Tool | Safety |
|---|---|
| `ios_list_simulators`, `ios_screenshot`, `ios_read_app_storage` (read-only; plist via `plutil -convert json`) | READ |
| `ios_launch_app`, `ios_terminate_app`, `ios_open_deep_link`, `ios_send_push`, `ios_grant_permission` | SAFE_ACTION |
| `ios_set_appearance`, `ios_set_location` — each with a paired reset | SAFE_ACTION |
| `ios_revoke_permission` (kills the app), `ios_uninstall_app` | DESTRUCTIVE (default deny) |

- [ ] Diagnose on an iOS simulator target: the P5 Flutter sections (errors, frames, rebuilds, HTTP) run for an `IosSimTarget` with a live session, with no Android-only sections.
- [ ] DiagnosticCollector's `more` scoping (`APP_SCOPED_DETAILS`, `AppSection.id` → `filter`) is Android-specific: move it onto `DetailRef` before iOS sections reuse ids `app`/`logs` [review P3-4].
- [ ] Document: notifications and camera can't be pre-granted by `simctl`; system alerts are UIKit and need a manual tap.
- [ ] Register `ios_*` tools only on macOS with Xcode present, to keep the tool list short [FR15].
- [ ] Update `ToolRegistry`, `ToolSafetyTest`, `McpSmokeTest`, `ReadmeToolCountTest`, `SkillToolNamesTest`, tool counts in `README.md` and `docs/MCP.md` (CLAUDE.md checklist step 8).
- **Gate:** every tool exercised against a booted simulator with the P0 sample; Diagnose on an iOS simulator target yields each P0 fixture screen's expected top `LikelyProblem` (moved from P5); `FakeToolContext` gains an iOS target for the destructive-deny loop [FR21].

### P7 — Docs + agent setup (should) · ~2 days
- [ ] `docs/FLUTTER.md`: what Spock adds vs Flutter plugin vs Dart MCP; how to run **Spock MCP + Dart MCP together** (who does widgets, who does device/OS).
- [ ] `skills/spock-adb`: a Flutter section — when to call Spock tools vs Dart MCP tools; native dialog handling.
- [ ] CHANGELOG under `[Unreleased]`.

## Total

| Phase | Days |
|---|---|
| P0 sample | 3 |
| P1 Flutter-aware Android (release 4.1) | 4 |
| P2 spike | 3 |
| P3 target-neutral context | 3 |
| P4 VM Service + session | 4 |
| P5 cross-layer Diagnose + Timeline | 7 |
| P6 iOS simulator (MCP) | 6 |
| P7 docs | 2 |
| **Total** | **32 days ≈ 6.5 weeks** |

First release (P0 + P1) after **~1.5 weeks**.

## Out of scope v1

- Widget inspector, tap-by-Key, hot reload/restart, debug toggles (Flutter plugin / Dart MCP own these).
- `spock_flutter` companion package.
- iOS tool-window UI, physical iOS devices, iOS storage writes, `recordVideo`, `status_bar`.
- Add-to-app and multi-view apps: detected (several UI isolates/views) and reported, not supported [FR22].
- Cross-platform recipes (needs a platform-neutral tool layer) [FR20].
- Any `flutter_evaluate` tool [FR15].

## Open risks

| Risk | Mitigation |
|---|---|
| Dart plugin not installed (pure Android Studio) | Optional dependency; P1 still works; pasted URI fallback |
| SDK older than the DTD ConnectedApp service (Flutter 3.22 / Dart 3.4) | Fallbacks: Flutter plugin's running-app state, the DDS URI from the VM's refusal (S9/S10), pasted URI |
| `DartToolingDaemonService` API changes across Dart plugin versions | Reflection-free thin adapter in the optional config file; `verifyPlugin` on all IDEs |
| Framework errors are invisible without a VM Service connection, or anywhere when the app replaced `FlutterError.onError` | Diagnose says so explicitly instead of reporting "no errors" (P0 finding, spike S3/S7) |
| DDS's replayed history carries old events and the VM Service token | Dedupe, mark history, redact payloads (P4, spike S6) |
| Inspector / event names change between Flutter versions | Probe `extensionRPCs`; fixtures per supported stable [FR11] |
| Tool count growth confuses agents | Only `flutter_app_status` + `ios_*` (macOS only) are new [FR15] |
| `ToolContext` change breaks AS 232 compatibility | P3 is behaviour-neutral; `verifyPlugin` gate |

## Sources

- Dart MCP server tools: https://pub.dev/packages/dart_mcp_server
- Flutter plugin for Android Studio / IntelliJ: https://docs.flutter.dev/tools/android-studio
- Flutter plugin source (DTD use): https://github.com/flutter/flutter-intellij
- DTD ConnectedApp service: https://github.com/dart-lang/sdk/issues/60540
- Spike findings: [FLUTTER-SPIKE.md](FLUTTER-SPIKE.md)
- Official skills: https://github.com/flutter/agent-plugins

## Appendix: review findings referenced

The v1 review lives outside the repo; these are the findings this plan cites.

| Tag | Finding |
|---|---|
| FR1 | DDS owns the VM Service connection; Spock must coexist with `flutter run` / `flutter attach`, not grab the URI first |
| FR2 | MCP tool context and the diagnostics probe are Android-bound (`IDevice`) |
| FR4 | Jank is only meaningful in profile builds; the frame budget comes from the display refresh rate |
| FR5 | Flutter semantics already reach Android accessibility; `Semantics(identifier:)` maps to the resource-id |
| FR8 | VM Service discovery from logs or ports is fragile |
| FR9 | A VM Service token means code execution: never store or log it |
| FR10 | `structuredErrors`, `trackRebuildDirtyWidgets` and the HTTP profile have side effects on the app |
| FR11 | Inspector API names and object groups change between Flutter versions |
| FR12 | Isolate selection, and paused isolates that would hang calls |
| FR15 | MCP safety classes and tool-count growth |
| FR16 | `simctl` limits (what it can't grant, read or automate) |
| FR17 | WebSocket client threading, message fragments and timeouts |
| FR18 | Timeline clocks (device vs host) and the timeline schema |
| FR20 | Phase ordering |
| FR21 | Test strategy gaps |
| FR22 | Flavors, add-to-app and multi-view apps |
