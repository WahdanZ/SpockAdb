# Android MCP server

Spock ADB can expose the connected Android device to MCP-compatible AI agents — Claude Code,
Claude Desktop, Cursor, or anything else that speaks the Model Context Protocol.

The goal is not "an AI can run adb". It is: **an agent gets safe, structured, auditable
access to a real device**, and cannot destroy state without you agreeing to that specific
action.

> **Off by default.** While the server runs, any local process holding the session token can
> drive your device. Start it deliberately, from
> `Tools → SpockAdb → Spock: Start MCP Server for AI Agents`.

## The MCP Server panel

`Tools → SpockAdb → Open MCP Server Panel`, or the **MCP Server** tab in the tool window.

It shows what is actually true rather than a mock-up of it:

- **Status** — running or stopped, the transports actually accepting connections
  (`HTTP (127.0.0.1:<port>)` and, when it bound, `stdio (unix:<path>)`), and the tool count.
  Start / Stop / Restart / Copy Config / Settings.
- **Tools tab** — the full catalogue of what an agent can do to your device, grouped by
  safety level with destructive first, searchable, and filterable to destructive only.
  Selecting a tool shows its description and argument schema. Available whether or not the
  server is running, so you can review exactly what you are exposing *before* you start it.
- **Activity monitor** — every tool call as it happens, with a safety marker
  (`✓` read-only, `⚡` action, `⚠` destructive), success or failure, and duration.
- **Request details** — select a call to see arguments, result, client, target device,
  duration, and for destructive calls whether you approved or denied it. Copy request or
  response.
- **History** — searchable across tool name, arguments and result; filterable by tool and by
  outcome. Bounded, and the size is configurable in `Settings → Tools → Spock ADB`.

### What the transport can and cannot tell you

The panel names the connected client only when the client identifies itself in `initialize`,
and says so plainly when it has not:

> No client has identified itself yet. On either transport, a client is only known once it
> calls initialize.

There is no per-client presence list. Plain HTTP POST has no connection to be "online" on at
all, and a stdio session is a connection but carries no identity before `initialize`, so a list
of green and grey dots next to client names would be invented rather than observed.
**What a transport does not expose is reported as unknown, not guessed.**

## Quick start

1. `Tools → SpockAdb → Spock: Start MCP Server for AI Agents`
2. `Tools → SpockAdb → Spock: Copy MCP Client Configuration (stdio)` — or `(HTTP)` if your
   client does not spawn processes
3. Paste into your MCP client's config

Both transports are started together and serve the same tools. Pick whichever your client
supports; **prefer stdio**, because its configuration contains no credential.

### stdio

```json
{
  "mcpServers": {
    "spock-adb": {
      "command": "<the IDE's java>",
      "args": [
        "-cp", "<the Spock ADB plugin jar>",
        "spock.adb.mcp.stdio.SpockAdbStdioLauncher",
        "<IDE config>/spock-adb/mcp-stdio.properties"
      ]
    }
  }
}
```

There is no token in it. The client is pointed at an endpoint descriptor, and the token lives
in that file with `600` permissions — so this config can be committed, pasted into a chat or
attached to a bug report without leaking anything. The `java` named is the IDE's own, so the
launcher runs on a JDK that is definitely present and new enough.

### HTTP

```json
{
  "mcpServers": {
    "spock-adb": {
      "type": "http",
      "url": "http://127.0.0.1:<port>/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

This one **does** contain a credential for your device. It is generated locally, never leaves
your machine unless you paste it somewhere, and can be rotated.

## Architecture

```
MCP client --+-> McpHttpServer  --+
             |                    |
             +-> McpStdioServer --+->  McpProtocol  ->  ToolRegistry
                                                             |
                                                             v
                                         SpockAdbService / DeviceLister / commands
                                                             |
                                                             v
                                                        ADB -> device
