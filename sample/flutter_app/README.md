# Spock Flutter Sample

A Flutter fixture app for checking Spock ADB on Flutter apps, on Android and the iOS simulator.
It is a separate Flutter project: the plugin's Gradle build, tests and Detekt never see it.
Plan: `docs/PLAN-FLUTTER.md`.

```bash
cd sample/flutter_app
flutter run                 # debug: everything
flutter run --profile       # profile: frame times mean something (Android device/emulator)
```

The Android build uses Gradle 7.6.3 and AGP 7.3.0. If it fails with an unsupported Java version
(JDK 21 or newer), point Flutter at a JDK 17: `flutter config --jdk-dir <path to JDK 17>`.

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
| Log problem detection | *Errors and plugin failures* | One failure per button. In logcat (tag `flutter`): unhandled async error, `MissingPluginException`, `PlatformException(SAMPLE_ERROR)`. *Exception in a channel handler* (Android only): Flutter catches it, logs `Failed to handle method call` (tag `MethodChannel#spock.sample/native`) and the Dart side gets `PlatformException(error, …)`; the app survives. *Checked exception in a channel handler* (`error_channel_checked`, Android only): the `IOException` gets past `MethodChannel`; `DartMessenger` logs `Uncaught exception in binary message listener` and replies empty, so the Dart side gets a `MissingPluginException` although the handler exists; the app survives. *TODO() in a channel handler* (`error_channel_todo`, Android only): `NotImplementedError` is a `java.lang.Error`, which nothing catches, so the app crashes. On iOS both answer not-implemented (`MissingPluginException`). *Native crash* kills the app on both platforms (Android: uncaught exception on the main thread; iOS: `fatalError`). VM Service only (`Flutter.Error`, while structured errors are on): tap-handler error and the red screen from `build()`. *Custom FlutterError.onError* (`error_custom_on_error`) stands in for a crash reporter: while it is on, framework errors (this screen and *Layout overflow*) post no `Flutter.Error` and reach logcat only as one `SPOCK_SAMPLE custom FlutterError.onError: …` line; switch it off to get Flutter's handler back |
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
   the app; on 3.22 (`logcat-pid`) up to a minute after the VM announces itself, because while
   `flutter run` holds a forward to the VM Spock does not connect to the VM itself (it would keep
   DDS out). `flutter run` itself must keep working: hot reload with `r`. idea.log shows every
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
5. **A session's end.** With both copies running (item 8), select the other one: `Flutter session
   ended: … — Spock connected again, to a newer session` and `Spock switched HTTP recording back off
   for …`, then the new session's row. Quit `flutter run` with `q`: the end row says the connection
   was lost, as a warning.
6. **The Settings switch.** Turn *Record Flutter HTTP traffic automatically* off, restart the app: no
   "turned on" row, and Diagnose's `flutter.identity.httpRecording` says the setting is off.
7. **No debugger session.** Start the app from the launcher (not `flutter run`). After about 10 s,
   Diagnose's `flutter.attach` says the app is running without a debugger session; Spock never stays
   on its VM, so a `flutter attach` afterwards works. On 3.47.5 the next Diagnose finds it through
   the Dart Tooling Daemon and connects; on 3.22 the "no DDS" verdict holds for that process until
   the app restarts.
8. **Two copies, two emulators.** The second copy below, both running: select each in turn — the
   session row names the selected one's pid. Two emulators of one image: the session follows the
   selected emulator's serial.

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
