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
  Start / Stop / Restart / Copy Config / Rotate Token / Settings.
- **Copy Config** — a menu, safest form first: the stdio configuration (no token), the HTTP
  one that reads `$SPOCK_ADB_MCP_TOKEN` from the environment (no token), the HTTP one with the
  token written into it (confirmed first), and **Install into this project**, which writes
  `.mcp.json` in the project root.
- **Rotate Token** — invalidates the current session token and issues a new one. stdio clients
  re-read the token file and need no config edits; an HTTP client needs its environment updated, and
  the new `export` line can be copied once, right after rotating.
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
2. In the MCP Server panel, **Copy Config → Install into this project (`.mcp.json`)** — or
   `Tools → SpockAdb → Spock: Install MCP Client Configuration Into This Project`
3. Restart your MCP client

That writes the project's `.mcp.json`, merging into whatever servers are already in it, and
never writes a token into it. If you would rather paste it yourself, use `Copy Config` and pick
a form.

**Neither entry can leave this machine.** `.mcp.json` in a project root is a file teams share —
that is what project scope is for — but nothing the plugin generates survives that:

| Entry | Contains | Why it is machine-local |
|---|---|---|
| **stdio** | this machine's JDK, plugin jar and IDE config, by absolute path | Those paths do not exist on anyone else's machine |
| **HTTP** | `http://127.0.0.1:<port>/mcp` and `${SPOCK_ADB_MCP_TOKEN}` | The port is whatever the OS handed this IDE on first start, and the token is in this machine's keychain |

So the choice the install asks about is what your **client** can do — spawn a process (stdio) or
open a URL (HTTP) — not who can use the file. After the install it offers to add `.mcp.json` to
the project's `.gitignore`, whichever entry you picked, and only when `.gitignore` does not
already say so. A teammate installs their own from their own IDE.

If you pick HTTP, set the token too: **Copy Config → Copy the `SPOCK_ADB_MCP_TOKEN` export
line**. Without it the config names a variable that is never set and every request is rejected.

> **Pasting a configuration into a chat connects nothing.** A client only ever reads its own
> config file. If the snippet you paste is the HTTP one with the token in it, all that happens
> is that a live credential for your device ends up in a transcript — rotate it if that
> happens.

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
      "headers": { "Authorization": "Bearer ${SPOCK_ADB_MCP_TOKEN}" }
    }
  }
}
```

This is the form `Copy Config` produces by default, and it holds **no credential**: the token
comes from `SPOCK_ADB_MCP_TOKEN` in the client's environment. Claude Code expands `${VAR}` in
`.mcp.json`; clients that do not are served by `Copy HTTP config with token…`, which writes the
token in literally and asks first.

Get the value from **Copy Config → Copy the `SPOCK_ADB_MCP_TOKEN` environment line** (or from
**Rotate Token**, which offers the same line once a new token exists):

```sh
export SPOCK_ADB_MCP_TOKEN=<the token>
```

On Windows, the plugin copies both forms:

```powershell
$env:SPOCK_ADB_MCP_TOKEN='<the token>'
```

```bat
set "SPOCK_ADB_MCP_TOKEN=<the token>"
```

Either way that token is a credential for your device: anything holding it can drive the device
and read and write files on this machine. If one reaches a chat, an issue or a screen share,
rotate it.

### Rotating the token

`Tools → SpockAdb → Spock: Rotate MCP Token`, or **Rotate Token** in the panel. It generates a
new token, restarts the server if it is running, and disconnects every client still presenting
the old one. stdio clients re-read the token file on their next connection and need no change.

Rotation invalidates a leaked credential; it does not close the port. Existing stdio
configurations are still the right ones — the launcher re-reads the descriptor file on the next
connection — but an HTTP client needs the new environment value. An IDE you no longer want
reachable at all is best handled by stopping the server as well.

## Architecture

```
MCP client --+-> McpHttpServer  --+
             |                    |
             +-> McpStdioServer --+->  McpProtocol  ->  ToolRegistry --+
                                                                       |
                       IDE tool window -> AdbController -> commands ---+
                                                                       |
                                                                       v
                                                     AppOperations / DeviceLister
                                                                       |
                                                                       v
                                                                 ADB -> device