```

The transports meet at `McpProtocol` and share everything below it: one protocol
implementation, one `ToolRegistry`, one safety model, one audit trail. A `tools/call` arriving
over stdio is confirmed, recorded in the activity panel and written to `idea.log` exactly as
the same call over HTTP, because it is the same call.

The MCP layer **owns no ADB logic**. Tools resolve devices through the same
`DebugBridgeProvider` and `DeviceLister` the tool window uses, and reuse the same command
classes. One implementation of every device operation means one set of behaviours, one set
of error messages, and no chance of the UI and the agent path drifting apart.

`ToolRegistry` is deliberately shared: a future in-plugin AI assistant uses the same tool
definitions and the same safety levels rather than a parallel implementation. Two
implementations would drift, and the one that drifted would be the one enforcing safety.

### How stdio reaches a plugin

A client speaking stdio *spawns* its server and talks to that child process's stdin and
stdout. The tools cannot live in that child: they need the running IDE's ADB bridge, its
project model, and its confirmation dialogs. So the child — `SpockAdbStdioLauncher` — is a
**byte relay and nothing else**. It copies bytes between its own stdio and a local stream
endpoint in the IDE, where `McpStdioServer` serves the session against the shared
`McpProtocol`.

The relay is deliberately ignorant. It does not parse JSON-RPC, does not know what a tool is,
and never rewrites anything passing through it, because the wire on both sides is identical:
newline-delimited JSON-RPC, exactly as the MCP stdio spec defines it. A relay that understood
the protocol would be a second implementation of it, and the second one would drift.

It is written in Java with no dependencies, so `java -cp <plugin jar>` is enough to start it.
The plugin does not bundle the Kotlin standard library — it uses the one inside the IDE — so a
Kotlin launcher could not be started that way.

**Stdout is the protocol stream.** One stray line on it corrupts the session, so the launcher
claims the real stdout on its first statement and redirects `System.out` to stderr; nothing
that later prints, in this code or any library, can reach the client. Diagnostics go to
stderr, which MCP clients surface in their logs, and inside the IDE to `idea.log`.

**Every connection presents the token**, on both transports, as one line checked before the
session starts. The filesystem is defence in depth, not a substitute: a Unix domain socket in a
`700` directory is preferred because another user cannot reach it at all, and where `AF_UNIX`
is unavailable — or the config path is too long for `sun_path` — the endpoint falls back to a
loopback TCP port, which any local process can connect to. The token is read from a `600` file
rather than carried in the client config, and is the same one the HTTP transport uses, so
rotating it rotates both.

A connection that opens and then says nothing is closed after ten seconds, and the session pool
is bounded. Without both, anything that could reach the endpoint could hold threads open until
real clients could not get one — which in the TCP fallback means any local process.

**Cancellation.** `notifications/cancelled` interrupts the thread running that request and
suppresses its response, per spec — the client has already stopped waiting. Requests run on a
worker pool rather than the reading thread, precisely so a cancellation arriving behind a slow
tool call can still be read. Stopping the server closes live sessions, which ends the relay
processes rather than stranding them.

### Why a self-hosted HTTP server

Android Studio does **not** ship IntelliJ's built-in web server —
`org.jetbrains.ide.HttpRequestHandler` is absent from the distribution, so the usual
`RestService` route is unavailable. Rather than add Netty or Ktor to a plugin that otherwise
has a single dependency, the transport uses the JDK's own `com.sun.net.httpserver`: no new
dependencies, and identical behaviour in Android Studio and IntelliJ IDEA.

## Safety model

Every tool declares a level, as a property of the tool rather than a flag a client can set.

42 tools, in three levels.

| Level | Behaviour | Tools |
|---|---|---|
| **Read-only** (19) | Runs automatically. Cannot change device or app state. | `android_list_devices`, `android_get_device_info`, `android_list_packages`, `android_get_package_info`, `android_get_current_activity`, `android_get_activity_stack`, `android_get_current_fragments`, `android_get_logcat`, `android_get_processes`, `android_get_battery_info`, `android_get_network_info`, `android_get_debug_context`, `android_take_screenshot`, `android_get_ui_tree`, `android_find_ui_element`, `android_accessibility_audit`, `android_assert_visible`, `android_assert_enabled`, `android_assert_text` |
| **Safe action** (19) | Runs automatically. Changes state only in ways you routinely do by hand and can undo by repeating a normal action. | `android_select_device`, `android_select_project`, `android_launch_app`, `android_stop_app`, `android_restart_app`, `android_grant_permission`, `android_tap_element`, `android_long_press_element`, `android_scroll_to_element`, `android_input_text_into_element`, `android_open_deep_link`, `android_input_text`, `android_tap`, `android_swipe`, `android_press_key`, `android_push_file`, `android_pull_file`, `android_start_screen_recording`, `android_stop_screen_recording` |
| **Destructive** (4) | **Always** asks you first, per call. Never auto-approved. | `android_clear_app_data`, `android_uninstall_app`, `android_revoke_permission`, `android_run_adb_command` |

Rules that hold regardless of what a client asks for:

- A destructive call **cannot** report success without a confirmation. This is enforced by a
  test, not by convention.
- Confirmation **defaults to denied**. An unattended IDE, a disposed project, or a failure to
  show the dialog all mean "no". Nothing is ever waved through by timeout.
- The IDE window is brought forward when a confirmation is needed, because the request came
  from another application and you are probably not looking at it.
- Destructive calls are written to `idea.log`, so "what did the agent do to my device"
  survives a restart.

### The arbitrary command tool

`android_run_adb_command` exists, and is deliberately awkward. Its description tells agents
to prefer a typed tool and to justify why they cannot. It requires a stated `reason`, shown
to you in the confirmation. It has a bounded timeout, capped output, and an audit entry.

A short list of commands is refused *before* you are asked at all — `rm -rf /`, factory
reset, `mkfs`, raw `dd` — so a catastrophic command never reaches a dialog where a tired
developer might approve it. That is a guard rail, not a sandbox.

### Why semantic tools instead of a shell

`android_open_deep_link(uri, packageName)` tells an agent what the operation *means*;
`adb shell am start -a android.intent.action.VIEW -d ...` does not. Semantic tools give the
agent something to reason about, give you something readable to audit, and let the safety
level be attached to an operation rather than guessed from a string.

## Resources

Read without a tool call: `android://devices`, `android://device/selected`,
`android://project/application-id`.

