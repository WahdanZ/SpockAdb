# Spock Flutter Sample

A Flutter fixture app for checking Spock ADB on Flutter apps, on Android and the iOS simulator.
It is a separate Flutter project: the plugin's Gradle build, tests and Detekt never see it.
Plan: `docs/PLAN-FLUTTER.md`.

```bash
cd sample/flutter_app
flutter run                 # debug: everything
flutter run --profile       # profile: frame times mean something (Android device/emulator)
```

Package / bundle id: `spock.adb.spock_flutter_sample`. Every control has `Semantics(identifier:)`,
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
| Flutter errors in Diagnose / Timeline | *Layout overflow* | *Show overflow* draws the yellow-black stripe. In a debug build the error goes **only** to the VM Service (`Flutter.Error`), not logcat |
| Log problem detection | *Errors and plugin failures* | One failure per button. In logcat (tag `flutter`): unhandled async error, `MissingPluginException`, `PlatformException(SAMPLE_ERROR)`. *Exception in a channel handler* (Android only): Flutter catches it, logs `Failed to handle method call` (tag `MethodChannel#spock.sample/native`) and the Dart side gets `PlatformException(error, …)`; the app survives. *Native crash* kills the app on both platforms (Android: uncaught exception on the main thread; iOS: `fatalError`). VM Service only (`Flutter.Error`): tap-handler error and the red screen from `build()` |
| Network, HTTP proxy, Wi-Fi toggle | *Network* | 200, 500, 404 and an unknown host through `dart:io` `HttpClient`, each logged as one line |
| Native screens above Flutter, grant / revoke | *Native permission dialogs* | Camera, location and notifications open the system dialog; status refreshes on resume |
| Push messages | *Push messages* | Android: `PushReceiver` stands in for Firebase, stores the message as `flutter.last_push`. iOS: a system banner after notifications are allowed |
| App Storage, Clear Cache vs Clear Data | *Storage* | `shared_prefs/FlutterSharedPreferences.xml` with `flutter.`-prefixed keys of every type, `app_flutter/settings.hive`, `databases/notes.db`, a cache file, and a support file Clear Cache must keep |
| Flutter logcat preset, redaction | *Logs* | `print`, `debugPrint`, `developer.log` (VM Service only), a 200-line burst, a line with a token |
| Jank and rebuild storms (VM Service, later phases) | *Frames and rebuilds* | Slow frames busy-wait 40 ms each; a counter rebuilds every frame |

## Checks

```bash
flutter analyze
flutter test
```

Device checklist (manual): launch on an Android emulator and an iOS simulator; open every hub
entry; open both deep links; write and read back storage.