```

The transports meet at `McpProtocol` and share everything below it: one protocol
implementation, one `ToolRegistry`, one safety model, one audit trail. A `tools/call` arriving
over stdio is confirmed, recorded in the activity panel and written to `idea.log` exactly as
the same call over HTTP, because it is the same call.

The MCP layer **owns no ADB logic**. Tools resolve devices through the same
`DebugBridgeProvider` and `DeviceLister` the tool window uses, and what both surfaces do to a
device lives in `spock.adb.device.ops`:

| | |
| --- | --- |
| `AppOperations` | launch, stop, restart, clear data, clear cache, uninstall |
| `InspectionOperations` | current activity, activity stack, fragments, app labels |
| `UiTreeOperations` | the `uiautomator` capture behind the UI Inspector and the UI tools |

Each holds Android behaviour and nothing else: no Swing, no PSI, no MCP, no confirmation
policy. One implementation of every device operation means one set of behaviours, one set of
error messages, and no chance of the UI and the agent path drifting apart.

Three domains are shared already and were left alone rather than moved for symmetry: app
storage goes through the `AppStorageCommands` extensions from both sides, the HTTP proxy
through `IDevice.setHttpProxy` and its read-back, and the deep link through
`openDeepLinkWithAmStart`. File transfer and coordinate input have no tool-window counterpart
to drift from. Logcat looks shared and is not: the tool window streams `logcat -v threadtime`
for as long as the tab is open, an agent reads a bounded `logcat -d -t n` snapshot, and those
are two operations that happen to name the same command.

They did drift while these were two implementations, in ways nobody chose: `android_stop_app`
skipped the "is it installed" check the button made, and the button ignored the failure the
tool reported when an uninstall was refused. `AppOperationsParityTest` now runs each action
from both entry paths against identically scripted devices and fails when what they send to
the device stops matching.

Confirmation stays **above** this layer on purpose. A human-driven destructive action is
gated by `DestructiveActionConfirmation` in the tool window, an agent-driven one by
`ToolContext.confirmDestructive` before the tool calls the operation. Each asks its own
caller in the way that caller can answer; the shared layer does the device work and never
decides whether it was allowed.

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

**Where the token itself lives.** In `PasswordSafe` — the IDE's credential store, backed by the
OS keychain — and nowhere else. It used to sit in `spock-adb-mcp.xml` as a plain attribute,
which put a credential for the device and the filesystem in a file that settings sync copies
between machines, that backup tools pick up, and that anything running as the developer can
read. An existing token is moved into the keychain on the first startup after the update and the
attribute is cleared, so already-configured clients keep working. The `600` descriptor file the
stdio launcher reads is the one deliberate copy: the relay is a separate process and has no
other way to authenticate.

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

62 tools, in three levels.

| Level | Behaviour | Tools |
|---|---|---|
| **Read-only** (26) | Runs automatically. Cannot change device or app state. | `android_list_devices`, `android_get_device_info`, `android_list_packages`, `android_get_package_info`, `android_get_current_activity`, `android_get_activity_stack`, `android_get_current_fragments`, `android_get_logcat`, `android_get_processes`, `android_get_battery_info`, `android_get_network_info`, `android_get_debug_context`, `android_take_screenshot`, `android_get_ui_tree`, `android_find_ui_element`, `android_accessibility_audit`, `android_assert_visible`, `android_assert_enabled`, `android_assert_text`, `android_wait_for_element`, `android_get_http_proxy`, `android_list_app_storage`, `android_read_app_storage`, `android_get_scheduled_jobs`, `android_get_pending_alarms`, `android_get_device_conditions` |
| **Safe action** (28) | Runs automatically. Changes state only in ways you routinely do by hand and can undo by repeating a normal action. | `android_select_device`, `android_select_project`, `android_launch_app`, `android_stop_app`, `android_restart_app`, `android_clear_app_cache`, `android_grant_permission`, `android_tap_element`, `android_long_press_element`, `android_scroll_to_element`, `android_input_text_into_element`, `android_open_deep_link`, `android_input_text`, `android_tap`, `android_swipe`, `android_press_key`, `android_push_file`, `android_pull_file`, `android_start_screen_recording`, `android_stop_screen_recording`, `android_clear_http_proxy`, `android_run_job_now`, `android_set_standby_bucket`, `android_unplug_battery`, `android_set_battery_level`, `android_set_charger`, `android_reset_battery`, `android_reset_device_conditions` |
| **Destructive** (8) | **Always** asks you first, per call. Never auto-approved. | `android_clear_app_data`, `android_uninstall_app`, `android_revoke_permission`, `android_set_http_proxy`, `android_set_app_preference`, `android_delete_app_preference`, `android_run_adb_command`, `android_force_doze` |

Rules that hold regardless of what a client asks for:

- A destructive call **cannot** report success without a confirmation. This is enforced by a
  test, not by convention.
- Confirmation **defaults to denied**. An unattended IDE, a disposed project, or a failure to
  show the dialog all mean "no". Nothing is ever waved through by timeout.
- The IDE window is brought forward when a confirmation is needed, because the request came
  from another application and you are probably not looking at it.
- Destructive calls are written to `idea.log`, so "what did the agent do to my device"
  survives a restart. The full activity trail survives one too: it is kept as newline-delimited
  JSON under the IDE config directory, capped by the same "keep the most recent N requests"
  setting, and written off the calling thread so an agent never waits on a disk write.

### Turning tools off

`Settings → Tools → Spock ADB → Tool Access` lists every registered tool, grouped by safety
level, with **Enable all** and **Read-only only** for the two decisions worth making in one
click. The per-call confirmation on destructive tools is unchanged: this decides what may be
*attempted*, that decides what actually runs.

It exists because confirmation cannot see a *composition*. `android_push_file` and
`android_pull_file` are individually reasonable and compose into reading any file on the
machine; a dialog shown one call at a time has no way to notice that, and a list does not have
to.

Three properties are deliberate:

- **A disabled tool is still listed and still described.** It refuses when called, naming
  itself and where the switch is, so an agent is told "you turned this off" rather than hunting
  for a tool it can see documented. The exception is the in-IDE assistant, which is handed a
  fresh list every turn and so is simply not offered the tool.
- **A refused call is audited like any other.** An agent reaching for something it was denied
  is the entry most worth reviewing.
- **The setting stores what is *off*.** A tool added by a later plugin update is therefore
  available by default, rather than silently withheld from anyone who had ever opened the
  screen.

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

Element selectors accept optional `packageName` and `containerTag` scopes. A container tag
must identify exactly one subtree; `exactTag: true` matches a complete case-sensitive tag or
raw resource ID. Existing substring matching remains the default for compatibility.
Taps, long presses, and text entry reject multiple matches and disabled or ineligible targets.
Matches that resolve to the same control count once. A refusal lists the candidates and names
what can separate them: often `exact: true`. An action result reports that the input was
dispatched, not that the app changed, unless the call names an expected result — see
[Actions with an expected result](#actions-with-an-expected-result). `android_scroll_to_element` swipes the
outermost of nested scrollable containers and refuses only between unrelated ones.

Accessibility audits separate spoken labels from test tags. Touch-target estimates use the
reported default-display density; when it cannot be read, the size check is explicitly skipped.
Reported bounds may differ from expanded touch regions, so these checks do not certify accessibility.

`android_tap_element`, `android_long_press_element`, `android_scroll_to_element`,
`android_input_text_into_element`, `android_find_ui_element`, `android_assert_visible`,
`android_assert_enabled`, `android_assert_text` and `android_wait_for_element` all resolve elements
by **testTag → content description → text**, and only then derive a tap point from the matched node's own bounds.

A coordinate guessed from a screenshot breaks on a different screen size, density, font scale
or after any layout change, and is the single biggest cause of flaky AI-driven UI automation.
`android_tap` still exists, and its description tells agents it is the fallback.

One Compose-specific detail matters: Compose usually puts the text on a child node and the
click handler on its **parent**, so the node matching "Continue" often is not the tappable
one. `android_tap_element` walks up to the nearest clickable ancestor automatically.

### Limitations, stated plainly

- **Compose test tags require the app to opt in.** `Modifier.testTag` is only visible over
  ADB when the app sets `Modifier.semantics { testTagsAsResourceId = true }` (Compose UI
  1.2+). When no exposed tags are observed, `android_get_ui_tree` suggests matching on
  text or content description. The capture alone cannot establish whether tags were never
  added, their subtree was not exposed, or the tagged content is absent from this capture.

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
the result of an action rather than infer it from pixels, and `android_wait_for_element` lets it wait
for that result without guessing how long to sleep. Every assertion opens with the capture's summary
line. `android_assert_enabled` needs exactly one match: with several it fails and lists them, as an
action's refusal does, rather than answering for whichever came first.

Screenshots are first-class MCP image content, so an agent can also look at the screen.

### In the tree, in the viewport, and in view

These are three different answers, and the tools keep them apart. Each node of a capture is
classified against the capture's viewport — the dumped window clipped to the display — and
against every **scroll container** above it: a list shows its content only within itself, so
a row laid out below a list's edge is outside the viewport even when it lies inside the display.
Other parents do not clip. A node is *in the viewport*, *partly in it* (with the share in view),
*outside it*, of *zero area*, or — when neither the display size nor the window bounds could be
read — *viewport unknown*.

- **Taps, long presses and text entry** refuse a target outside the viewport or with zero area,
  send nothing, and point the agent to `android_scroll_to_element`. A target partly in view is
  pressed at the centre of its part in view, not of its bounds. Without a viewport the press goes
  to the centre of the bounds, and the result says the viewport was unknown. Ambiguity is still
  decided over the whole tree: an off-screen duplicate makes a selector ambiguous.
- **`android_assert_visible` and `android_assert_text`** pass when at least one match has
  something in the viewport, and say how many of the matches do. They fail when every match is
  outside it, fail when nothing matched, and are **inconclusive** — an error result, so a test
  workflow stops — when the viewport is unknown.
- **`android_scroll_to_element`** stops at a match in the viewport, not at a match that is only
  in the tree. Without a viewport it keeps the old rule, any match, and says so. It still does
  not look again after its last swipe.
- **`android_find_ui_element`** describes where each match is, and **`android_get_ui_tree`**
  marks only the nodes that are not plainly in view — `[outside viewport]`, `[62% in viewport]`,
  `[partly in viewport, clipped by scroll container]` — so a screen that is all in view costs no
  extra tokens.

What this cannot tell you:

- **Nothing is ever called unobscured.** Bounds cannot show a dialog, a sheet or a sibling drawn
  on top, so every "in the viewport" says *occlusion not checked*.
- **A node scrolled wholly out of view is usually absent, not "outside".** On an API 34 emulator,
  a Compose node scrolled out of its column is left out of the dump entirely, so it comes back as
  *nothing matched* — the refusal still points to `android_scroll_to_element`.
- **A node cut by its container's edge has an unknown size.** `uiautomator` reports a row partly
  scrolled out with bounds already cut short and its out-of-view text missing, sometimes still
  reaching past the container's edge. A node whose bounds reach a scroll container's edge is
  therefore flagged *clipped by scroll container*: its share in view is unknown, and the
  accessibility audit leaves it out of the size and label checks rather than report the clipping
  as a fault, counting what it skipped in its coverage note. The first row of a list at scroll
  offset zero looks the same, so the flag means *may be* cut.

### Waiting for the screen to change

`android_wait_for_element` captures the screen every `pollIntervalMs` (default 500, 100 to 5000)
until an element meets `until`, or `timeoutMs` (default 10000, 0 to 60000; at most 15000 over
HTTP — see below) runs out. It is read-only: it sends nothing to the device but captures.

| `until` | Met when |
|---|---|
| `visible` (default) | a match is in, or partly in, the viewport |
| `present` | a match is in the tree, wherever it is |
| `gone` | nothing in the tree matches |
| `hidden` | no match is in the viewport |
| `enabled`, `disabled`, `checked`, `unchecked`, `selected`, `unselected`, `focused` | exactly one element matches, and it is in that state |

- A state needs exactly one match. With several, the wait keeps looking, and a timeout says the
  selector was ambiguous and lists the candidates. `checked` and `unchecked` need an element that can
  be checked, so a plain button never counts as unchecked.
- `gone` is met at once by an element that was never there. And an element scrolled out of a
  Compose list usually drops out of the tree on a device, so `gone` and `hidden` cannot tell
  "removed" from "scrolled away".
- Without a viewport, `visible` and `hidden` are never met while anything matches, and a timeout
  says why.
- The result says how many captures the wait took and how long, and how many `uiautomator` refused
  or left empty, usually a UI still animating. Those are retried.

**Timing.** A dump is not quick: 2 to 7 s on an API 34 emulator, and about 13 s before `uiautomator`
gives up on a UI that never settles. So **the first capture of every wait runs to completion**, with a
capture's full 30 s, even when that takes it past `timeoutMs`: a wait shorter than one dump would
otherwise end having seen nothing. Every wait therefore looks at the screen at least once and answers
from what it saw, and `timeoutMs: 0` means "look once". When that first look took longer than the whole
limit, the result says so — "The first observation took 4.6 s, past the 1.0 s limit" — so a verdict
reached late is not mistaken for one reached in time. A limit of 0 is never called late, since one look
is what it asked for.

Each later capture is given only what is left of the wait, rounded up to whole seconds, at least one.
So after the first look a wait overruns its limit by under a second, plus the time to read back a dump
that finished just in time. A later capture that runs out of time ends the wait, since it had all that
was left, and the result says so along with what the last completed capture showed. A first capture
that runs out of its full 30 s is a dump that never finished, and the result says the device may be
badly loaded. Cancelling does not wait for the first capture to finish: it stops at adb's next check,
as any capture does.

**Cancellation** depends on who is asking:

| Caller | What stops a wait |
|---|---|
| A stdio client, with `notifications/cancelled` | The worker running the request is interrupted. A capture in progress stops at ddmlib's next check, and a pause between captures ends at once |
| The in-IDE assistant's **Stop** | A flag, checked during each capture and every 100 ms of a pause. No thread is interrupted, since that would drop the model's HTTP connection too |
| An HTTP client | Nothing. The call runs to its limit — at most 15 s over HTTP, or one first capture when that takes longer, plus the overrun above — and holds one of the HTTP server's four worker threads while it does |

A cancelled wait answers `CANCELLED …; nothing was changed on the device`. Over stdio that answer is
discarded, as the spec requires.

**The HTTP cap.** The HTTP server has four worker threads and no way to cancel a call: a client that
gives up closes its connection and the wait runs on regardless. Four abandoned 60-second waits would
hold every thread for a minute, stalling every HTTP call behind them — `tools/list` included. So over
HTTP a `timeoutMs` above 15000 is capped at 15000, and the result ends by saying it was capped and
from what. An agent that needs longer calls the tool again, or uses the stdio transport, where a wait
can be cancelled and so keeps its full 60 s.

### Actions with an expected result

`android_tap_element`, `android_long_press_element` and `android_input_text_into_element` can check
what their input led to. Name the element expected afterwards with the same flat fields a wait
takes, prefixed `expect`:

| Argument | Meaning |
|---|---|
| `expectTestTag`, `expectText`, `expectContentDescription` | The element expected after the action. Any of them turns the check on |
| `expectExact`, `expectExactTag` | Whole-value text and description match; case-sensitive whole tag match |
| `expectUntil` | `android_wait_for_element`'s words: `visible` (default), `present`, `gone`, `hidden`, or a state |
| `expectTimeoutMs` | How long to look, 0 to 60000, default 5000; at most 15000 over HTTP, as for a wait |

The expected element is matched over the whole screen: `packageName` and `containerTag` scope only the
element acted on, since a result often appears outside the control that caused it. `expectUntil` or
`expectTimeoutMs` without an element to expect is an argument error, reported before the device is
touched.

The call observes the screen, resolves one target (the usual ambiguity and viewport rules), checks
the expectation against that same pre-action capture, sends the input, and then looks for the result
as `android_wait_for_element` would — the first look always completes, and the display metrics of the
pre-action capture are reused. The answer is one of:

| Outcome | Error? | Meaning |
|---|---|---|
| no expectation | no | Unchanged: "Tap dispatched once to …; UI outcome not verified." |
| `VERIFIED` | no | Not there before the action, seen after it |
| `NOT OBSERVED` | yes | Not seen within `expectTimeoutMs`. The input may still have landed, or the screen may need longer |
| `INCONCLUSIVE` | yes | Already true before the action, so seeing it afterwards proves nothing. Expect something the action changes |
| `CANCELLED` | yes | Stopped while checking; the input had been sent |

**An action is dispatched once and never repeated** — not when the result is not observed, and not
when the shell call itself fails. A tap whose `input tap` timed out or lost its device may still have
reached the app, and a second one could place a second order. So a failed dispatch step is reported as
**`Dispatch uncertain`**, with the failure classified as a capture's is (timed out, device unavailable),
saying the input may have reached the device and was not repeated, and pointing to
`android_find_ui_element` or `android_wait_for_element` before retrying. An uncertain dispatch is an
error, sends nothing further and runs no check. For text input, `input text` is sent only after the
focusing tap was: if that tap fails, the text is never typed. A cancel during a step says the input
may or may not have reached the device; a cancel while the target is still being found says nothing
was dispatched.

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

Transfers are capped at 50 MB in both directions, and the cap on a pull is enforced
before the bytes are kept, not just claimed. The **source** of a push is restricted too —
the open project, or the IDE's pull directory — because push and pull compose: an
unrestricted source means an agent can push any file on the machine to a device and pull
it straight back, which is an arbitrary local read wearing a debugging tool's clothes.

### `android_start_screen_recording` and `android_stop_screen_recording`

One session per device, capped at three minutes. Recording stops with `SIGINT` rather than
`SIGKILL` so `screenrecord` writes the MP4 index on the way out — a killed recording leaves a
file no player will open — and the remote file is deleted only once the pull has succeeded.

### `android_set_http_proxy` and `android_clear_http_proxy`

Points the device's global HTTP proxy at a debugging proxy on your machine — Charles,
Proxyman, mitmproxy — so an agent can inspect what the app actually sends.

`android_set_http_proxy` takes `host` and `port`, and both are optional: each one left out
defaults to the proxy last set in the tool window's HTTP proxy field, so an agent can turn your
usual proxy on without being told its address, or change only the port. With nothing set there
and an argument missing, the call fails and says to pass both or set a proxy in the tool window
once. The tool reads that remembered proxy but never changes it — only the tool window does.

`android_set_http_proxy` is the one tool here that is **destructive by judgement rather than
by definition**. It destroys nothing, and by the letter of the safety model it is a safe
action: you do it by hand routinely and undo it by clearing. What moves it up a level is the
failure mode. The setting is global, it survives a reboot, and it redirects *all* device
traffic through a host — so a device left pointing at a proxy that is no longer listening
fails every request with nothing on screen to explain why. That is the kind of state an agent
should not be able to leave behind without you agreeing to that specific call. The
confirmation names the host the traffic would go through.

Clearing is a safe action: it restores the device to its normal state and repeating it is
harmless. Reading is read-only, which is what makes either mutation safe to reason about —
an agent can always check before and after without needing approval for the check.

Both mutating tools read the value back and report what the device actually holds, through the
same write-and-read-back step the tool window uses. `settings put` exits 0 even where the
write does not take, and telling an agent the proxy is set while traffic still goes direct is
worse than reporting the failure. An IPv6 host must be bracketed, for example `[::1]`.

It does not capture everything: apps that use their own HTTP stack, or that pin
certificates, will not route through it.

### App storage tools

`android_list_app_storage` and `android_read_app_storage` show an app's SharedPreferences
(`shared_prefs/*.xml`) and Preferences DataStore (`files/datastore/*.preferences_pb`) files as
typed entries. `android_set_app_preference` and `android_delete_app_preference` change one key.
All four go through `run-as`, so the app must be a debuggable build, and they reach only files
directly inside those two directories.

The two edits are **destructive**. The change replaces app state that no normal action
restores, and the write force-stops the app — a running app holds its preferences in memory and
writes them back on its next `apply()`, which would silently undo the edit. Everything that can be
refused is refused before you are asked: a key that is not in the file, a type the file cannot
store (SharedPreferences has no double or bytes), or an EncryptedSharedPreferences file, which is
read-only. The confirmation names the value before and after.

A write re-reads the file after stopping the app and refuses if it changed since it was read, then
replaces it and reads it back. What the editor does not understand is preserved: unknown XML
elements and unknown protobuf fields survive an edit. Proto DataStore files with the app's own
schema are listed as unsupported and never decoded.

### Background work tools

`android_get_scheduled_jobs` reads `dumpsys jobscheduler <package>` and lists each job the app
has with JobScheduler, which is also where WorkManager's workers live on API 23 and above: job
id, service, periodic or one-off, the constraints it requires and which of them are unsatisfied
right now, backoff, failure count, next and last run. WorkManager's work spec id is shown only
when the dump prints the job's extras readably. Usually it prints only their size, and then the
tool says the id is not shown rather than guessing.

`android_get_pending_alarms` reads `dumpsys alarm` and lists the app's alarms with their next
trigger as a device clock time, their repeat interval and whether they are exact.

`android_run_job_now` runs `cmd jobscheduler run -f`, which starts a job now whatever its
constraints. It is a **safe action**: it runs code the app already scheduled, and changes no
setting. It needs Android 7.0 (API 24); a job in a namespace needs Android 14. The namespace is
found from the app's jobs when it is not given, which matters because WorkManager 2.10+ puts all
its jobs in the `androidx.work.systemjobscheduler` namespace on API 34+.

"Started" means JobScheduler started the job. For a WorkManager job, WorkManager then checks
whether the work is due and puts periodic work inside its period, or work in retry backoff, back
without running the Worker. The dump cannot show which WorkManager jobs are periodic, so the
result says this rather than claiming the Worker ran. One-off work does run.

### Device condition tools

Background work behaves differently in Doze, in a low App Standby bucket, and on battery. These
tools put the device in those states and take it back out:

- `android_get_device_conditions` reads the deep Doze state, the app's bucket, and whether the
  battery is overridden. It also lists what Spock changed and has not yet reset.
- `android_force_doze` unplugs the battery (Doze requires it) and runs
  `dumpsys deviceidle force-idle`. It is **destructive**: every app on the device is deferred until
  reset, and a device left in forced Doze misbehaves for whoever uses it next.
- `android_set_standby_bucket` runs `am set-standby-bucket` and reads the bucket back, because the
  command prints nothing either way and Android often keeps an app higher. On Android 12+, an app
  allowed to schedule exact alarms never drops below the working set.
- `android_unplug_battery` runs `dumpsys battery unplug`, leaving the level as it is.
- `android_set_battery_level` runs `dumpsys battery unplug` and then `dumpsys battery set level`,
  and reads the level back. It unplugs first on purpose: Battery Saver, the low-battery warning and
  the job scheduler's charging constraints all key off a device that is discharging, so a phone
  reporting 5% while plugged in behaves like a full one. The Background Work tab offers 5, 20, 50
  and 100 as one-click presets.
- `android_set_charger` runs `dumpsys battery set ac|usb|wireless 0|1` for one charger and reads it
  back, leaving the others and the level alone. AC off with USB on is a device discharging while
  still plugged into the machine — a state a plain unplug cannot express.
- `android_reset_battery` runs `dumpsys battery reset` and leaves Doze and buckets alone. Plugging
  the charger back in ends Doze on a real device, so the conditions are re-read afterwards rather
  than assumed.
- `android_reset_device_conditions` runs `unforce` and `battery reset`, and sets every bucket Spock
  moved back to active.

Every change is tracked, whether an agent made it or the Background Work tab did. The tab shows a
banner until the change is reset, and the last project to close resets every device that is still
online. A device that is offline then keeps its state; a reboot clears Doze and the battery
override.

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

- **Prompts.** `prompts/list` answers with an empty array. The debugging workflows in this
  document are prose an agent cannot call.
- **Cancellation over HTTP.** stdio honours `notifications/cancelled` by interrupting the
  request; the HTTP transport is stateless by design and has nothing to cancel against, so a
  slow tool call there runs to its timeout. For `android_wait_for_element` that is capped at 15 s
  over HTTP, so abandoned waits cannot hold all four of the server's threads for long.

## Testing

Two guards exist because of bugs that reached users. `StubbedIDeviceApiTest` fails the build
when anything calls one of the `IDevice` methods Android Studio leaves unimplemented —
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
