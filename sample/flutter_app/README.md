# Spock Flutter Sample

A Flutter fixture app for checking Spock ADB on Flutter apps, on Android and the iOS simulator.
It is a separate Flutter project: the plugin's Gradle build, tests and Detekt never see it.
Plan: `docs/PLAN-FLUTTER.md`.

```bash
cd sample/flutter_app
flutter run                 # debug: everything
flutter run --profile       # profile: frame times mean something (Android device/emulator)
```

Supported Flutter versions: **3.22.2** and **3.47.5**; the sample builds, analyzes and tests on both.
The Android build uses the newest versions both accept: Gradle 8.14.5, AGP 8.13.2 and Kotlin 2.3.21.
3.47.5 needs at least Gradle 8.14, AGP 8.11.1 and Kotlin 2.2.20. Gradle 9 cannot compile 3.22's
Flutter Gradle plugin, and AGP 9 needs Gradle 9. So 3.47.5 warns that support for this Gradle and
AGP "will soon be dropped": expected, ignore it. 3.22.2 says "One or more plugins require a higher
Android NDK version"; a debug build uses no NDK, ignore that too.

AGP 8 needs JDK 17 or newer (the sample is built with 17). If Gradle fails with an unsupported Java
version, point Flutter at a JDK 17: `flutter config --jdk-dir <path to JDK 17>`.

Switching Flutter versions in one checkout:

- Run `flutter clean` first. `build/` keeps the other version's compiled shaders, and `flutter test`
  then fails in `InkSparkle` with a `RangeError`.
- 3.47.5 rewrites `pubspec.lock`: its SDK pins newer `meta`, `collection`, `test_api` and others, and
  Dart 3.11, which 3.22.2 cannot resolve. The committed lock is 3.22.2's, so don't commit that
  change; `git checkout pubspec.lock` after.

Android package: `spock.adb.spock_flutter_sample`. iOS bundle id: `spock.adb.spockFlutterSample`
(iOS bundle ids allow no underscores). Every control has `Semantics(identifier:)`,
which Flutter publishes as the Android resource-id, so Spock's `android_*` element tools find it.
Around a button that makes two nodes with the same bounds: the id on an unlabelled parent, the label
and `clickable` on the child. Results (`result`) carry their text as the node's content-desc.

Push from a shell without Spock (the receiver only accepts the app itself as sender):

```bash
adb shell run-as spock.adb.spock_flutter_sample am broadcast --user 0 -a com.google.android.c2dm.intent.RECEIVE -p spock.adb.spock_flutter_sample --es title Hello
```

