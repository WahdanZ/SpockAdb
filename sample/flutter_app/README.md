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

| Spock feature | Where in the sample | What to look for |
|---|---|---|
| UI tree, `android_find_ui_element`, `android_tap_element` | *Login* | Resource-ids `login_email`, `login_password`, `login_submit`, `result`. *Remember me* and *Help* have no identifier on purpose |
| Open Deep Link | *List → detail routes* | `spockflutter://open/item/42?ref=spock` opens *Item 42* with `ref=spock`. `spockflutter://open/nowhere` opens *No such route* |
| Flutter errors in logs / Diagnose / Timeline | *Layout overflow* | *Show overflow* logs "A RenderFlex overflowed by … pixels" |
| Log problem detection | *Errors and plugin failures* | One failure per button: tap-handler error, unhandled async error, `MissingPluginException`, `PlatformException(SAMPLE_ERROR)`, red screen from `build()`, and a native crash that kills the app |
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