Every resource is read fresh and stamped with the time it was read. A resource an agent
believes is current but is minutes old is worse than none — it will reason confidently about
a screen that has since changed.

## Jetpack Compose

Compose is a first-class target, not an afterthought bolted onto a View-based design.

**Why it works without depending on Compose.** Compose has no View hierarchy to inspect, so
`Activity → View hierarchy` is simply the wrong model for a Compose screen. What Compose
*does* publish is **semantics into the accessibility tree** — the same tree `uiautomator`
reads. Modelling semantics covers Views, Compose and hybrid screens with one implementation,
and means the plugin needs **no Compose artifact and pins no Compose version**.

`android_get_ui_tree` reports which framework is in use:

| Reported | Meaning |
|---|---|
| `Traditional Android Views` | No Compose host on screen |
| `Jetpack Compose` | An `AndroidComposeView` is hosting the content |
| `Mixed Views and Jetpack Compose` | Both, with real View *content* widgets present |

The detection deliberately ignores layout containers: every Compose app still has an
`android.widget.FrameLayout` decor view, so counting any `android.widget.*` class as "Views"
would report every pure-Compose screen as hybrid.

### Semantics first, coordinates last

`android_tap_element`, `android_long_press_element`, `android_scroll_to_element`,
`android_input_text_into_element`, `android_find_ui_element`, `android_assert_visible`,
`android_assert_enabled` and `android_assert_text` all resolve elements by **testTag → content
description → text**, and only then derive a tap point from the matched node's own bounds.

A coordinate guessed from a screenshot breaks on a different screen size, density, font scale
or after any layout change, and is the single biggest cause of flaky AI-driven UI automation.
`android_tap` still exists, and its description tells agents it is the fallback.

