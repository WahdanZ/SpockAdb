# Flutter spike (P2) — findings

Status: in progress · 2026-10-01 · Flutter 3.22.2 / Dart 3.4.3 · Android 14 emulator.
Plan: [PLAN-FLUTTER.md](PLAN-FLUTTER.md), phase P2. Probe: `sample/flutter_app/tool/vm_service_probe.dart`
(`dart run tool/vm_service_probe.dart <ws-uri> [seconds]` from `sample/flutter_app`).

## Confirmed

| # | Question | Result | Plan impact |
|---|---|---|---|
| S1 | Can a second client use the VM Service through DDS while `flutter run` is attached? | **Yes.** `flutter run --machine` reports `app.debugPort` → `wsUri` `ws://127.0.0.1:<port>/<token>=/ws` on the **host**. The probe connected (protocol 4.15), listed the UI isolate and called extensions; `flutter run` kept working. | P4 discovery order holds: DDS URI first [FR1]. |
| S2 | Which inspector entry point exists? | `ext.flutter.inspector.getRootWidgetSummaryTree` **yes**; `getRootWidgetTree` **no** on 3.22. | Version-gate by `extensionRPCs` [FR11]. |
| S3 | Are structured errors on by default? | **Yes**: `structuredErrors` returns `enabled: true` with no client having set it. | P4 never toggles it; it only listens for `Flutter.Error` [FR10]. |
| S4 | Is HTTP profiling on by default? | **No**: `httpEnableTimelineLogging` is `false`. `ext.dart.io.getHttpProfile` exists. | P4 must enable it on connect and switch it off on disconnect [FR10]. |
| S5 | How does hot reload/restart reach a second client? | `Service` stream announces `s0.reloadSources`, `s0.hotRestart`, `s0.flutterVersion`, `s0.compileExpression`, `s0.flutterMemoryInfo`, `s0.flutterGetSkSL`. | Call the registered services; no `flutter attach --machine` [FR6]. |
| S6 | Which `Extension` events arrive with no interaction? | `Flutter.FrameworkInitialization`, `Flutter.FirstFrame`, `Flutter.ServiceExtensionStateChanged`, `Flutter.Frame` (one per frame drawn). | `Flutter.Frame` is the frame source for P5 jank. |
| S7 | Do framework errors reach logcat in debug? | **No** (seen in P0): overflow, `build()` and gesture errors go only to `Flutter.Error`. | Diagnose says so for debug builds (P1, done). |
| S8 | Is an exception thrown in a platform channel handler a crash on Android? | **No**: the embedding catches it and replies `PlatformException(error, …)`; `MethodChannel#…` logs "Failed to handle method call". | Classified as a plugin failure, not a crash (P1, done). |

## Pending

| # | Question | Why not yet |
|---|---|---|
| S9 | Is the **direct** device URI (logcat `Dart VM service is listening on …` + `adb forward`) refused while DDS owns the VM? | Run interrupted; repeat with the emulator idle. |
| S10 | **Order B**: Spock connects to the direct URI first, then `flutter attach` — does the tool fail with "Existing VM service clients prevent DDS from taking control"? | Same. |
| S11 | Do `Flutter.Error`, `Flutter.Navigation` and `Flutter.RebuiltWidgets` arrive, and with what payload? | The emulator was too loaded (Choreographer skipped 220 frames, `adb` went offline) for the fixture taps to land. |
| S12 | DTD discovery: does the Dart plugin's `DartToolingDaemonService` list running apps (`ConnectedApp` service)? | The `ConnectedApp` service postdates Dart 3.4 (dart-lang/sdk#60540). Needs a newer Flutter SDK installed via fvm (~2 GB) and Android Studio with the Flutter plugin. |
| S13 | `simctl` behaviour (`push`, `privacy`, `get_app_container`, `io screenshot`, `ui appearance`, `location`) | The iOS 26 simulator runtime is still downloading. |

## Notes for the gates

- Run device spikes with no Gradle build in parallel: an overloaded emulator drops `adb` and backgrounds the app, which looks like an app bug.
- `flutter run` prints `app.debugPort` once; P4's "pasted URI" path should accept exactly that `wsUri` form.
