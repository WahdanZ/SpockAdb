# Flutter wiring (H / P5b core) — design

Status: implemented on `feature/flutter-h-wiring` (2026-10-02), not yet validated on a device. Wave 1 (H1 on #159,
H2 + D1–D11 on #161, A1–A15 on #162) is reviewed, fixed and merged together on that branch. Plan:
[PLAN-FLUTTER.md](PLAN-FLUTTER.md), phase H. Where the code differs from this design, §8 says how and why.

The flow it completes, with nothing pasted:

```
selected device/app ──► automatic discovery ──► verified DDS session ──► Flutter analysis ──► Diagnose / Timeline
   SpockSelection        DTD → logcat (H2)        (serial, pid), kind=DDS   P5a analyzers      sections + correlation
```

## 1. Who owns what

| Piece | Lives in | Owns | Consumes |
|---|---|---|---|
| `FlutterSessionService` (#161, H2) | `spock.adb.flutter` | discovery, (device, pid) verification, the one session per project | `FlutterSession` (#159) |
| **`FlutterFollower`** (new) | `spock.adb.flutter` | *when* to attach: selection changes, app process start, Diagnose | `SpockSelection`, `FlutterBuildCache` (P1), `FlutterSessionService.ensureSession` |
| **`FlutterEventLog`** (new) | `spock.adb.flutter` | bounded per-kind buffer of the live session's events (errors 200, frames 600, navigation 100, rebuilds while recording) | `FlutterSessionListener.onEvent` |
| **`FlutterSection`** (new) | `spock.adb.diagnostics` | the `flutter` part of the Diagnose report | `AndroidProbe.flutter` (new optional field), P5a analyzers |
| **`FlutterTimelineRecorder`** (new) | `spock.adb.timeline` | turning live events into `TimelineEvent`s on the host clock | `FlutterSessionService` listener, `FlutterTimelineMapper` (P5a), `DebugTimelineService.timeline` |

No piece reaches into another's internals: the follower only calls `ensureSession`; Diagnose and the Timeline only read
the session's snapshot and the event log; analyzers stay pure.

## 2. Automatic attach (`FlutterFollower`)

Triggers — no polling:
1. **Selection change** (device or app), the same signal `DebugTimelineService` follows.
2. **App process start** for the selected app (`DeviceEventRecorder` already sees "Process started (pid N)"; needs a
   small hook). No fixed delay is the contract: the follower calls `ensureSession` at once and, on
   `NotReady(reason, retryAfterMs)`, retries with backoff (≈0.5, 1, 2, 4 s) until the startup grace window (~10 s from
   process start) ends. Every attempt re-reads `pidof` on the selected device; a new pid drops the old attempt.
3. **Diagnose** (panel or `android_diagnose_current_screen` / `android_get_debug_context`): attach on demand within the
   report's time budget (≈3 s) when there is no session yet.

Gates before attaching: the selected app is a Flutter app and not a release build (`FlutterBuildCache`, P1); a device
and an application ID are selected. Outcomes are remembered per (serial, applicationId, app pid) so a terminal outcome
(`NoDdsSession`, `NotFound`) is not retried until the app restarts.

**Startup race (S10).** A client on a direct VM that has no DDS blocks DDS from attaching, and `flutter run` attaches
DDS a moment after the VM prints its URI. A probe in that moment can break the developer's `flutter run`. So the probe
only connects, calls `getDartDevelopmentServiceVersion` and closes (a 302 to DDS holds nothing). Inside the grace window,
DTD and logcat evidence come first and probes back off. Validated in H3: `flutter run` started 10× with the follower
active must never fail.

## 3. Diagnose (`FlutterSection`, id `flutter`)

Seam: `AndroidProbe(…, flutter: FlutterDiagnosticSource? = null)`. The two places that build a probe — `DiagnosePanel.collect`
and `DebugContextTool` — fill it from `FlutterSessionService.ensureSession` (bounded). Other sections are untouched.

Data (bounded like every section):
- `attach`: the `FlutterAttachOutcome` in words, never "no errors": Connected (reused or new) / NotRunning / ReleaseBuild /
  NotReady ("the app is starting; Flutter session not ready yet") / NoDdsSession (its message) / Ambiguous (which ones) /
  NotFound(reason) / Failed. Not a Flutter app → no section content at all (the follower never calls `ensureSession`).
- `identity`: device serial, application ID, pid, verified by (DTD+pid / logcat+pid); `connectionKind` (H1); build mode;
  `structuredErrors` on/off.
- `errors`: `FlutterErrorReader` over the event log since connect (history counted apart: "before Spock connected").
- `frames`: `FrameStats` (jank only in profile; debug says "not representative").
- `http`: `HttpProfileReader` over `getHttpProfile` (read via the session's read API; paged by `updatedSince`).
- `navigation`: last few routes.
- Notes: structured errors **off** → "framework errors are in logcat — see `logs`"; an app that replaced
  `FlutterError.onError` → no `Flutter.Error` and nothing in logcat → say so when the session is live, errors are on, and
  none arrived while the Layout-style signals (red screen in UI tree, overflow text) suggest one.

Problems: the analyzers' `LikelyProblem`s; their type ids join `DiagnosticCollector.TYPE_PRIORITY`
(flutterPlugin, flutterError above network; jank and rebuild below).

**Correlation** (the gate's "Flutter error next to its logcat context"): a collector post-step pairs each Flutter error
group with log problems whose device time is within ±2 s, using device epoch ms on both sides (Flutter: event timestamp;
logcat: the stamp moved to epoch with the device zone, §4a). Output: `flutter.errors[i].nearbyLogs` (ids of `likelyProblems`), so the panel
and agents can show them together. Rebuild recording is **not** started by Diagnose (it writes to the app); a separate,
explicit action records a window.

## 4. Timeline (`FlutterTimelineRecorder`)

- Follows `FlutterSessionService` session changes; on each live session subscribes to events, maps them with
  `FlutterTimelineMapper` (A2 rules: no per-frame rows in debug, bursts coalesced in profile), records into
  `DebugTimelineService.timeline`.
- Host time: see §4a. Frames use `startTime` only when an offset to the engine clock is known (P5a note); otherwise the
  event time.
- HTTP recording row: when the snapshot says `EnabledBySpock`, one row "Spock turned on HTTP recording for <app> (restored
  when Spock disconnects)"; another when it is restored.
- Session lifecycle rows: "Flutter session: <app> on <device> — DDS, pid N (via DTD)", "ended: isolate exited / app
  stopped / Spock disconnected" — including Android back-at-root destroying the engine.
- Redaction: events arrive scrubbed (#159); the mapper scrubs again (P5a A8/A12).

## 4a. One clock model, owned by the session

Diagnose correlation and the Timeline use the same `DeviceTime` model, held by the live `FlutterSession` (set by the
service at attach, from the verified serial). Measured on emulator-5554, 2026-10-02:
- `date +%s%3N` works (toybox); one adb round trip took 94–441 ms, the first much slower (warm-up), and naive offsets
  varied by ~150 ms.
- The device zone was `+0200` (Europe/Berlin). **logcat `threadtime` stamps are device local time, VM event timestamps
  are UTC epoch.** Comparing them as if both were epoch, as the first draft of §3 did, is off by the zone: 2 h here.

Model:
- Samples: one `adb shell 'date "+%s%3N %z"'` per sample, 5 samples, the first discarded. Keep the sample with the
  smallest round trip; `epochOffset = deviceMs − (sent + received) / 2`, and `uncertainty = roundTrip / 2`.
  The zone comes from the same sample.
- `epochToHost(ms)`; `logcatToEpoch(stamp)` = the stamp read as UTC minus the zone (year chosen as `DeviceClock` does).
- Re-sampled per session; a session that reconnects after a hot restart keeps it.
- A `date` without `%3N` (prints it literally) falls back to `+%s` with uncertainty 1 s, and the report says so.
- Correlation compares on device epoch (no adb noise at all); the Timeline converts to host with `epochToHost`.
- The window stays ±2 s, widened by the uncertainty.
- The existing logcat Timeline rows keep their marker-based `DeviceClock`. The gate checks that the two agree within the
  uncertainty; unifying them is a follow-up.

## 5. What wave 1 exposes (landed, 2026-10-02)

| From | API the wiring uses |
|---|---|
| #159 (H1) | `FlutterSession.addListener(listener, replayState = true)` (delivered on the session thread); `snapshot`: `connectionKind`, `connectedAtHostMs`, `vmPid`, `vmStartTimeMs`, `operatingSystem`, `httpRecording` (EnabledBySpock / AlreadyOn / Pending / Off(reason)); `callUiExtension(method, params, timeoutMs)` (read allowlist on non-DDS) and `callVm(...)` (no `ext.*`), both scrubbed; `connect(candidate, allowDirect = false, recordHttp = true)` |
| #161 (H2) | `FlutterSessionService.ensureSession(device, applicationId, processStartedAt?, build?)` → `FlutterAttachOutcome` (Connected / NotRunning / ReleaseBuild / NotReady(reason, retryAfterMs) / NoDdsSession / Ambiguous / NotFound / Failed); `identity: AppIdentity(serial, applicationId, pid, verifiedBy: IdentityCheck)`; `addListener(parent, FlutterSessionServiceListener)` with Connected / Replaced / Disconnected; `resetAttach()` |
| #162 (A) | `FlutterTimelineMapper.Placement(zone, deviceSerial, frameStartOffsetMs, toHostMs)` (zone required); `map(events, fps, build, placement)`; `httpFailure(request, placement)` → `HttpFailure(requestId, row)` (dedupe by id); `FlutterProblemTypes`; error groups with `firstSeenMs` / `lastSeenMs` (device epoch) |

`recordHttp` is passed by `ensureSession`'s caller from the Settings switch (§7); today `ensureSession` passes `true`
itself, so the wiring adds that parameter.

## 6. Tests (prepared as a list; written against the merged APIs)

- Follower: selection change → one `ensureSession`; release build / non-Flutter app → none; NoDebugSession not retried
  until a new app pid; process start → NotReady retried with backoff and stopped at the grace window; pid change resets.
- DeviceTime: min-round-trip sample wins; zone applied to logcat stamps (+0200 case); `%3N` fallback; correlation
  window widened by the uncertainty.
- FlutterSection: each outcome renders words, not "no errors"; structured errors off → points at `logs`; history errors
  labelled; correlation pairs an error with a log problem 1 s away and not one 5 s away; bounded output.
- Timeline: events land on host time with a known offset; debug frames produce no rows; session start/end rows; no token
  in any row (fake token through the whole path).
- End-to-end (device, the H gate): P0 Layout overflow → Diagnose shows the `Flutter.Error` and its logcat context, and the
  Timeline shows both, with nothing pasted — `flutter run` 3.22 (logcat → 302 → DDS), 3.47.5 (DTD), two application IDs
  side by side, two emulators reporting the same model.

## 7. HTTP recording on automatic attach — decided 2026-10-02

On by default, so Diagnose and the Timeline already hold the HTTP failure that came before the bug was noticed. Guardrails:
- only verified DDS sessions;
- only debug and profile builds;
- exact ownership: Spock restores only what it enabled, and never touches logging that was already on;
- a Settings switch, "Record Flutter HTTP traffic automatically", default on;
- a Timeline row when Spock turns it on and when it restores it, and the `flutter` section says which.

## 8. As built — where the code differs

- **Who owns what.** `FlutterFollower` is a plain class (testable without the IDE); the project service is
  `FlutterFollowerService`, which owns it and the `FlutterEventLog`, wires the triggers (`SpockSelection`, the
  Timeline's process starts, Diagnose) and builds Diagnose's `FlutterDiagnosticSource`. `FlutterTimelineRecorder` is
  owned by `DebugTimelineService`, whose timeline it records into, and records only while **Record device events** is on.
- **Events from before `Connected`.** `FlutterSessionServiceListener` gained `sessionCreated(session)`, called before
  the session connects: DDS replays history during `connect`, before any `Connected` change, so the log and the
  recorder subscribe there and drop the events of a session that never becomes current.
- **The clock.** `FlutterSession.deviceTime` is a `DeviceTimeSlot`; `FlutterSessionService.ensureSession` measures it
  once per session on a pooled thread, on the device it verified (`DeviceTimeSampler`, `date '+%s%3N %z'`, 5
  samples, first dropped, least round trip). A session opened with a pasted address (no device) has none; rows are
  then placed on the device's clock in UTC and the section pairs no log lines, and both say so.
- **Correlation ids.** `likelyProblems` entries gained `id` (`p1`…), and a section can run a step after ranking
  (`SectionReport.afterRanking`); `flutter.errors.groups[i]` carries `problem` (its own id) and `nearbyLogs`.
  `DiagnosticSection.appliesTo` leaves `flutter` out for an app that is not Flutter.
- **Pairing before ranking (device gate, 2026-10-02).** Pairing only the top ten failed on the device: start-up
  noise from minutes earlier (ziparchive, avc denied, hidden API, OpenGLRenderer) filled the list, and the app's own
  warning 1.3 s before the overflow was not in it. Now the section pairs against every log problem before ranking
  (`SectionReport.companions`), at most 5 per error, the closest first, and the collector lists each right after
  its error — one that already ranks higher (a crash) stays where it is. `nearbyLogs` are ids in `likelyProblems`
  again; `moreNearbyLogs` counts pairs below the cut, which happens only when the error itself is below it.
- **Frames on the Timeline.** No frame rows in debug or an unknown build (§6's "debug frames produce no rows"); in
  profile, one row per burst per 2-second window. Frames are placed at batch time: no engine-clock offset is measured.
- **Follower memory.** Terminal outcomes are remembered for the automatic triggers only; Diagnose always asks, since
  `flutter attach` may have run since. Backoff 0.5/1/2/4 s then 4 s again within a run's time budget, or the
  service's `retryAfterMs` when longer; between runs, a slow check (see "Re-attach after a lost `flutter run`").
- **Not built here:** the check that the session clock and `DeviceClock` agree (the H gate does that by eye). The
  rebuild recording window, the logcat source for errors when structured errors are off, the cross-layer problem
  and `flutter_app_status` came after H, in P5b (PLAN-FLUTTER.md, P5b; docs/MCP.md). The logcat source is read by
  the section through `FlutterDiagnosticSource.Reads.flutterLog`, the report's own window (`maxLogcatLines`,
  1,500 by default) filtered to `flutter:I`, and only the session's own pid counts. The merge is a new
  pre-ranking step, `SectionReport.merges`, run before `companions`.
- **Device gate, 2026-10-02 (emulator-5554, 3.22.2), and what changed.** The first run never attached by itself:
  the follower's attempt on "Process started" found no pid — Android logs the start ~2 s before the process has its
  name, so `pidof` is empty — and stopped. Now a run triggered by a process start (or a lost session) retries "not
  running", and every run has a time budget instead of an attempt count. The VM announced itself 6 s after its
  process, so the startup window now runs from the announcement too (`AppStartup`: 15 s after the process, 15 s
  after the announcement). A Flutter tool's `adb forward` to the VM's device port, read from the adb server
  (`host:list-forward`, no VM contact), keeps Spock off the VM for 10 s after the later of the forward first seen
  and the announcement (`TOOL_SETTLE_MS`): the S10 risk is only until DDS attaches, which the tool does right after
  forwarding, and a probe once DDS is attached gets a redirect and holds nothing. Then a VM still without DDS is
  probed again at the usual spacing while the forward stays, up to 60 s from the announcement
  (`TOOL_ATTACH_CAP_MS`), after which the normal rules give "no debugger session". Expected attach on 3.22: about
  10–15 s after the VM line. A "no DDS" verdict is re-checked every 30 s with no VM contact until a tool forwards
  the VM. Every follower and attach decision is logged at INFO.
- **HTTP recording owned across sessions (device gate, 2026-10-02).** A session whose DDS died with `flutter run`
  could not switch off the HTTP recording it had switched on, and the next session on the same isolate (`flutter
  attach`) read it as `AlreadyOn` and left it on. `FlutterSessionService` now remembers per (serial, pid, isolate)
  what Spock switched on and has not switched off (`HttpOwners`, bounded, dropped when the pid changes); a session
  on that isolate adopts it (`HttpRecording.AdoptedBySpock`), switches it off on close through DDS, and the Timeline
  says "Spock's HTTP recording for <app> from the earlier session is still on; Spock will switch it off when it
  disconnects". Spock's VM Service forwards are logged at INFO as they are made and removed.
- **Cached-app freezer (device gate, 2026-10-03).** An app left in the background overnight was frozen by Android
  (`isFrozen=true` in `dumpsys activity processes`, API 34): its VM answered nothing, four attaches timed out, the
  follower gave up, and nothing re-armed it when the app came back. Now the service reads the freezer state before
  any VM contact (`ProcessFreezer`) and answers `NotReady(frozen = true)` with the reason in words; the follower parks
  without spending its time budget (a `dumpsys`-only check every 30 s, at most 20), and the Timeline's device recorder
  re-arms it when one of the app's activities resumes — as it does a follower that gave up. Unknown freezer state
  keeps the old behaviour.
- **Re-attach after a lost `flutter run` (sandbox, 2026-10-09).** The sandbox's project window closed at 19:02:34;
  Android Studio terminates adb when its last project closes, so `flutter run` lost the device and its DDS while the
  app (pid 4292) ran on. The closing project's follower logged "session lost" and was disposed with it. The project
  reopened at 19:07:41, but its Gradle sync failed (AGP too new for the sandbox), so it named no app, and the app
  chosen before was not remembered: the new follower had nothing to follow when `flutter run` started pid 7590 at
  19:08:45, and attached only when the app was selected by hand at 19:14:33. Fixed, with the gaps the same scenario
  shows with the project left open:
  - `SpockSelection` remembers the selected app per project and, when Gradle names none, restores it if the device
    has it installed. A restored app is provisional: when the device arrives before sync ends, the sync's end reads
    the apps again and the project's app replaces it; choosing an app clears that;
  - ddmlib naming one of the selected app's processes (`IClientChangeListener`, `CHANGE_NAME`; static, so it outlives
    an adb restart, unlike the device log that carries `Start proc`) starts a run when the pid is new and none is
    under way. Matched on the process name (`clientDescription`): ddmlib's `packageName` is the package for every
    process of the app, `com.foo:bg` too, while `pidof com.foo` lists only the main one, so a restarting secondary
    process re-armed the follower each time. This is a shortcut, not the mechanism: ddmlib sees only debuggable
    processes — a profile build on a production phone that is not rooted never fires `CHANGE_NAME`, and only the
    watch below catches its restart — and in recent Android Studio ddmlib's process tracking is backed by adblib, so
    that the listener fires there is unproven until H gate item 9 runs on a device;
  - a run that ends without a session — its time spent, no DDS, the app not running — keeps watching
    (`RECHECK_MS` 30 s, `RECHECKS` 20: the old "no DDS" re-check, generalised), and a new pid seen there starts a
    fresh run with its own time budget (before, a pid change kept the spent budget and gave up at once). Diagnose
    finding the app not ready during a watch starts a run with the backoff, rather than waiting up to 30 s for the
    next check, so a `flutter attach` it found starting is followed at once;
  - an attempt that throws (ddmlib throws `CancellationException` while adb restarts) is asked again like a
    failure, where it ended the run with nothing scheduled; and the follower reads the app's build as an answer or
    adb failing (`FlutterBuildCache.readOn`): adb failing is asked again, while an app read as not Flutter — one
    uninstalled answers `dumpsys package` with no `versionCode`, and its listing is empty — stops the follower,
    even after a Flutter build was read.
  Follow-up review: a pid change inside a run restarts the backoff but no longer renews the run's time, and the
  watch renews it once per trigger — before, an app restarting at every attempt was asked every 500 ms for ever
  (7,201 attempts in a simulated hour). A watch that ends connected clears "gave up", so the next foreground
  starts nothing.
  Sample README, H gate item 9.
- **P5b review (PR #168, 2026-10-03).** The rebuild window counts from the app's
  `Flutter.ServiceExtensionStateChanged` for the tracking flag, which Flutter posts after the frame its switch
  forced, so that reassemble is not counted; and it counts those announcements to tell Spock's write from the
  IDE's or DevTools'. A session takes `FlutterSession.CloseRestore`s: what a caller switched on, run by `close`
  through DDS, before HTTP logging is switched off and the socket closed (a lost connection runs none). The
  recorder registers one per window and addresses its isolate by id. The Recompositions tab and the MCP tool share
  `RecompositionRecording`. The cross-layer merge measures between occurrences (`Group.occurrencesMs`, the latest
  100); logcat repeats join their full report; a profile build's first error is read from its
  `debugPrintStack` form.

## 9. Home: current Flutter route and back stack (2026-10-03)

`FlutterRouteSource` reads only the already connected, verified session for Home's selected app/device.
It never attaches. `SessionObjects` sends bounded `getIsolate`, `getObject`, and (on click only)
`getInstances` through `FlutterSession.callVm`, retaining its read-only guard and token redaction.
No evaluate, invoke, runtime dependency, coroutine or MCP tool is added.

`NavigatorReader` owns all Flutter private field names: `WidgetsBinding._instance` →
`_buildOwner._globalKeyRegistry` → StatefulElement `_state` → mounted NavigatorState `_history`
→ `_value` → route `_settings.name`. A plain history list is also supported. VM class superclass
reads recognise NavigatorState subclasses; the heap query includes subclasses of NavigatorState.
Fields count only when the VM reports Flutter's class as their owner (a mixin's: its mixin
application), and NavigatorState only from `navigator.dart`, so an app subclass's same-named
private field or class cannot stand in. Missing fields, expired objects, partial collections and
exceeded bounds give unavailable. Home opens at most 80 keyed StatefulElements per read within a
shared 3 s call budget, and remembers for the isolate those that hold no navigator (by the
`identityHashCode` of key and element: object ids change between calls), so later reads open only
new ones; past 80 it says how many keyed widgets there are and goes on next read. The click check
has 5 s and returns at most 20 heap instances (including unmounted instances in that limit).

One keyed navigator gives KEYED: Home's tooltip describes the root navigator and the unchecked
possibility of an unkeyed nested navigator. Several keyed navigators give unavailable: v1 does
not prove nesting or choose a visible navigator. On clicking **App back stack** for an app with a
session, a heap count of exactly one mounted navigator gives EXACT, but only if that navigator is
the keyed one (same `identityHashCode`) and the stack, read again after the walk, holds the same
idle entries; otherwise “the navigation changed”, transient. Additional mounted navigators give
unavailable, remembered for that session/isolate until a successful check. The same checked
snapshot updates Home and the popup's **Flutter routes** section; the activities are read beside it
and shown as their own **Activities** section, never nested under an activity (with add-to-app,
Spock cannot prove which activity hosts the engine). Either section that fails says why while the
other still shows. Without a session App back stack is the activity chooser as before: no VM
reads, no pause. A new isolate starts clean. The popup reports call duration and says the heap
check briefly paused the app (S25 measured 120–364 ms; RPC duration is not exact pause time).

Normal Home refresh and debounced (300 ms) live Flutter.Navigation/session state notifications
trigger reads, only while Home is showing (a read skipped out of sight runs when it shows again).
Events never supply route data. `FlutterReadRequests` keeps Home's reads and the click check as
separate latest requests, so a refresh during the check does not swallow its popup; session/isolate
checks discard late results after selection or hot restart. Transitional reads retry at most
three times per burst.

Flutter 3.22.2's [_RouteEntry.isPresent](https://github.com/flutter/flutter/blob/3.22.2/packages/flutter/lib/src/widgets/navigator.dart)
uses the range add through remove, including pop and complete; popping is outside that range.
This implementation conservatively requires **idle for every entry**, otherwise unavailable
“navigation in progress”, without dropping entries. The same source defines Page as a
RouteSettings subclass and stores it in Route._settings: Page.name needs no separate parser.
The [VM Service protocol](https://github.com/dart-lang/sdk/blob/main/runtime/vm/service/service.md)
defines getObject/getInstances as inspection reads, with includeSubclasses on the latter.

Hand-built S25-shaped fixtures cover 3.22.2 and 3.47.5 parsing; they are not new device captures.
The Navigation sample's expanded cases remain **device-unverified** on both SDKs; see its README.