One Compose-specific detail matters: Compose usually puts the text on a child node and the
click handler on its **parent**, so the node matching "Continue" often is not the tappable
one. `android_tap_element` walks up to the nearest interactive ancestor automatically.

### Limitations, stated plainly

- **Compose test tags require the app to opt in.** `Modifier.testTag` is only visible over
  ADB when the app sets `Modifier.semantics { testTagsAsResourceId = true }` (Compose UI
  1.2+). When it has not, `android_get_ui_tree` says so explicitly and tells the agent to
  match on text or content description instead. It does not pretend the tag is missing for
  some other reason.

- **The current Compose Navigation route is not observable over ADB.** Navigation Compose
  keeps its back stack in memory and publishes nothing to `dumpsys` or the accessibility
  tree, so an agent sees the hosting Activity and the visible semantics, not the route. To
  make routes visible, an app can put the route in a test tag on its `NavHost` and enable
  `testTagsAsResourceId`. Navigation **Fragment** destinations *are* visible, via
  `android_get_current_fragments`.

- **The Layout Inspector's Compose protocol is deliberately not used.** Android Studio reads
  the full composition tree — recomposition counts, modifiers, parameters — through the
  app-inspection framework, which needs a debuggable build, the `ui-tooling` artifact, and a
  JVMTI agent speaking an undocumented protocol. Reimplementing that would mean depending on
  unstable Compose internals and would break with Compose releases. The semantics tree is the
  stable, documented, version-independent alternative, and is what test frameworks use too.

- **Recomposition counts are therefore not available.** Use Android Studio's Layout Inspector
  for that; it is better at it and already exists.

### Accessibility audit

`android_accessibility_audit` reports unlabelled interactive elements, touch targets below
the 48dp minimum, ambiguous duplicate labels and unlabelled images — each with a **code-level
fix appropriate to the framework**. On a Compose screen it suggests
`Modifier.semantics { contentDescription = "…" }`, not `android:contentDescription`, because
the View-level fix does not exist there.

## UI automation

`android_get_ui_tree` returns the semantics tree as a structure rather than raw XML: class,
test tag, text, content description, bounds, and whether each node is clickable, enabled,
scrollable, checked or selected. Pass `interactiveOnly` to see only what can be acted on.

Agents should not drive `android_tap` from those bounds by hand. Prefer the element-addressed
tools — `android_tap_element`, `android_long_press_element`, `android_scroll_to_element`,
`android_input_text_into_element` — which resolve the element from semantics and derive the tap
point from the matched node themselves. `android_tap` remains the fallback for a screen that
offers no semantic identifier at all, and its own description says so.

`android_assert_visible`, `android_assert_enabled` and `android_assert_text` let an agent verify
the result of an action rather than infer it from pixels.

Screenshots are first-class MCP image content, so an agent can also look at the screen.

## Triage, files and screen recording

### `android_get_debug_context`

The call to reach for first when something is wrong. It returns the current activity, the UI
semantics tree with its framework identified, recent logcat, and optionally a screenshot — in
one round trip, all describing **the same moment**. Assembling those separately costs three or
four turns, and by the time the last one lands the screen may have moved on, so the bundle it
produces describes no single moment at all.

Sections are chosen with `include` (`activity`, `ui`, `logcat`, `screenshot`); the first three
are the default. The screenshot is opt-in because it is by far the most expensive section.

**A failing section does not fail the call.** A screenshot blocked by `FLAG_SECURE` must not
cost you the crash sitting beside it in logcat, so each section reports its own failure in place
and the rest still come back.

### `android_push_file` and `android_pull_file`

Deliberately narrower than `adb` itself, in two ways.

**Device paths are restricted** to `/sdcard`, `/storage` and `/data/local/tmp`. `adb` will hand
over anything the shell user can read, and an agent that can be talked into pulling another
app's database is an exfiltration path wearing a debugging tool's clothes. A path outside the
allow-list is *refused*, not confirmed: one conditional gate that sometimes prompts would be a
second implementation of the safety model, and `android_run_adb_command` already exists as the
confirmed escape hatch.