| Spock feature | Where in the sample | What to look for |
|---|---|---|
| UI tree, `android_find_ui_element`, `android_tap_element` | *Login* | Resource-ids `login_email`, `login_password`, `login_submit`, `result`. *Remember me* and *Help* have no identifier on purpose |
| Open Deep Link | *List → detail routes* | `spockflutter://open/item/42?ref=spock` opens *Item 42* with `ref=spock`; on a cold start Back goes to *Items*, then the hub. `spockflutter://open/nowhere` opens *No such route* |
| Flutter errors in Diagnose / Timeline | *Layout overflow* | *Show overflow* draws the yellow-black stripe. In a debug build the error goes **only** to the VM Service (`Flutter.Error`), not logcat. *Overflow with a native warning* (`layout_overflow_logged`) does the same and logs one `W/SpockSample` line at the same moment, so Diagnose has a log problem to pair the error with |
| Log problem detection | *Errors and plugin failures* | One failure per button. In logcat (tag `flutter`): unhandled async error, `MissingPluginException`, `PlatformException(SAMPLE_ERROR)`. *Exception in a channel handler* (Android only): Flutter catches it, logs `Failed to handle method call` (tag `MethodChannel#spock.sample/native`) and the Dart side gets `PlatformException(error, …)`; the app survives. *Checked exception in a channel handler* (`error_channel_checked`, Android only): the `IOException` gets past `MethodChannel`; `DartMessenger` logs `Uncaught exception in binary message listener` and replies empty, so the Dart side gets a `MissingPluginException` although the handler exists; the app survives. *Dart error from a failing channel call* (`error_cross_layer`, Android only): the checked exception above, and the app reports the `MissingPluginException` it gets back with `FlutterError.reportError`, so a `Flutter.Error` lands beside the native line (one cross-layer problem in Diagnose). *TODO() in a channel handler* (`error_channel_todo`, Android only): `NotImplementedError` is a `java.lang.Error`, which nothing catches, so the app crashes. On iOS both answer not-implemented (`MissingPluginException`). *Native crash* kills the app on both platforms (Android: uncaught exception on the main thread; iOS: `fatalError`). VM Service only (`Flutter.Error`, while structured errors are on): tap-handler error and the red screen from `build()`. *Custom FlutterError.onError* (`error_custom_on_error`) stands in for a crash reporter: while it is on, framework errors (this screen and *Layout overflow*) post no `Flutter.Error` and reach logcat only as one `SPOCK_SAMPLE custom FlutterError.onError: …` line; switch it off to get Flutter's handler back |
| Network, HTTP proxy, Wi-Fi toggle | *Network* | 200, 500, 404 and an unknown host through `dart:io` `HttpClient`, each logged as one line. *GET 404, body never read* (`net_404_undrained`) reads the status and never the body, so the VM Service's HTTP profile never marks it finished: Spock must still report it as a failed request. The status codes come from httpbin.org, which is sometimes slow or down (502/503, or the 15 s timeout): repeat before blaming Spock |
| Native screens above Flutter, grant / revoke | *Native permission dialogs* | Camera, location and notifications open the system dialog; status refreshes on resume |
| Push messages | *Push messages* | Android: `PushReceiver` stands in for Firebase, stores the message as `flutter.last_push`. iOS (after notifications are allowed): a banner even in the foreground, and the `AppDelegate` notification delegate stores a summary (title, body, userInfo) as `flutter.last_push`; a push received in the background is not stored |
| App Storage, Clear Cache vs Clear Data | *Storage* | `shared_prefs/FlutterSharedPreferences.xml` with `flutter.`-prefixed keys of every type, `app_flutter/settings.hive`, `databases/notes.db`, a cache file, and a support file Clear Cache must keep |
| Flutter logcat preset, redaction | *Logs* | `print`, `debugPrint`, `developer.log` (VM Service only), a 200-line burst, a line with a token |
| Jank and frequent rebuilds (VM Service, later phases) | *Frames and rebuilds*, *List → detail routes* | Slow frames (`frames_slow`) busy-wait 40 ms each. Rebuilds, after a second of every-frame builds: the rebuild storm (`frames_rebuild_storm`) is reported with no hint. *Looping rotation* (`frames_rotation`, a `RotationTransition`, no busy-wait) and the slow frames' spinner (an `AnimatedBuilder`) rebuild every frame by design: reported as INFO with "expected if this widget animates continuously", never as a warning. Not reported at all: *Indeterminate progress* (`frames_progress`), which animates inside Flutter's own widgets, and scrolling the 500-item list of *List → detail routes*, which builds each item as it scrolls in, for well over a second |

## End to end: selected app → session → Diagnose and Timeline (the H gate)

Nothing is pasted at any step. Before each run: in Spock's tool window select the emulator and
`spock.adb.spock_flutter_sample`, keep **Record device events** on in the Timeline tab, and keep
**Settings → Tools → Spock ADB → Record Flutter HTTP traffic automatically** on.

1. **Automatic attach, verified DDS session.** `flutter run` (debug). The Timeline shows
   `Flutter session: spock.adb.spock_flutter_sample on emulator-5554 — DDS, pid N (verified by
   dtd+pid+start)` and `Spock turned on HTTP recording for spock.adb.spock_flutter_sample (restored
   when Spock disconnects)`: within seconds on Flutter 3.47.5, where the Dart Tooling Daemon names
   the app; on 3.22 (`logcat-pid`) about 10–15 s after the VM announces itself (`The Dart VM service
   is listening on …` in logcat), because for 10 s after `flutter run` forwards the VM Spock does not
   connect to the VM itself (it could keep DDS out). `flutter run` itself must keep working: hot reload with `r`. idea.log shows every
   decision as `Flutter follower: …` and `Flutter attach for …` lines.
