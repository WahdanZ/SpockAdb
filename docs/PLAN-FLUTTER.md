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

## Status — implemented vs validated (2026-10-10)

"Implemented" means merged into `epic/flutter` with green gates. "Validated" means checked on a real device
or SDK, not only in unit tests — so far an Android 14 (API 34) emulator; no physical phone yet. The Android flow
comes first: **selected app → automatic discovery → Flutter session → Diagnose/Timeline correlation**. iOS (P6)
waits for it. User docs: [FLUTTER.md](FLUTTER.md).

| Phase | Implemented | Validated on a device | Still pending |
|---|---|---|---|
| P0 sample | ✅ #154; builds on 3.47.5 too (#166) | ✅ Android 14 emulator, Flutter 3.22.2 and 3.47.5 | iOS build (Xcode 26.3 storyboard needs the iOS 26.2 platform) |
| P1 Flutter-aware Android | ✅ #155, #158 | ✅ prefs round trip, logcat, UI tree, Hive/SQLite (13 MB DB) | — |
| P2 spike | ✅ #156 | ✅ S1–S10, S12, S24, S25 | S11 payload details, S13 simctl, S14–S21, S22 (IDE-run app), S23 (Linux/Windows DTD) — see FLUTTER-SPIKE.md |
| P3 target-neutral context | ✅ #157 | ✅ CI Plugin Verifier, all IDEs | — |
| P4a client + session | ✅ #159, H1 included | ✅ DDS connect, 302 → DDS from the direct address, history by arrival, hot restart followed, HTTP logging restored, H1 probe | fixtures from further Flutter stables |
| P4b DTD discovery | ✅ #161, H2 and D1–D11 included | ✅ DTD on 3.47.5 (`dtd+pid+start`, #166); logcat route on 3.22.2 (#163) | S22, S23 (Linux/Windows registry paths unverified); the Flutter plugin's running-app state as a source |
| P5a analyzers | ✅ #162, A1–A15 | ✅ through H and P5b on device: errors, HTTP failures (undrained 404 included), rebuild storm, cross-layer pairing | H3: profile jank thresholds on a real jank fixture, HTTP paging by `updatedSince`, 120 Hz |
| H Android hardening | ✅ #163 | ✅ #163 on 3.22.2: automatic attach, error beside its logcat line, HTTP rows, Back at the root, `flutter attach` after a lost session, no-DDS app left alone, two app IDs, two same-model emulators, the freezer. #166 on 3.47.5: DTD discovery, Settings switch off | rest of H3 (above) |
| P5b wiring | ✅ #168 | ✅ #168 on 3.22.2 and 3.47.5: `flutter_app_status`, logcat errors, rebuild window (idle, storm, already on, other writer, hot restart, profile refused), cross-layer merge and no merge across a long-lived error. Re-checked 2026-10-10: `errors.source` after structured errors were switched off (3.47.5), a profile build's message-less repeat (3.22.2) | UI-only checks, see Follow-ups |
| Home navigation state | ✅ #169 | ✅ on 3.22.2 and 3.47.5, every Navigation case in the sample README; heap check 174–699 ms | hot restart, selection switch during a read, transition retries, popup layout in AS 232 |
| P6 iOS | ⬜ deferred until the Android flow is done | ⬜ | all |
| P7 docs | ✅ #172 | — | — |

### Follow-ups

