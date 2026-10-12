# Spock Sample

A small Android app for trying every Spock ADB feature against something known. It is a separate
Gradle build: the plugin's build, tests and Detekt never see it.

```bash
./gradlew -p sample :app:installDebug
```

Then check that the status bar names **spock.adb.sample** (click it to choose it if not). Each screen of the app notes the
feature it is for.

For Flutter apps there is a second fixture app in [`flutter_app/`](flutter_app/README.md).

| Plugin feature                                               | Where in the sample                  | What to look for                                                                                                                                                            |
|--------------------------------------------------------------|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Current Activity, Activity Stack                             | *Activity stack*                     | Each push adds a numbered `StackActivity`; *Separate task* adds a second task                                                                                               |
| Current Fragment, fragment back stack                        | *Fragments*                          | Navigation host: Home → List → Detail, with `ChildFragment` nested in Detail                                                                                                |
| Open Deep Link                                               | *Deep links*                         | `spocksample://open/item/42?ref=spock`, `https://sample.spock.adb/item/7`, `spocksample://nav/detail/99` (into the fragment stack). `spocksample://nowhere` matches nothing |
| Grant / Revoke permissions, won't ask again, Ask again       | *Permissions*                        | Camera, location, contacts, microphone, notifications, with live state. *Request CAMERA only*: deny twice and Manage… shows CAMERA as won't ask again (answered in a few ms, no prompt); right-click → *Ask again* and the prompt returns with the app's data intact |
| Process death, Don't keep activities, Restart, Force stop    | *Process death*                      | Three counters kept three ways, plus the pid                                                                                                                                |
| HTTP proxy, Wi-Fi / mobile data                              | *Network*                            | HTTP and HTTPS requests through the platform stack, active transport, proxy in use                                                                                          |
| Developer options animation scales, `android_set_animations` | *Animations*                     | The three scales as `Settings.Global` reports them (unset shows "not set (1×)"), `ValueAnimator.areAnimatorsEnabled()`, and a 1 s animation's measured length: about 0 ms with scale 0, about 1000 ms at 1× |
| Storage tab, Clear Cache vs Clear Data                       | *Storage*                            | `shared_prefs/settings.xml` and `datastore/user_prefs.preferences_pb` with every value type, cache files, and a file Clear Cache must keep                                  |
| Spock Logcat, redaction, Assistant hand-off                    | *Logcat*                             | Every level, a 200-line burst, a stack trace, secrets, a crash, an ANR                                                                                                      |
| Emulator noise ranked last (`LogNoise`), `android_get_debug_context` | *Logcat* → *Noise ranking* | Press *Log emulator noise ×80*, then *Log one app error*, then call `android_get_debug_context {"include":["logs"]}`: `p1` is `SpockSample: Checkout failed: …`, and the `mapper.ranchu`, `WindowOnBackDispatcher` (two lines) and `OpenGLRenderer` problems come after it with `"emulatorNoise": true` and their counts (50, 10, 10, 10); `logs.emulatorNoise` is 80. The noise is logged from the sample's own process with the platform's tags and messages, which is where a real emulator logs these lines too: the mapper and renderer run on the app's RenderThread, the back dispatcher on its UI thread |
| `android_get_debug_context`, Assistant *Attach debugging context* | *Debug context* | An HTTP 500 in OkHttp's format, a 404, an unknown host, one error 25 times, a bearer token, a crash, an unlabelled button — each should be one line of `likelyProblems`, with no token and no query string |
| Spock Screen › Diagnose, *Diagnose Current Screen* action, `android_diagnose_current_screen` | *Diagnose* | DiagnoseActivity above MainActivity in the stack, PaymentFragment under fragments, CAMERA denied after *Don't allow*, the HTTP 500 and the repeated error in likely problems, one unlabelled button; *Open Fragment*, *Inspect UI* and *View Related Logs* each land on this screen's data |
| UI Inspector, accessibility audit, element MCP tools         | *UI Inspector — Compose* / *— Views* | Test tags (switchable `testTagsAsResourceId`), a text field, a checkbox, a list, two deliberate accessibility faults, and one target per way Jump to Source finds code (open `sample/` in the IDE; `docs/COMPOSE-SUPPORT-PLAN.md`, *Source navigation checks*)                                                    |
| Element refusals, scroll containers, audit, capture failures and summaries, what is in view, waits, actions with an expected result | *UI Inspector — Compose reliability fixtures* | Seven tabs, with a *Last tap* line above them all. **Taps**: repeated, look-alike and disabled targets. **Scroll**: a feed of carousels beside two unrelated lists. **Audit**: exactly two faults. **Busy**: a UI that never goes idle, and things that appear, disappear, become enabled or switch on 3 s after a button. **Hybrid**: switchable test tags and a View inside Compose. **Window**: the app's own view of rotation, density and window size, a dialog, and buttons whose result appears, is already there, or never comes, each press counted. **Fold**: a short column with one row cut by its edge and one below the fold. Calls and expected answers: `docs/COMPOSE-SUPPORT-PLAN.md`, *Device checks*, also behind each fixture card's info button |
| Scheduler tab, `android_run_job_now`                         | *Background work*                    | WorkManager one-off with constraints, periodic, and a flaky worker that backs off; JobScheduler jobs 4242 and 4343; exact, inexact and repeating alarms; a log of what ran  |
| Device conditions: Doze, standby bucket, battery            | *Background work* → *What the app sees* | Doze, the bucket and the battery as the app's own APIs report them. The app asks for `SCHEDULE_EXACT_ALARM` and starts without it, so its bucket can move. *Allow exact alarms* grants it, and then Android keeps the app at Working set or higher |
| `android_get_ui_tree` with `meaningfulOnly`, `center=[x,y]` | *Spock Screen › UI Tree — Recomposition counts* | With `meaningfulOnly: true` the `recomp_ticking` switch ends in `center=[x,y]` and is immediately followed by `TextView text="Ticking"`, which has no centre; `interactiveOnly: true` lists the switch alone |
| `android_run_recipe`, `android_list_recipes`               | *Debug Recipes*                      | One fixture per built-in recipe: a `spocksample://recipe/42` deep link, a count kept only in saved state, a note kept in SharedPreferences (also shown on the hub, where a restart lands), and JobScheduler job 4545 that waits an hour unless forced. Parameters for each recipe are in `RecipesActivity`'s KDoc |
| MCP output bounds (#182): `android_read_app_storage`, `android_get_package_info`, `android_get_device_conditions`, `android_take_screenshot`, `android_get_network_info` | *Storage* → *Write big + secret prefs* (any screen for the rest) | `android_read_app_storage {"file":"shared_prefs/session.xml"}` is about 1 KB: `cached_feed` ends `…[truncated, 39755 chars]`, `auth_token`, `refreshToken` and `user_password` read `<redacted, N chars>`, `session_active` (a boolean) is shown, and the header says 3 redacted and 1 cut. `{"key":"token"}` lists only the two token keys. `{"showSecrets":true}` opens the Allow once / Deny dialog naming the three keys; Deny returns the redacted answer, Allow shows the values. `{"maxValueChars":10}` is refused (`Argument 'maxValueChars' must be between 50 and 40000, got 10.`). `android_get_package_info {"packageName":"spock.adb.sample"}` is under 1 KB: version `1.2.0-sample (7)`, min 24 / target 36, launcher `spock.adb.sample/.MainActivity`, the runtime permissions granted and denied; `{"raw":true}` returns the dump. `android_get_device_conditions` reads `Deep Doze: no (state ACTIVE)`, and `yes (state IDLE)` after `android_force_doze`. `android_take_screenshot {"scale":0.5,"format":"jpeg"}` returns an `image/jpeg` several times smaller than `{}` (3.4x on a plain sample screen; more on a busy one) and the line `Screenshot scaled 0.5 (540x1318 of 1080x2636) — multiply image coordinates by 2 for android_tap.` (sizes for a 1080x2636 emulator) `android_get_network_info` lists interfaces with no `\` in them (`scope host lo valid_lft forever`) |
| Restart with debugger, Input Text, Show taps / layout bounds | any screen                           | The debug build is debuggable; the Views screen has a text field                                                                                                            |

Everything the app does in the background is written to Logcat under the tag `SpockSample`.

## Command Center autocomplete

No screen needed: with the sample installed, type `pm clear spock.adb.sample` or
`am force-stop spock.` in the **Shell** tab and **spock.adb.sample** is offered where the package
goes, with its usage underneath. The installed packages are read once per device, so install
anything new before selecting the device, or reconnect it (`adb reconnect`) to read them again.

To check that a finished word runs rather than completes, install a second package whose name
extends the sample's: add `applicationIdSuffix = ".debug"` to the `debug` build type in
`app/build.gradle.kts`, run `installDebug` again, and revert the line (both apps stay installed).
Then `am force-stop spock.adb.sample` + **Enter** must run the command, although
`spock.adb.sample.debug` is still listed; pressing **↓** first and then **Enter** inserts it.
Uninstall it afterwards with `adb uninstall spock.adb.sample.debug`.

## MCP protocol checks

No screen needed: these check the MCP server itself (#182, schema and protocol). Start the server
in the sandbox IDE, then pipe JSON-RPC lines into the stdio launcher, as in the issue's probe:

```bash
JAVA="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
JAR="<AS config>/plugins/com.wahdan.spockAdb/lib/com.wahdan.spockAdb-4.1.0.jar"
PROPS="<AS config>/spock-adb/mcp-stdio.properties"

{ echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"android_get_logcat","arguments":{"maxLines":-5}}}'
  echo '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"flutter_app_status","arguments":{"packageName":"spock.adb.sample"}}}'
  sleep 6
} | "$JAVA" -cp "$JAR" spock.adb.mcp.stdio.SpockAdbStdioLauncher "$PROPS"
```

| Check | Expected |
|---|---|
| Response `1` | `"protocolVersion":"2025-06-18"`, the version asked for. Ask for `2025-03-26` or `2024-11-05` and get that back; ask for `2099-01-01` and get `2025-06-18` |
| `serverInfo.version` in response `1` | The plugin version, `4.1.0` for this build, not `1.0.0` |
| Response `2`, size | Under 44,500 characters on 2025-06-18, and under 41,500 when initialized with `2024-11-05` (save it to a file and `wc -c`) |
| Response `2`: `android_set_battery_level`, `android_unplug_battery`, `android_set_charger`, `android_set_standby_bucket` | `"destructiveHint":true` and `"idempotentHint":true`; calling them still opens no dialog |
| Response `2`: `android_run_recipe` | `"destructiveHint":true` and `"openWorldHint":true` |
| Response `2`: `android_get_debug_context`, `android_diagnose_current_screen`, `flutter_app_status` | An `outputSchema`; no other tool has one. Every tool has a top-level `title` |
| Response `2`: any numeric argument, e.g. `android_get_logcat` `maxLines` | `"minimum":1,"maximum":5000` |
| Response `3` | `"isError":true` with `Argument 'maxLines' must be between 1 and 5000, got -5.` |
| Response `4` | The status JSON as text and the same object under `structuredContent`; initialized with `2025-03-26` there is no `structuredContent` |
