---
name: spock-adb
description: Debug Android apps on a connected device or emulator through the Spock ADB MCP server (tools named android_*). Use when asked to investigate a UI bug, a state bug, a crash or ANR, process-death survival, background work (WorkManager, JobScheduler, alarms, Doze), a deep link, or an accessibility problem in an Android app — or whenever android_* tools are available and the task involves a running Android app.
---

# Spock ADB debugging workflows

Spock ADB is an IntelliJ / Android Studio plugin that exposes a device to agents as typed MCP
tools, all named `android_*`. This skill is the order to call them in. The tool descriptions
say what each tool does; this says which one to reach for first, and what to check before and
after a change.

## Four rules

1. **Start with the summary, not the raw data.** `android_get_debug_context` returns a bounded,
   ranked `likelyProblems` list plus one short section each for the screen, app, log, UI,
   background work and device conditions — all describing the same moment. Read
   `likelyProblems` first. Each section's `more` entry names the exact call that returns its
   raw data; fetch that only when the summary points there. Use
   `android_diagnose_current_screen` instead when you also need to *see* the screen: same
   report, every section, plus a screenshot.
2. **Narrow to the app.** Pass `packageName` (or rely on the open project's app, which is the
   default) so logcat, storage, jobs and alarms are the app's, not the whole device's. Read the
   log at `minLevel: "W"` or `"E"` before reading it at `"V"`. Pass `packageName: ""` only when
   you mean the whole device.
3. **Safe before destructive.** Observe with read-only tools, act with safe actions, and use a
   destructive tool only when no safe one reproduces the problem. A destructive call stops and
   asks the developer; say in your message why you need it before you make it.
4. **Recapture after every change.** Anything that mutates state — a tap, a deep link, a
   permission, a preference, a device condition — is followed by a fresh read of the thing it
   was meant to change (`android_wait_for_element`, an `android_assert_*`, or
   `android_get_debug_context` again). Never report an outcome you inferred rather than read.

## Before the first call

- More than one device attached: `android_list_devices`, then `android_select_device`. Every
  device tool also takes `deviceSerial`.
- More than one project open in the IDE: `android_select_project`. With one open, skip it.
- A tool that answers "disabled in Settings" was switched off by the developer. It did not run
  and will not run on retry — tell the developer and use another tool.

## Safety levels

Every tool has a fixed level. A client cannot change it.

| Level | What happens | Examples |
|---|---|---|
| Read-only | Runs automatically; changes nothing | `android_get_debug_context`, `android_get_logcat`, `android_get_ui_tree`, `android_assert_text` |
| Safe action | Runs automatically; changes only what a developer routinely does by hand and can undo | `android_launch_app`, `android_tap_element`, `android_open_deep_link`, `android_grant_permission`, `android_set_battery_level` |
| Destructive | **Stops and asks the developer, per call.** Denied by default: no answer, a closed IDE or a timeout all mean no | the list below |

The destructive tools, all of them:

<!-- destructive-tools -->
- `android_clear_app_data` — deletes the app's preferences, databases and caches
- `android_uninstall_app` — removes the app
- `android_revoke_permission` — revoking a permission the app is using usually kills its process
- `android_set_app_preference` — rewrites one SharedPreferences / DataStore key; force-stops the app
- `android_delete_app_preference` — removes one key; force-stops the app
- `android_set_http_proxy` — routes all device traffic through a host, and survives a reboot
- `android_force_doze` — defers every app on the device until reset
- `android_run_adb_command` — arbitrary shell; requires a `reason` the developer reads
<!-- /destructive-tools -->

How to behave around them:

- **Say why first.** One sentence in your reply before the call: what it will destroy and what
  it will prove. The developer is reading a dialog, not your plan.
- **A denial is an answer.** Do not retry the same call, rephrase it, or reach the same effect
  through `android_run_adb_command`. Ask the developer, or continue without it.
- **Prefer the typed tool.** `android_run_adb_command` is for what no other tool expresses. Its
  `reason` must say which typed tool you considered and why it does not fit.
- **Leave the device as you found it.** Undo what you changed: `android_clear_http_proxy` after
  `android_set_http_proxy`, `android_reset_device_conditions` after Doze, buckets or battery
  changes. `android_get_device_conditions` lists what Spock changed and has not reset.

## Finding and acting on UI elements

- Find by semantics, not pixels: `android_find_ui_element` with `testTag`, then `text`, then
  `contentDescription`. `android_get_ui_tree` with `interactiveOnly: true` when you do not know
  what is on screen.
- Act with the element tools — `android_tap_element`, `android_long_press_element`,
  `android_scroll_to_element`, `android_input_text_into_element`. They refuse, and say why, when a
  selector matches several elements or a disabled one. Refine the selector (for example with
  `containerTag`); do not fall back to coordinates to get around a refusal.
- `android_tap`, `android_swipe` and `android_input_text` are the coordinate fallback, for
  surfaces with no semantics (a game view, a map, a WebView without accessibility).
- Give an action its expected result — `expectText`, `expectTestTag` or
  `expectContentDescription`, with `expectUntil` — and it reports VERIFIED, NOT OBSERVED or
  INCONCLUSIVE instead of leaving you to guess.
- Wait with `android_wait_for_element` (`until`: `visible`, `gone`, `enabled`, `checked`, …)
  rather than sleeping and re-reading.

## Playbooks

