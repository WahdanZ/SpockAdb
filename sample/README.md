# Spock Sample

A small Android app for trying every Spock ADB feature against something known. It is a separate
Gradle build: the plugin's build, tests and Detekt never see it.

```bash
./gradlew -p sample :app:installDebug
```

Then pick **spock.adb.sample** in the tool window header. Each screen of the app notes the
feature it is for.

| Plugin feature                                               | Where in the sample                  | What to look for                                                                                                                                                            |
|--------------------------------------------------------------|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Current Activity, Activity Stack                             | *Activity stack*                     | Each push adds a numbered `StackActivity`; *Separate task* adds a second task                                                                                               |
| Current Fragment, fragment back stack                        | *Fragments*                          | Navigation host: Home → List → Detail, with `ChildFragment` nested in Detail                                                                                                |
| Open Deep Link                                               | *Deep links*                         | `spocksample://open/item/42?ref=spock`, `https://sample.spock.adb/item/7`, `spocksample://nav/detail/99` (into the fragment stack). `spocksample://nowhere` matches nothing |
| Grant / Revoke permissions                                   | *Permissions*                        | Camera, location, contacts, microphone, notifications, with live state                                                                                                      |
| Process death, Don't keep activities, Restart, Force stop    | *Process death*                      | Three counters kept three ways, plus the pid                                                                                                                                |
| HTTP proxy, Wi-Fi / mobile data                              | *Network*                            | HTTP and HTTPS requests through the platform stack, active transport, proxy in use                                                                                          |
| Storage tab, Clear Cache vs Clear Data                       | *Storage*                            | `shared_prefs/settings.xml` and `datastore/user_prefs.preferences_pb` with every value type, cache files, and a file Clear Cache must keep                                  |
| Logcat tab, redaction, Assistant hand-off                    | *Logcat*                             | Every level, a 200-line burst, a stack trace, secrets, a crash, an ANR                                                                                                      |
| UI Inspector, accessibility audit, element MCP tools         | *UI Inspector — Compose* / *— Views* | Test tags (switchable `testTagsAsResourceId`), a text field, a checkbox, a list, and two deliberate accessibility faults                                                    |
| Element refusals, scroll containers, audit, capture failures | *UI Inspector — Compose reliability fixtures* | One tab per fixture group: repeated and disabled targets, a feed of carousels beside two unrelated lists, audit faults, a UI that never goes idle, a hybrid screen, a short column with one row cut by its edge and one below the fold, things that appear, disappear, become enabled or switch on 3 s after a button, for waits, and buttons whose result appears, is already there, or never comes, each press counted, for actions with an expected result. Calls and expected answers: `docs/COMPOSE-SUPPORT-PLAN.md`, *Device checks*, also behind each fixture card's info button |
| Background Work tab, `android_run_job_now`                   | *Background work*                    | WorkManager one-off with constraints, periodic, and a flaky worker that backs off; JobScheduler jobs 4242 and 4343; exact, inexact and repeating alarms; a log of what ran  |
| Device conditions: Doze, standby bucket, battery            | *Background work* → *What the app sees* | Doze, the bucket and the battery as the app's own APIs report them. The app asks for `SCHEDULE_EXACT_ALARM` and starts without it, so its bucket can move. *Allow exact alarms* grants it, and then Android keeps the app at Working set or higher |
| Restart with debugger, Input Text, Show taps / layout bounds | any screen                           | The debug build is debuggable; the Views screen has a text field                                                                                                            |

Everything the app does in the background is written to Logcat under the tag `SpockSample`.