- **Follower re-attach after a lost session**: when `flutter run` lost the device, the follower did not attach
  to the restarted app until it was selected again (seen during #169). In progress in #171.
- **#168 device re-checks.** Done 2026-10-10: `errors.source` after switching structured errors off now reads
  "Flutter.Error events from the VM Service: these arrived while structured errors were on; they are off now …"
  (3.47.5); a 3.22.2 profile build lists "Exception caught by Flutter: Bad state: …" (count 1) and "Another
  framework error; a profile build prints repeats without their message" (count 2) with its note. Still open,
  all UI checks: the Recompositions tab's rows and double-click to source; selecting another app mid-recording;
  the `.second` copy through the selector.
- **`LEFT_ON` when a session dies mid-window**: a connection lost (not closed by Spock) during a rebuild window
  leaves `trackRebuildDirtyWidgets` on, and the result says so; the next session could adopt and restore it, as
  HTTP recording does.
- **`nearbyLogs` pairing by the group's span**: the cross-layer merge measures between single occurrences;
  pairing an error with its log problems still measures against the group's whole span.
- **Cross-layer pair lost with the native half**: once the native line is older than the 1,500 logcat lines
  Diagnose reads, the pair stops merging while the Dart side keeps its history. Correct for the data read;
  documented, not fixed.
- **Sample README**: the Navigation checks now say what #169 checked on both SDKs, and what is still unchecked.

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
- [x] History: events DDS replays on subscribe are marked `history` by arrival — on `Extension`/`Logging`/`Stdout`/`Stderr`, before Spock's `streamListen` for that stream was answered (DDS replays inside its handler) — not by comparing the device's timestamps with the host clock, which can be hours apart; `Isolate`/`Debug` events are never history and always drive the session. Replayed repeats are dropped by one bounded LRU per session, kept across reconnects.
- [x] Token redaction in the library [FR9]: `VmServiceUri` never prints its token; `Redaction` scrubs addresses from text, error messages and every event (including stdout bytes and the `connectedVmServiceUri` / `activeDevToolsServerAddress` state changes). P5 still has to apply it wherever it stores or shows events (timeline, audit).
- [x] Tests: hand-written RFC 6455 fake server (test-only), VM Service JSON fixtures in the Flutter 3.22.2 shapes from the spike, recorded logcat lines [FR21].
- [x] Blocking work on the caller's thread (callers use pooled threads); events on the client's own thread (bounded queue: logs shed first, never `Isolate`/`Debug`); re-selection and per-isolate set-up on the session's thread, debounced; the session lock is never held across a call or a listener, and `close()` fails stuck calls first; nothing on the EDT.
- [x] **H1 — connection kind by probe**: the first call on a new connection (after a 302 to DDS, before any stream, `getVM` or extension) is `getDartDevelopmentServiceVersion`. DDS answers → `ConnectionKind.DDS`, writable; `-32601` → `DIRECT_NO_DDS`, closed at once and `connect` fails with `NoDdsException` ("running without a debugger session — start it with `flutter run`/`flutter attach`"), unless `connect(candidate, allowDirect = true)`, which keeps it read-only; any other failure fails the connection. `readOnly` follows the kind, not the source; `ddsLikely` only orders candidates. `connectionKind` is on the session and its snapshot; `FlutterSessionLiveCheck` prints it (`SPOCK_VM_SERVICE_ALLOW_DIRECT=1` to opt in) [FR1, S10]. `VmServiceProbe.probe(uri)` asks the same on a connection that makes that one call and closes (a `302` followed without holding the VM) and returns `Dds(target)` / `DirectNoDds` / `Unreachable(reason)`, so H2 can treat `DirectNoDds` soon after start-up as "DDS not up yet" rather than final (the S10 start-up race).
- [x] HTTP recording policy: `httpEnableTimelineLogging` is switched on only on a DDS connection, in a debug or profile build (decided again as extensions register), and when `connect(…, recordHttp = true)` (the default; P5b wires a Settings switch); one already on is never touched. `FlutterSessionSnapshot.httpRecording` = `EnabledBySpock` / `AlreadyOn` / `Pending` (dart:io's extension not registered yet) / `Off(SETTING_OFF | NOT_DDS | RELEASE_OR_UNKNOWN_MODE | FAILED)`, null with no UI isolate; the flag is read every time, and an unknown answer is asked again at the isolate's next extension or resume [FR10].
- [x] Session API for P5: `callUiExtension` (UI isolate; refused when disconnected, paused or without a UI isolate — typed exceptions — and, on a read-only connection, anything but a known read: the HTTP profile, and the HTTP-logging and structured-errors flags with no arguments) and `callVm` (no `ext.*`, no `getStreamHistory`; known mutators refused on a read-only connection), both scrubbed [FR9]. The raw client is internal and `connect` returns nothing. The snapshot carries `connectedAtHostMs`, `vmPid`, `vmStartTimeMs`, `operatingSystem`; `addListener(listener, replayState = true)` gives a late listener the current state first, on the session's thread; a removed listener hears nothing more.
- [ ] Fixtures recorded from each further supported Flutter stable (only 3.22 shapes so far).
- **Gate:** `./gradlew test detekt` green; a device check by the lead with `FlutterSessionLiveCheck` (`SPOCK_VM_SERVICE_URI=<flutter run address> ./gradlew test --tests spock.adb.flutter.FlutterSessionLiveCheck --rerun -i`) against the P0 sample with `flutter run` attached, hot reload still working. **Passed 2026-10-01** on the Android 14 emulator with `flutter run` (3.22.2) attached: connected through DDS, history marked, hot restart followed onto the new isolate (and still worked in `flutter run`), `Flutter.Error`/`Flutter.Navigation` live, HTTP logging restored, no token in any event.

#### P4b — IDE discovery
- [x] DTD discovery (`spock.adb.flutter.dtd`; Flutter 3.47.5 confirmed in S12; first version not pinned): `DtdRegistry` reads the registry files (`~/Library/Application Support/Dart/dtd/<pid>`) leniently, keeps live pids (dead DTDs leave their file behind) and refuses non-loopback addresses; `Workspace` matches `workspaceRoot` to the project (equal, inside it or holding it; symlinks resolved, so `/tmp` is `/private/tmp`); `DtdClient` calls `ConnectedApp.getVmServices` on the P4a JSON-RPC core; `DtdDiscovery` keeps apps on the selected device's model (underscores and spaces alike) and of the project's pubspec `name`, ranked exact workspace first, as `VmServiceSource.DTD` DDS candidates. The DTD secret is redacted by value in every `toString` and exception. Covers `flutter run` from a terminal; apps run from the IDE appear only if the Flutter plugin registers them with the IDE's DTD (S22) [FR1, FR8].
- [ ] Linux and Windows registry directories (S23): best guesses from `applicationConfigHome('Dart')` — Linux `$XDG_CONFIG_HOME/{Dart,dart}/dtd`, `~/.config/{Dart,dart}/dtd`, `~/.dart-tool/dtd`; Windows `%APPDATA%\Dart\dtd` — read when present, **unverified**.
- [ ] Older SDKs (no `ConnectedApp`, e.g. 3.22): the Flutter IntelliJ plugin's running-app state, else logcat: while `flutter run` is attached the VM hands a direct-URI client to DDS (S9, confirmed), so the logcat URI is safe then; connecting before DDS starts (S10) is still open, so a direct connection stays read-only with a warning.
- [x] `FlutterSessionService` (project service): at most one `FlutterSession`, closed on a pooled thread when the project closes; `discover(device, applicationId, pasted)` tries DTD → pasted URI → logcat, logcat only when nothing else found the app; DTD filters by the pubspec package and is confirmed by pid (H2 below), logcat by the selected applicationId's pid (flavors) [FR22]. Blocking by contract — P5's Diagnose and MCP call it from pooled threads.
- [x] **H2 — identity by (device, pid)** (FR22, review D5): a DTD name gives the pubspec package and the device model, which two flavors of one project or two emulators of one image share. A pid is unique only on its own device (two emulators of one snapshot can repeat it), so `DtdAppIdentity` reads each DTD candidate's `getVM()` `pid` and `startTime` over a bare connection (`getVM` only, closed at once, before any session exists) and keeps a candidate whose pid is in `pidof <applicationId>` on the **selected device** (ddmlib, `ShellQuote`) and whose VM started within 5 s after that process — read from the device's own `/proc/<pid>/stat` field 22 + `btime` (`ProcessTiming`; emulator-checked, not yet on a phone). `/proc` refused → the pid alone, marked weaker (`dtd+pid`), only with one match. Several passing → `Ambiguous`, never picked by rank. None → logcat, its VM checked to be on one of those pids. No applicationId → a DTD app is offered only when it is the one listed, flagged unverified. Every candidate carries an `AppIdentity` (serial, applicationId, pid, `verifiedBy`). `ensureSession(device, applicationId)` is the no-paste flow, one call per app at a time: `pidof` each call, a session on that pid reused, `NotReady(reason, retryAfterMs)` inside a 10 s startup window (from the caller's process-start time, else the device's process age, else the first call), no direct-VM connection in the first 3 s nor twice within 4 s, `NoDdsSession` after the window — the DTD still asked on each call, so a later `flutter attach` is found; `disconnect`/`resetAttach` forget it. Session changes reach `FlutterSessionServiceListener`s, each loss once. DDS or not comes from H1: `FlutterSession.connect` throws `NoDdsException` (NotReady in the window, `NoDdsSession` after), and a DTD address that is a VM with no DDS gets nothing past `VmServiceProbe.kindOf`. Live check: add `SPOCK_DTD_APP_ID=<applicationId> SPOCK_DTD_SERIAL=<serial>` to the command below.
- [x] Review fixes D1–D11 (#161): device models normalised as adb and `flutter` reword them (`SM-S918B`, `moto g(60)`, ` (wireless)`); the package filter per workspace match, never from a pub workspace root's pubspec; non-Flutter `Kind:` dropped; an enclosing workspace only at a package root, not `/` or home, nearest first; a reused pid (process younger than the entry's `epoch`) dropped; registry reads bounded; DTD client limits (16 events, 1 MiB) and names capped to one line; `connect` no longer holds the service lock across the app, and `dispose` waits up to 1 s for the close that restores HTTP logging; the service refuses the EDT.
- [ ] The Flutter plugin's running-app state as a source between DTD and the pasted URI.
- [ ] UI via `invokeLater` once there is UI (plugin rules).
- **Gate:** connects to the P0 sample through DTD (newer SDK) and through the fallbacks (3.22) with `flutter run` attached; an IDE-run app (S22); hot reload still works while connected. DTD half, on a machine with `flutter run` attached: `SPOCK_DTD_PROJECT=/path/to/flutter/project SPOCK_DTD_CONNECT=1 ./gradlew test --tests spock.adb.flutter.dtd.DtdLiveCheck --rerun -i` (optionally `SPOCK_DTD_DEVICE_MODEL=<ro.product.model>`) prints the live daemons and candidates, redacted, then connects a session to the first.

### H — Android flow hardening (must, before P5b and P6) · ~4 days
From the joint review of #159, #161 and #162 and the device experiments of 2026-10-02.

- [x] **H1 — connection type by probe, not by source** (#159; done, see P4a). A pasted address is not proof of DDS: a manually forwarded
  VM address looks the same. Experiment (Android 14, app started without `flutter run`): the direct VM answered
  `getDartDevelopmentServiceVersion` with `-32601 Method not found`, and while that direct client stayed connected
  `flutter attach` failed ("connection to device ended too early"); with no client it attached normally (**S10 confirmed**).
  - After connecting, call `getDartDevelopmentServiceVersion` (read-only). DDS answers → writable session. Method not found → direct VM.
  - A direct VM with no DDS is **not kept by default**: disconnect at once and report "the app is running without a debugger session —
    start it with `flutter run`/`flutter attach`; connecting now would block them". An explicit "connect anyway, read-only" stays possible.
  - A 302 to DDS (V1) stays the normal path for logcat/direct addresses while `flutter run` is attached.
  - `VmServiceCandidate.ddsLikely` becomes a hint for ordering only; `readOnly` comes from the probe result.
- [x] **H2 — app identity by process, not by name** (done, see P4b; #161, FR22, review D5). The DTD name gives the pubspec package and the device
  model, which cannot tell apart two flavors of the same project (different application IDs, same `Package:`), nor two
  emulators of the same image (same model). Experiment: `getVM().pid` (31058) equalled `pidof <applicationId>` on the device.
  - Pre-filter and rank with the DTD name (workspace, package, model — normalised as flutter does, review D1/D2/D3).
  - Confirm with `getVM().pid == pidof <selected applicationId>` on the **selected device**: a read-only call on a DDS candidate.
  - No candidate confirms → none is used (fall back to the logcat path, which is keyed by the app's pid already).
  - Several confirm (pid collision across devices) → ambiguous, ask.
- [x] **Review findings**: #161 D1–D11. #162 A1–A15 done (A1 error grouping, A2 frame rows flooding the Timeline, A3 rebuild-storm false positives).
- [ ] **H3 — device validation of the P5a assumptions** (each on the P0 sample, Android emulator):
  - profile-mode `Flutter.Frame` payloads and a real jank fixture (`flutter run --profile`, *Frames and rebuilds* →
    `frames_slow`): budget from `_flutter.getDisplayRefreshRate`; the jank WARNING thresholds (≥ 3 slow frames and ≥ 5 %, or one
    ≥ 700 ms frame) and the Timeline's one row per burst;
  - the frequent-rebuilds rule (built in every frame for ≥ 1 s by frame `startTime`, frames ≤ 250 ms apart; WARNING at ≥ 10
    builds a frame) against the sample's rebuild storm (`frames_rebuild_storm`: reported, no hint); *Looping rotation*
    (`frames_rotation`, a `RotationTransition`) and `frames_slow`'s spinner (an `AnimatedBuilder`): reported as INFO **with**
    "expected if this widget animates continuously" — the rule cannot tell them from a storm, only the hint does; and a fast
    scroll of the 500-item *List → detail routes* (`ListView.builder`) and *Indeterminate progress* (`frames_progress`):
    not reported. Also check the run at 120 Hz if an emulator offers it (review A3);
  - HTTP profile paging: `updatedSince` returns updated in-flight requests; a 4xx whose body is never drained
    (*Network* → `net_404_undrained`) is reported as failed (review A7);
  - hot restart: `errorsSinceReload` resets, RebuiltWidgets location ids restart with the new isolate (review A6), HTTP logging is re-enabled;
  - Android back at the root destroys the engine: the session reports "isolate exited", not a hang.
- [x] **The sample builds on Flutter 3.47.5** (follow-up from the H device gate). Unblocked: 3.47.5 stopped
  in Kotlin compile ("Language version 1.4 is no longer supported") on the sample's Gradle 7.6.3. It now uses
  Gradle 8.14.5, AGP 8.13.2 and Kotlin 2.3.21, which 3.22.2 and 3.47.5 both build (debug APK, plain and
  `spockAppIdSuffix=.second`), analyze and test. Gradle 9 and AGP 9 are out of reach while 3.22 is supported.
  See the sample's README.
- **Gate (end to end, Android):** with an app selected in Spock and nothing pasted, discovery → session → Diagnose shows the
  Flutter error from the P0 Layout screen next to its logcat context, in each of: `flutter run` on 3.22 (logcat → 302 → DDS),
  `flutter run` on 3.47.5 (DTD), two flavors of the sample running together (the second copy: `flutter run
  --android-project-arg spockAppIdSuffix=.second`, see the sample's README), two emulators of the same image. An app started
  without a debugger session is reported, not connected.

### P5 — Cross-layer Diagnose + Timeline (must) · ~7 days

#### P5a — analyzers (pure, no wiring)
Library and tests only, in `spock.adb.flutter.analysis`: no device, no IDE API, no UI, no MCP tool. Inputs are `FlutterExtensionEvent` (kind, device timestamp, isolate, `extensionData`, history flag), built from the session's `FlutterEvent` by one adapter, so a change inside `FlutterSession` touches only that.
- [x] `FlutterErrorReader`: `Flutter.Error` → one line from the tree, not the console text (`ErrorSummary` headline, the "Exception caught by …" description, what was thrown, the error-causing widget's `lib/…:line:col`), `errorsSinceReload` (sent 0-based; reported with the latest error counted); repeats grouped with a count (digits ignored in the headline only; the widget's `file:line:col` keeps two sites apart); multi-line headlines on one line; URL queries cut; clipped; type `flutterError`, ERROR. Replayed events are counted apart and the problem says "(before Spock connected)".
- [x] `FrameStats`: p50/p90/worst build and raster in ms, frames over the budget (`1000 / fps` from `_flutter.getDisplayRefreshRate`, 60 assumed when absent), a frame being over when build **or** raster exceeds it. Type `jank`: nothing when every frame made it; WARNING in profile/release when slow frames are frequent (≥ 3 and ≥ 5 %) or one froze (`elapsed` ≥ 700 ms); the Timeline rates each slow-frame burst by the same rule, else INFO; INFO "not representative" in debug or an unknown build [FR4]. Replayed frames (start-up) left out by default.
- [x] `RebuildTracker`: accumulates `Flutter.RebuiltWidgets` (`events` = `[id, count, …]` per frame; `locations` with names and the older `newLocations` triples, both deltas) → top widgets by rebuilds, per second and per frame, with source location. Frequent rebuilds (type `frequentRebuilds`): a location built in **every** frame for ≥ 1 s by frame `startTime` (frames ≤ 250 ms apart; 60 frames when untimed) — INFO "rebuilt N times in M frames, in every frame for T", with "expected if this widget animates continuously" on `AnimatedBuilder`, `Animated*`, `*Transition`, `TweenAnimationBuilder` and on widgets whose run started with one; WARNING only at ≥ 10 builds in each frame of the run, never for an animation. The inspector counts first builds too and per creation location (`_onRebuildWidget` ignores `builtOnce`), so an average per frame is met by a scrolling list; a run in every frame is not (review A3). A new isolate starts the window over (hot restart renumbers locations), replayed events are skipped, and the tracker is thread-safe. Found in the 3.22 source: each location is sent **once per isolate**, so a second recording, or one started while the IDE's rebuild counts were on, sees bare ids — `seedLocations` takes `ext.flutter.inspector.widgetLocationIdMap` to name them.
- [x] `HttpProfileReader`: requests with method, URL (query stripped, host kept, as logcat's), status, error, duration; failed = an error or a 4xx/5xx status, finished or not (a body never drained never finishes); in flight with no status is not failed; VM Service tokens scrubbed and URL queries cut from URL and error; a failed request can reach the Timeline on two pages, so `httpFailure` returns its request id to key the row by. Type `network`: WARNING for 4xx, ERROR for 5xx and errors. Pages merged by request id. Times are epoch **µs** on the device clock.
- [x] `FlutterTimelineMapper` and categories `FLUTTER_ERROR`, `FLUTTER_FRAME` (one row per burst of slow frames, capped per window; replayed frames none), `NAVIGATION` and `HTTP` (there was no network category); events start from the device's timestamp and `Placement.toHostMs` moves them onto the host clock, frames optionally by `startTime` plus a caller-measured offset. `android_get_debug_timeline`'s schema and `docs/MCP.md` list them. `Flutter.Navigation` carries no push/pop flag, so a row says "Navigator: <route>", never "opened"; the KDoc and `docs/MCP.md` say once which route each call names (pop: the popped one; `removeRoute`: the new current one, or none). `TimelineCategory.parse` matches names first and refuses `flutter`.
- [x] Classifier: a `MissingPluginException` within 500 ms of DartMessenger's `Uncaught exception in binary message listener` (same pid, no other line of that pid in between, failures paired oldest first) is one problem, "Platform channel handler for M on C threw <exception> (inferred from DartMessenger's log)" (type `flutterPlugin`), replacing both the DartMessenger problem and "plugin not registered" (spike S8). A `TODO()` in a handler stays a crash.
- [x] Tests against the device captures in `src/test/resources/flutter/vm-3.22.2/` (Flutter 3.22.2, Android 14 emulator): errors, frames, rebuilds, navigation, HTTP profile, refresh rate; empty, history-only and malformed payloads.
- Found in the capture: the first `Flutter.Error` after a reload renders a DevTools link whose `uri=` holds the VM Service token **percent-encoded** (`%2F<token>%3D%2F`). `Redaction` already scrubs that form; fixtures must be checked for it too, not only for `/<token>=/`.

#### P5b — wiring
- [x] Diagnose gains Flutter sections **inside the existing report and tool** (`android_diagnose_current_screen`, Diagnose panel) when a session is live — no parallel Flutter tool. Done: errors, frames, HTTP and pairing with logcat in H (`FlutterSection`, #163); the logcat source, the cross-layer problem and the rebuild window in P5b (below). The `FlutterProbe` is `AndroidProbe.flutter` (`FlutterDiagnosticSource`, FLUTTER-WIRING.md §8).
  - Flutter errors (overflow, exceptions): from `Flutter.Error` while structured errors are on (P4 tracks the state); from logcat when off (the first error in full, then `Another exception was thrown: …`). An app that replaced `FlutterError.onError` reports to neither, so Diagnose never says "no errors" from the absence of events (spike S3, S7). Errors from before Spock connected are not recovered: `Flutter.Frame` volume evicts them from DDS's replay ring (spike S6).
  - Frames: a problem only in **profile** builds, budget from `_flutter.getDisplayRefreshRate`; info-only in debug ("not representative") [FR4]. `Flutter.Frame` arrives in engine batches and only for rendered frames; place each by its `startTime` (engine-monotonic µs) on a monotonic offset, not by the event timestamp (spike S6).
  - Rebuild storm: a recording window like `GetRecompositionCountsTool`, accumulating `Flutter.RebuiltWidgets`, then switched off — unless it was already on, since the flag is shared with the IDE's rebuild counts (debug only) [FR10].
    Done in P5b, without a new tool: `android_get_recomposition_counts` records rebuilds for a Flutter app with a DDS debug session (`FlutterRebuildRecorder`); read-only sessions and other builds are refused, one window per session, and the flag is switched off only where Spock switched it on, on the same isolate, and nobody else wrote it meanwhile (a close by Spock switches it off through DDS too). The UI Inspector's *Recompositions* tab records through the same path (`RecompositionRecording`, `FlutterFollowerService.recordRebuildsIfFlutter`): widget rebuilds for a Flutter app with a session, Compose otherwise — an add-to-app host with no Flutter session included.
  - HTTP failures from `getHttpProfile` (paged by `updatedSince`); document that `cupertino_http` / `cronet_http` are invisible [FR10].
  - Correlation: a Dart error next to a native crash or a `MissingPluginException` within the same window is reported as one likely problem.
    Done in P5b: a listed error group and a native failure (a crash, a `flutterPlugin` problem, DartMessenger's unpaired listener failure) within the pairing window and on the device clock become one `flutterCrossLayer` problem with both as `parts` (`SectionReport.merges`).
  - Error source follows the structured-errors state P4 tracks: on → `FlutterErrorReader`; off → logcat (the first error in full, then `Another exception was thrown: …`), and the section says which. If neither shows anything in a debug build, say an app that replaced `FlutterError.onError` (Crashlytics, Sentry) hides errors from both — never "no errors".
    Done in P5b: `FlutterLogcatErrors` reads the app's pid's `flutter` lines when structured errors are off or the build is profile, and when they are on and no `Flutter.Error` arrived; `errors.source` says which.
  - A `FlutterProbe` (beside `AndroidProbe`) feeds the sections from the live session; rebuild recording switches `trackRebuildDirtyWidgets` on, seeds locations from `widgetLocationIdMap`, and restores the flag.
- [x] Classifier: done in P5a.
- [x] Timeline service: done in H, `FlutterTimelineRecorder` (FLUTTER-WIRING.md §4, §8; frames at batch time). Record the mapper's events while a session is live. Device timestamps are epoch ms, but `DeviceClock` today converts logcat stamp strings — add an epoch-ms path (same offset) on Android; host time on the simulator. Frames by `startTime`: the offset from engine-monotonic to epoch has to be measured (open: the first frame is reported unbatched, which may be enough) — until then frames sit at their batch time, which the row says. Apply `Redaction` before anything is stored [FR9, FR18].
- [x] Done in H: the Timeline's session-end row says the UI isolate exited (`FlutterTimelineRecorder`; sample README, H gate step 4). The session must report **isolate exited** when the engine goes away: on Android, back at the root activity destroys the Flutter engine (seen in the lead's capture), and the session should say so rather than look connected and idle.
- [x] Done in #159 and H: `callUiExtension` and `callVm` return `Redaction.scrubJson` results, and `FlutterTokenPathTest` runs a fake token through the whole path. Redaction where P5 keeps or shows VM Service data [FR9]: events arrive scrubbed, but call and extension **results** do not — only `ext.flutter.connectedVmServiceUri` / `activeDevToolsServerAddress` answers are redacted by the client. Run `Redaction.scrubJson` over any result that reaches the timeline, a report, the audit log or an MCP answer (`getHttpProfile` request URLs included). `Redaction.scrub` only takes tokens after a loopback or unspecified host, so an app's own `https://…/x=` URLs survive.
- [x] New MCP tool: `flutter_app_status` only (connected?, URI source, build mode, isolate, Flutter version). Everything else rides existing tools.
  Done in P5b: read-only, attaching as Diagnose does. The Flutter version reads "unknown": no framework extension reports it, and `flutter_tools`' `flutterVersion` service is announced only on the `Service` stream, which the session does not follow. The Dart version comes from `getVM`. No address or token in the answer.
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
- [x] `docs/FLUTTER.md`: what Spock adds vs Flutter plugin vs Dart MCP; how to run **Spock MCP + Dart MCP together** (who does widgets, who does device/OS).
- [x] `skills/spock-adb`: a Flutter section — when to call Spock tools vs Dart MCP tools; native dialog handling.
- [x] CHANGELOG under `[Unreleased]`; README links `docs/FLUTTER.md`.

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

## Home navigation state (2026-10-03)

- [x] Current Flutter route, and Flutter routes as their own section of App back stack, from
  read-only VM object inspection (S25), using existing verified sessions; bounded reads,
  KEYED/EXACT/unavailable confidence, nested navigator refusal, event-triggered refresh, popup
  sharing Home's snapshot, and parser/guard tests.
- [x] Navigation sample: imperative routes, unnamed dialog, nested navigators with/without keys,
  and a standalone two-page declarative Navigator. No new dependencies or MCP tool.
- [x] Device validation on Flutter 3.22.2 and 3.47.5: all Navigation checks in the sample README (#169;
  the heap check measured 174–699 ms).
- [ ] Hot restart, selection switching during reads, transition retries, and popup layout in AS 232.
