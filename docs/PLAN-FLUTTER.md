# Spock ADB for Flutter apps

Status: draft v2 · 2026-10-01 · lands in the **plugin** (`master`), before the standalone app.
Replaces the v1 draft in the `standalone` branch. Review findings are tagged **[FR#]**.

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
| Where | Plugin, `master`, normal PRs | Ships to existing users now. Standalone later reuses it. VS Code Flutter users wait. |
| Finding the running app | **DTD from the Dart plugin** (`DartToolingDaemonService`), optional `<depends optional="true">Dart</depends>` | DTD lists every running app's **DDS** URI, so Spock never fights `flutter run` for the VM Service [FR1, FR8]. Fallback: pasted URI. |
| Direct device URI (logcat / `log show`) | Read-only last resort, with a warning and a **Disconnect** button | Connecting first blocks DDS and breaks `flutter attach` [FR1]. |
| VM Service transport | JDK `java.net.http.WebSocket`, Gson | No new runtime dependency [FR17]. |
| Flutter widgets for agents on Android | Existing `android_*` UI tools + doc: use `Semantics(identifier:)` (maps to resource-id) | Free, zero app changes [FR5]. |
| Build modes | debug = all; profile = frames, logs, timeline, app control; release = app control only | [FR4] |
| iOS in v1 | Simulator only, macOS only, **MCP tools only** (no tool-window UI yet) | The viewer is built around `IDevice`; UI comes after demand [FR16]. |
| Tokens | Never stored; redacted in history, audit, timeline, logs; loopback `ws://` only | A VM Service token allows code execution [FR9]. |

## Phases

Order ships value early: P1 needs no VM Service and can release on its own.

### P0 — Flutter sample fixture (P0) · ~3 days [FR20]
- [ ] `sample/flutter_app`, **outside** the root Gradle build. Screens: login with `Semantics(identifier:)`, list → detail routes, layout overflow, deliberate exception, failing HTTP, `MissingPluginException` trigger, native permission request, push handler, SharedPreferences + Hive + sqflite writes, deep link.
- [ ] Runs on Android emulator and iOS simulator via `flutter run`.
- **Gate:** both platforms launch; checklist in `sample/flutter_app/README.md`.

### P1 — Flutter-aware Android, no VM Service (P0) · ~4 days → can release as 4.1
- [ ] Detect a Flutter app (`libflutter.so` in the APK / `io.flutter` in `dumpsys package`); show "Flutter · debug/profile/release" on the Home app card.
- [ ] App Storage: show SharedPreferences `flutter.` keys with decoded types; recognise Hive (`*.hive`) and sqflite (`*.db`) files.
  Seen in P0 (`FlutterSharedPreferences.xml`, shared_preferences 2.3): a double is stored as `<string>` with the Base64 prefix of "This is the prefix for Double." then `0.75`; a `List<String>` is the Base64 prefix of "This is the prefix for a list." followed by a Java-serialized `ArrayList`. Edits must write the same encoding back.
- [ ] Logcat: a "Flutter" preset (`flutter` tag + app pid).
- [ ] `LogProblemExtractor` / `LogcatTimelineClassifier`: `MissingPluginException`, `PlatformException`, Dart unhandled exception blocks (`[ERROR:flutter/runtime/dart_vm_initializer.cc…] Unhandled Exception:`), engine crashes (`libflutter.so` in tombstones).
  Seen in P0 (Flutter 3.22, debug, Android): **framework errors never reach logcat** — overflow, `build()` and tap-handler errors go only to the VM Service as `Flutter.Error`, because structured errors are on by default on mobile. Logcat sees only unhandled async errors. So P1 cannot report layout/build errors; Diagnose must say "Flutter framework errors need a VM Service connection (P4)" rather than "no errors".
- [ ] Element tools: `Semantics(identifier:)` around a button makes **two** nodes — a parent with the resource-id but no label and `clickable=false`, and the button child with the label and `clickable=true`, same bounds (seen in P0). Treat an id node whose only child is a same-bounds clickable node as one element, so find-by-id reports the label and tapping isn't refused as "not clickable".
- [ ] UI tree: when the screen is one `FlutterView` with no children, show a hint ("enable semantics: open the app with TalkBack once, or call `SemanticsBinding.ensureSemantics()` in debug") and note `Semantics(identifier:)`.
- **Gate:** each P0 fixture screen shows the expected storage / log / UI-tree result; unit tests on recorded logcat and prefs fixtures.

### P2 — Spike (P0) · ~3 days
- [ ] Read DTD URI from the Dart plugin; list apps through DTD `ConnectedApp`; connect to the DDS URI while `flutter run` is attached.
- [ ] Test both orders: Spock first then `flutter attach`; `flutter run` first then Spock [FR1].
- [ ] Confirm events on current Flutter stable: `Flutter.Error` (needs `structuredErrors`), `Flutter.Frame`, `Flutter.RebuiltWidgets`, `Flutter.Navigation`.
- [ ] Confirm `simctl` behaviour: `push`, `privacy`, `get_app_container`, `io screenshot`, `ui appearance`, `location`.
- **Gate:** findings in `docs/FLUTTER-SPIKE.md`; this plan updated where an assumption failed.

### P3 — Target-neutral tool context (P0) · ~3 days [FR2, FR15]
- [ ] `sealed interface Target { AndroidTarget(ConnectedDevice), IosSimTarget(udid, name) }`; `ToolContext.requireTarget()`; `confirmDestructive(tool, summary, targetLabel)`. `requireDevice()` stays as a thin Android helper so existing tools don't change.
- [ ] `DiagnosticProbe` → interface; current class becomes `AndroidProbe`. `SectionReport` / `LikelyProblem` unchanged.
- [ ] `ToolSafetyTest`: namespace rule becomes `android_|ios_|flutter_`.
- **Gate:** `./gradlew test detekt verifyPlugin` green on all supported IDEs; no behaviour change.

### P4 — VM Service client + Flutter session (P0) · ~4 days
- [ ] `VmServiceClient`: one reader, dispatch by request id, fragment accumulation, events fanned out on a dedicated executor, per-method timeouts [FR17].
- [ ] `FlutterSession` per project: discovery DTD → pasted URI → direct URI read-only [FR1, FR8]; filter by the selected app's package (flavors) [FR22].
- [ ] Isolate selection: the one exposing `ext.flutter.*`; ask when several; re-select on `IsolateStart`/`ServiceExtensionAdded` after hot restart; return "app is paused in the debugger" instead of hanging [FR12].
- [ ] Side effects: `structuredErrors` is already **on** by default in mobile debug builds (P0), so only subscribe to the `Extension` stream for `Flutter.Error` — never toggle it; enable `httpEnableTimelineLogging` on connect; disable what Spock enabled on disconnect [FR10].
- [ ] Token redaction everywhere [FR9].
- [ ] Tests: hand-written fake WebSocket server (test-only), recorded VM Service JSON per supported Flutter stable [FR21].
- [ ] All calls on pooled threads; UI via `invokeLater` (plugin rules).
- **Gate:** connects to the P0 sample on Android and iOS sim through DTD with `flutter run` attached; hot reload in the IDE still works while connected.

### P5 — Cross-layer Diagnose + Timeline (P0) · ~7 days
- [ ] Diagnose gains Flutter sections **inside the existing report and tool** (`android_diagnose_current_screen`, Diagnose panel) when a session is live — no parallel Flutter tool:
  - Flutter errors (overflow, exceptions) from `Flutter.Error` — the only place debug builds report them (P0).
  - Frames: a problem only in **profile** builds, budget from `_flutter.getDisplayRefreshRate`; info-only in debug ("not representative") [FR4].
  - Rebuild storm: a recording window like `GetRecompositionCountsTool`, accumulating `Flutter.RebuiltWidgets`, then switched off [FR10].
  - HTTP failures from `getHttpProfile` (paged by `updatedSince`); document that `cupertino_http` / `cronet_http` are invisible [FR10].
  - Correlation: a Dart error next to a native crash or a `MissingPluginException` within the same window is reported as one likely problem.
- [ ] Timeline: new categories `FLUTTER_ERROR`, `FLUTTER_FRAME` (jank only), `HTTP`; place by event timestamp + `DeviceClock` offset on Android, host time on the simulator; update the `android_get_debug_timeline` schema and `docs/MCP.md` [FR18].
- [ ] New MCP tool: `flutter_app_status` only (connected?, URI source, build mode, isolate, Flutter version). Everything else rides existing tools.
- **Gate:** every P0 fixture screen yields its expected top `LikelyProblem` on Android **and** iOS sim; timeline export round-trips.

### P6 — iOS simulator backend, MCP only (P0) · ~6 days [FR16]
- [ ] `SimctlBridge` (macOS only; hidden elsewhere), `simctl list -j` parsing, Xcode version in diagnostics.
- [ ] Tools and safety class:

| Tool | Safety |
|---|---|
| `ios_list_simulators`, `ios_screenshot`, `ios_read_app_storage` (read-only; plist via `plutil -convert json`) | READ |
| `ios_launch_app`, `ios_terminate_app`, `ios_open_deep_link`, `ios_send_push`, `ios_grant_permission` | SAFE_ACTION |
| `ios_set_appearance`, `ios_set_location` — each with a paired reset | SAFE_ACTION |
| `ios_revoke_permission` (kills the app), `ios_uninstall_app` | DESTRUCTIVE (default deny) |

- [ ] Document: notifications and camera can't be pre-granted by `simctl`; system alerts are UIKit and need a manual tap.
- [ ] Register `ios_*` tools only on macOS with Xcode present, to keep the tool list short [FR15].
- [ ] Update `ToolRegistry`, `ToolSafetyTest`, `McpSmokeTest`, `ReadmeToolCountTest`, `SkillToolNamesTest`, tool counts in `README.md` and `docs/MCP.md` (CLAUDE.md checklist step 8).
- **Gate:** every tool exercised against a booted simulator with the P0 sample; `FakeToolContext` gains an iOS target for the destructive-deny loop [FR21].

### P7 — Docs + agent setup (P1) · ~2 days
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
| `DartToolingDaemonService` API changes across Dart plugin versions | Reflection-free thin adapter in the optional config file; `verifyPlugin` on all IDEs |
| Framework errors are invisible without a VM Service connection | Diagnose says so explicitly instead of reporting "no errors" (P0 finding) |
| Inspector / event names change between Flutter versions | Probe `extensionRPCs`; fixtures per supported stable [FR11] |
| Tool count growth confuses agents | Only `flutter_app_status` + `ios_*` (macOS only) are new [FR15] |
| `ToolContext` change breaks AS 232 compatibility | P3 is behaviour-neutral; `verifyPlugin` gate |

## Sources

- Dart MCP server tools: https://pub.dev/packages/dart_mcp_server
- Flutter plugin for Android Studio / IntelliJ: https://docs.flutter.dev/tools/android-studio
- Flutter plugin source (DTD use): https://github.com/flutter/flutter-intellij
- DTD ConnectedApp service: https://github.com/dart-lang/sdk/issues/60540
- Official skills: https://github.com/flutter/agent-plugins