2. **Flutter error beside its logcat context.** *Layout overflow* → *Overflow with a native warning*,
   then **Diagnose**. Expect the `flutterError` problem "Exception caught by rendering library: A
   RenderFlex overflowed by … — Row at lib/fixtures/layout.dart:…" and under it *In logcat around it:*
   the `SpockSample` warning; in the raw report, `flutter.errors.groups[0].nearbyLogs` names that log
   problem's id. The Timeline shows the Flutter row and the log row within a second of each other
   (`deviceTime` on both reads the device's local time). *Show overflow* alone: the error, with no
   log beside it.
3. **HTTP failure.** *Network* → *GET 500*, then *GET 404, body never read*; wait ~5 s. The Timeline
   shows an `HTTP` row for each, and Diagnose's `flutter.http.failures` lists both — the 404 although
   its body is never read.
4. **Back at the root.** Press Back on the hub until the app leaves the screen. Within ~2 s the
   Timeline shows `…'s UI isolate exited — the Flutter engine was destroyed (on Android, Back at the
   root activity does this)`. Open the app again: a new session row, or the old one picking up the
   new isolate.
5. **A session's end.** With both copies running (item 10), select the other one: `Flutter session
   ended: … — Spock connected again, to a newer session` and `Spock switched HTTP recording back off
   for …`, then the new session's row. Quit `flutter run` with `q`: the end row says the connection
   was lost, as a warning.
6. **The Settings switch.** Turn *Record Flutter HTTP traffic automatically* off, restart the app: no
   "turned on" row, and Diagnose's `flutter.identity.httpRecording` says the setting is off.
7. **No debugger session.** Start the app from the launcher (not `flutter run`). After about 30 s,
   Diagnose's `flutter.attach` says the app is running without a debugger session; Spock never stays
   on its VM, so a `flutter attach` afterwards works. On 3.47.5 the next Diagnose finds it through
   the Dart Tooling Daemon and connects; on 3.22 the "no DDS" verdict holds for that process until
   the app restarts.
8. **Frozen in the background.** Leave the app in the background until Android freezes it
   (`adb shell dumpsys activity processes spock.adb.spock_flutter_sample` shows `isFrozen=true`;
   minutes to hours), select it: Diagnose's `flutter.attach` says Android froze it, and idea.log shows
   `Flutter follower: … is frozen in the background`. Bring it to the foreground: the session row follows
   within seconds, with nothing pressed in Spock.
9. **A lost `flutter run`, then a new one (issue: follower re-attach).** With the session up, make
   `flutter run` lose the device while the app keeps running: `adb kill-server` (or `adb forward
   --remove-all`) in another terminal. `flutter run` prints `Lost connection to device.`, idea.log
   shows `Flutter follower: session lost: following …`, and the old pid runs on without DDS — the
   follower then logs `…; checking again in 30000 ms` (no VM contact while the "no DDS" verdict
   stands). Within ten minutes, start the app again with a new `flutter run`, and touch nothing in
   Spock: within ~30 s of the new process, idea.log shows
   `… runs as a new process [N]: following it` (the slow check) or `a new process appeared:
   following …` (ddmlib naming the process), then `Connected (new), pid N`, and the Timeline shows
   the new session row. Which line appears is what this item measures: ddmlib sees only debuggable
   processes, so a profile build on a production phone that is not rooted is caught by the slow
   check alone; and in recent Android Studio ddmlib's process tracking is backed by adblib, so that
   ddmlib's line comes at all — and comes sooner — is unproven until it is seen here, on a device.
   Repeat with **Record device events** off: the same, since ddmlib and the slow check do not need
   the device log. The way it
   was first seen: close the IDE's only project window while `flutter run` runs (Android Studio then
   kills adb, and `flutter run` loses the device), reopen the project, and start a new `flutter run`:
   Spock's selector shows `spock.adb.spock_flutter_sample` again without being chosen, and the session
   row follows.
10. **Two copies, two emulators.** The second copy below, both running: select each in turn — the
   session row names the selected one's pid. Two emulators of one image: the session follows the
   selected emulator's serial.

## P5b checks: status, errors from logcat, rebuild window, one problem for two layers

Same setup as above: the emulator and the app selected in Spock, nothing pasted.

1. **`flutter_app_status`.** With `flutter run` attached, call it from an agent with no
   arguments. Expect `applicationId` to be the app selected in Spock, `connected: true`,
   `verifiedBy` `logcat-pid` (3.22) or `dtd+pid+start` (3.47.5), `connectionKind: dds`,
   `buildMode: debug`, `uiIsolate.name: main`, the Dart version, and the clock `measured`. Stop
   `flutter run` and start the app from the launcher: after about 30 s, `attach` says the app runs
   without a debugger session (at 15 s it still says the app is starting: the startup window is
   15 s from the later of the process start and the VM's log line). No answer may contain a
   `ws://` or `http://127.0.0.1` address. With the second copy (below) installed and selected in
   Spock, a call with no `packageName` describes `spock.adb.spock_flutter_sample.second`, not the
   project's ID, and the first copy's session is not closed by it.
2. **Errors with structured errors off.** The inspector's switch cannot be flipped from Dart, so
   run the sample with it off:

   ```bash
   flutter run --dart-define=flutter.inspector.structuredErrors=false
   ```

   *Layout overflow* → *Show overflow*, then *Errors and plugin failures* → *Throw in a tap
   handler* twice, then **Diagnose**. `flutter.errors.source` starts "Structured errors are off".
   The overflow is listed in full (`source: "logcat"`, with its `Row at lib/fixtures/layout.dart`),
   and the tap error as `Another exception was thrown: Bad state: …` with a count of 2. *Hide
   overflow*, then *Show overflow* again: it stays **one** group, its count going up, not a
   second "Another exception was thrown" group. `flutter.identity.structuredErrors` is `off`.
   On 3.22, DevTools' *Structured errors* toggle (`ext.flutter.inspector.structuredErrors`) in a
   normal `flutter run` does **not** move errors at runtime: events keep coming with it off, and
   logcat keeps getting them with it on. Switch it off after an overflow arrived as an event, then
   Diagnose: `errors.source` starts "Flutter.Error events from the VM Service: these arrived while
   structured errors were on; they are off now …" and ends "where none are yet", and the group's
   `source` is `vmService`. `errors.source` starts "Both" only when some errors arrived as events
   and others were printed to logcat; a group read in both places has `source` `vmService and
   logcat`.
3. **A profile build's errors.** `flutter run --profile`, then *Throw in a tap handler* twice and
   **Diagnose**. `flutter.errors.source` starts "A profile build has no inspector". Two groups,
   each with a count of 1: "Exception caught by Flutter: Bad state: Sample error thrown in a tap
   handler", Flutter's message-and-stack print of the first tap; and "Another framework error; a
   profile build prints repeats without their message", the second tap's repeat, which a profile
   build prints as `Another exception was thrown: Instance of 'ErrorSummary'` whatever the error
   was, so it is not added to the first. `notes` says the message shows only in a debug build or
   through the app's own `FlutterError.onError`. A third tap raises the second group's count.
   (Overflow errors are debug-only and do not appear.)
4. **Silence is never "no errors".** In a normal `flutter run`, turn *Custom FlutterError.onError*
   on, *Show overflow*, then Diagnose. `notes` says no `Flutter.Error` arrived and no framework error
   is in logcat, and names a replaced `FlutterError.onError` (Crashlytics, Sentry).
5. **Rebuild window.** With nothing moving on screen, call `android_get_recomposition_counts`
   with `durationSeconds: 5`: the headline says `(Flutter widget rebuilds): 0 frame(s)` — the
   whole-tree rebuild the switch forces is not counted. Then *Frames and rebuilds* → start the
   rebuild storm (`frames_rebuild_storm`) and record again: the storm's widget is at the top with
   its `lib/fixtures/frames.dart` line, and "Spock switched it on for the recording and off
   again".
   - Turn on the IDE's rebuild counts (Flutter Inspector → *Show widget rebuild information*) and
     record: "it was on already …, so Spock left it on", and the IDE's counts keep running.
   - Turn the IDE's counts off, start a 30 s recording, and turn them on while it runs: "something
     else (the IDE's rebuild counts or DevTools) switched it during the recording, so Spock left it
     as that set it", and the IDE's counts keep running afterwards.
   - Start a 30 s recording and select another app in Spock while it runs (here the `.second` copy,
     not running): the recording keeps measuring the app it started on, since selecting another app
     does not close that app's session, and ends with "Spock switched it on for the recording and
     off again"; the flag reads off afterwards (checked 2026-10-10, 3.22.2). It ends early with
     "The Flutter session ended during the recording, so it stopped early." only when that session
     really ends (`flutter run` quits, the app dies).
   - Start a 30 s recording and press `R` (hot restart) in `flutter run`: it ends early with "the
     app restarted during the recording … counts stop at the restart".
   - In the IDE, the UI Inspector's **Recompositions** tab, **Record** with the storm running: rows
     are widget locations with their counts; double-click the storm's row to open
     `lib/fixtures/frames.dart` at its line. The note ends with what became of the tracking flag.
   - In a `--profile` run the call is refused (debug only). With `flutter run` stopped and the
     app started from the launcher, the call records Compose instead and fails with the
     runtime-tracing advice followed by "… ships the Flutter engine; recording its widget rebuilds
     needs a live debug session, and …".
6. **One problem for two layers** (`error_cross_layer`, Android). *Errors and plugin failures* →
   *Dart error from a failing channel call*, then Diagnose. The handler's checked exception is
   logged by DartMessenger, and the app reports the `MissingPluginException` Dart gets back to
   Flutter. Expect **one** `flutterCrossLayer` problem: "In Dart and on Android, 0.2 s apart —
   Dart: Exception caught by spock sample: MissingPluginException(…); Android: DartMessenger:
   Uncaught exception in binary message listener …", the two about 0.2 s apart (a later press can
   read 0.0 s). Its `parts` hold both, and neither is listed on its own. Press it again a minute later and Diagnose: still one problem, now with `count: 2`.
   *Checked exception in a channel handler* alone gives the native problem only, with no Dart error
   to pair.
7. **No merge across a long-lived error.** Restart the app. *Show overflow* (a Dart error, debug
   build), wait two minutes, press *Checked exception in a channel handler* (Android only), wait
   two more minutes, *Hide overflow* and *Show overflow* again, then Diagnose. The overflow is one
   group seen at the start and the end; the Android failure in the middle stays a problem of its
   own, with **no** `flutterCrossLayer` problem and no `crossLayer` on the group.

## A second copy (two application IDs)

Two flavors of one app share the code and the pubspec package but not the application ID. To get
that without flavors, the Gradle property `spockAppIdSuffix` appends a suffix to the ID and names
the copy in its launcher label. Without it, `flutter run` builds the usual app.
`--android-project-arg` (`-P`) hands a Gradle property to the build, in Flutter 3.22.2 and 3.47.5
alike:

```bash
flutter run                                                    # spock.adb.spock_flutter_sample
flutter run --android-project-arg spockAppIdSuffix=.second     # spock.adb.spock_flutter_sample.second
flutter build apk --debug --android-project-arg spockAppIdSuffix=.second
```

The second shows as *Spock Flutter Sample (.second)*. Both copies answer `spockflutter://` links,
so Android asks which one opens a deep link. The shell push above names the package: use the
suffixed ID for the second copy. Both builds write the same `app-debug.apk`, so install one before
building the other.

## Checks

```bash
flutter analyze
flutter test
```

Device checklist (manual): launch on an Android emulator and an iOS simulator; open every hub
entry; open both deep links; write and read back storage.

## VM Service probe

`tool/vm_service_probe.dart` is the spike probe behind `docs/FLUTTER-SPIKE.md`; it is not part of
the app. With the app running under `flutter run`, pass it the `ws://` URI that `flutter run --machine`
reports as `app.debugPort` → `wsUri` (the DDS URI):

```bash
dart run tool/vm_service_probe.dart ws://127.0.0.1:<port>/<token>=/ws [listen-seconds]
```

It lists every Flutter isolate (skipping paused ones), the extensions Spock relies on, the DDS
version (or "direct VM, no DDS"), and counts the events it hears, split into replayed history
(before it connected) and live ones. It never prints the token. `dart analyze tool/` checks it.

## Navigation checks

**Checked on device** (Android 14 emulator) on Flutter 3.22.2 and 3.47.5, every row below (#169). Still unchecked: hot restart, switching the selected app during a read, transition retries.
Run with `flutter run`, select this app in Spock, and wait for its existing Flutter session.
Open **Navigation** from the hub. Let transitions settle before checking Home → This screen.

| Action | Expected Spock result |
|---|---|
| Push /nav/a, then /nav/a/b | Home KEYED current `/nav/a/b`; App back stack → Flutter routes EXACT: `/`, `/navigation`, `/nav/a`, `/nav/a/b`, root/current marked, under the Activities section. Back removes the top route. |
| Show dialog | Current `DialogRoute (unnamed)`; no path invented, exact check includes it. Dismiss restores `/navigation`. |
| Nested Navigator WITH key | Unavailable: several keyed navigators; no guessed visible child. |
| Nested Navigator WITHOUT key | Initially Home KEYED `/nav/unkeyed`; click check unavailable for an unkeyed nested navigator. Home remains unavailable until a successful check or new isolate. Leave the nested screen and check again. |
| Navigator.pages | Replaces the sample root with one keyed Navigator: `/pages/list`, `/pages/detail`; exact check, names read from Page.name in route settings. Remove/add detail and refresh Home (declarative changes need not emit Flutter.Navigation). Restart sample to return to hub. |

For an unnamed Page, expect its route class rather than a fabricated path. During animation expect
unavailable “navigation in progress”, followed by a bounded retry. Each check opens from **App
back stack**: one popup, Activities then Flutter routes, the routes never under an activity. Its
heap check briefly pauses the app; the popup reports its round-trip duration, not an exact
measured pause. Also check hot restart, switch selected apps/devices during a slow read, hide
stack actions in settings, and select a non-Flutter app: App back stack must be the plain
activity list, with no Flutter section and no pause.