**The local destination of a pull is not a parameter.** A tool that writes where its caller asks
lets anything holding the MCP token drop a file anywhere on your filesystem, and no debugging
workflow needs that. Pulls land in the IDE's own pull directory and the tool reports the path;
small text files come back inline as well, so reading one costs no second call. A transfer that
fails part-way cannot leave a half-written file under a previous good name — the pull stages to
a neighbour and moves into place.

Transfers are capped at 50 MB.

### `android_start_screen_recording` and `android_stop_screen_recording`

One session per device, capped at three minutes. Recording stops with `SIGINT` rather than
`SIGKILL` so `screenrecord` writes the MP4 index on the way out — a killed recording leaves a
file no player will open — and the remote file is deleted only once the pull has succeeded.

### `android_select_project`

Needed only when the IDE has more than one project open. The application ID, the sources an
Activity resolves against and the default logcat filter all come from a project, so with several
open the plugin refuses to guess and names the candidates, exactly as it does for several
attached devices. With one project open, every tool already targets it and this call is
unnecessary.

## Example workflows

**Debug a crash.** `android_get_debug_context(include: ["activity", "ui", "logcat"], minLevel:
"E")` → analyse. That is one call where it used to be four, and every section describes the same
moment.

**Test a deep link.** `android_open_deep_link(uri)` → `android_get_current_activity` →
`android_take_screenshot` → report which screen opened.

**Investigate a permission problem.** `android_get_package_info` to see declared vs granted →
`android_get_logcat` for the denial → `android_grant_permission` → `android_restart_app`.

## Not yet implemented

Recorded honestly so the gaps are not mistaken for features:

- **Per-tool allow-lists** so a developer can expose, say, the read-only tools and nothing
  else. The per-call confirmation on destructive tools is the real boundary today, and there is
  no way to remove a tool from the catalogue short of not starting the server.
- **Prompts.** `prompts/list` answers with an empty array. The debugging workflows in this
  document are prose an agent cannot call.
- **Cancellation over HTTP.** stdio honours `notifications/cancelled` by interrupting the
  request; the HTTP transport is stateless by design and has nothing to cancel against, so a
  slow tool call there runs to its timeout.

## Testing

Two guards exist because of bugs that reached users. `StubbedIDeviceApiTest` fails the build
when anything calls one of the nineteen `IDevice` methods Android Studio leaves unimplemented —
each throws "This method is not used in Android Studio" at runtime while compiling and
unit-testing cleanly, because a test that builds its own `AndroidDebugBridge` gets stock ddmlib
where they all work. It reads compiled bytecode rather than source, since Kotlin's property
syntax hides the call: `device.screenshot` is a call to `getScreenshot()` that no text search
would find. This is exactly how `android_take_screenshot` shipped broken.

`McpSmokeTest` calls every read-only tool against a real device through a running server, which
is the only place that class of failure appears. The live checks are opt-in via `SPOCK_MCP_URL`
and `SPOCK_MCP_TOKEN`, so an ordinary `./gradlew test` skips them — but its coverage assertion
always runs, so a read-only tool cannot be added without deciding how it is smoke-tested.

The protocol, the safety model and both transports are all tested without a device:
`FakeToolContext` stands in for the IDE and ADB. The tests that matter most assert that
destructive tools cannot succeed unasked, that catastrophic commands are refused before the
dialog, and that an unauthorised or wrong-token request is rejected — including a token that
is a prefix of the real one, since the comparison is constant-time.

The stdio transport is tested over a real pipe against the real `McpProtocol` — `initialize`,
`tools/list`, `tools/call`, an invalid request, malformed JSON, an unknown tool, a failing
tool, cancellation and shutdown — so the claim that it re-implements nothing is checked rather
than asserted. The launcher is tested as an actual spawned process, because the two things
most likely to be wrong about it are only true of a real one: that it exits when its client
closes stdin or the IDE stops the server, and that it writes nothing but protocol messages to
stdout.