Each playbook is a default order. Stop as soon as the evidence answers the question.

### 1. UI bug — "the screen looks wrong / the button does nothing"

1. `android_diagnose_current_screen` — the screen as an image and as data, in one call. Check
   `screen.activity` is the screen the developer means; if another app is in front, the bug
   report is about the wrong screen.
2. `android_find_ui_element` for the element in question. Not found, found twice, or found
   disabled are each a different bug — report which.
3. Reproduce: `android_tap_element` (or the matching element action) with an expected result.
4. `android_assert_visible`, `android_assert_enabled` or `android_assert_text` to state the
   outcome as a fact.
5. Nothing visible happened: `android_get_logcat` with `minLevel: "W"` for what the tap
   triggered.

### 2. State bug — "the value is wrong / it forgot my setting"

1. `android_get_debug_context` — rule out a crash or failing request before looking at state.
2. `android_list_app_storage`, then `android_read_app_storage` for the file that holds the value.
   Compare what is stored with what is shown (`android_assert_text`).
3. Stored right, shown wrong → a UI or loading bug; go to playbook 1. Stored wrong → find what
   wrote it: `android_get_logcat` around the action that changed it.
4. To test a hypothesis, set the value directly with `android_set_app_preference`
   (**destructive**: it force-stops the app), then `android_launch_app` and re-read both the
   storage and the screen.
5. Restore the original value the same way, or tell the developer you left it changed.

### 3. Crash or ANR

1. `android_get_debug_context`. A crash or ANR ranks first in `likelyProblems`, attributed even
   when the process has already died.
2. Follow its `more.logs` reference — typically `android_get_logcat` with `minLevel: "E"` — for
   the full stack trace or ANR block. Quote the first frame in the app's own package.
3. `android_get_processes` with the package as `filter`: is the process gone, or restarted with
   a new pid?
4. Reproduce from a known state: `android_restart_app`, repeat the steps with element actions,
   then `android_get_debug_context` again to confirm the same problem, not a new one.
5. Only if the crash needs empty state to reproduce: `android_clear_app_data` (**destructive**).

### 4. Process-death testing — "does the screen survive being killed in the background?"

Force-stopping is **not** process death: `android_stop_app` and `android_restart_app` also
discard the saved instance state that process death keeps. Use them for cold starts only.

1. Put the app in a known state on the screen under test, then read it —
   `android_assert_text` on the values that must survive.
2. `android_press_key` with `key: "HOME"`, so the app is in the background.
3. `android_get_processes` with the package as `filter` — note the pid.
4. Kill the process: `android_run_adb_command` with `command: "am kill <package>"` and a
   `reason` saying no typed tool kills a background process while keeping its task
   (**destructive**, the developer approves it). `am kill` only kills a background app, which is
   why step 2 comes first.
5. `android_get_processes` again: the pid must be gone.
6. `android_launch_app` — the task comes back and Android recreates the screen from saved state.
7. `android_get_processes` shows a new pid; `android_assert_text` on the same values as step 1.
   Whatever changed is what the screen does not save.

### 5. Background work — "the job never runs / the alarm is late"

1. `android_get_scheduled_jobs` — each job's constraints and which are unsatisfied right now,
   its backoff and failure count. `android_get_pending_alarms` for alarms.
2. `android_get_device_conditions` — Doze, the app's standby bucket, battery and charging.
3. Unsatisfied constraint → change the condition it waits for, not the code:
   `android_set_charger`, `android_set_battery_level`, `android_set_standby_bucket`. Re-read
   with `android_get_scheduled_jobs` after each.
4. `android_run_job_now` with the `jobId` to run it regardless of constraints, then
   `android_get_logcat` for what the worker did. For WorkManager, "started" means JobScheduler
   started it; periodic or backed-off work may be re-deferred without running the Worker —
   confirm from the log.
5. Reproduce a Doze bug with `android_force_doze` (**destructive**) only when the lighter
   conditions did not.
6. Always finish with `android_reset_device_conditions`, then `android_get_device_conditions` to
   confirm `changedBySpock` is empty.

### 6. Deep-link verification — "the link opens the wrong screen / nothing"

1. `android_get_package_info` — confirm the app is installed and which version.
2. `android_open_deep_link` with the `uri` (and `packageName` to rule out another app claiming
   it).
3. `android_get_current_activity` — did the expected activity open? For a navigation-graph app,
   `android_get_current_fragments` too.
4. `android_assert_text` or `android_find_ui_element` on content that proves the arguments
   arrived (the item id in the URI, for example).
5. Wrong screen or a chooser → `android_get_logcat` with `minLevel: "W"` for the intent
   resolution. Check one link that should match nothing, to confirm it fails cleanly.

### 7. Accessibility investigation

1. `android_accessibility_audit` — each finding with its element and a fix.
2. `android_get_ui_tree` with `interactiveOnly: true` — what a screen reader can reach, and what
   each element announces (text, content description, role, state).
3. For each finding, `android_find_ui_element` to identify the element in source terms (its
   test tag) so the developer can fix the right composable or view.
4. After a fix is deployed: re-run `android_accessibility_audit` and compare the counts. A
   finding that moved to another element is not fixed.

## Reporting

End with what you observed, from which call, and what is still unverified. Name any state you
changed and did not restore (a preference, a proxy, a device condition). Quote log lines and
selectors exactly; do not paraphrase a stack trace.
