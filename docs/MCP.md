# Android MCP server

Spock ADB can expose the connected Android device to MCP-compatible AI agents — Claude Code,
Claude Desktop, Cursor, or anything else that speaks the Model Context Protocol.

The goal is not "an AI can run adb". It is: **an agent gets safe, structured, auditable
access to a real device**, and cannot destroy state without you agreeing to that specific
action.

> **Off by default.** While the server runs, any local process holding the session token can
> drive your device. Start it deliberately, from
> `Tools → SpockAdb → Spock: Start MCP Server for AI Agents`.

## Quick start

1. `Tools → SpockAdb → Spock: Start MCP Server for AI Agents`
2. `Tools → SpockAdb → Spock: Copy MCP Client Configuration`
3. Paste into your MCP client's config. It looks like this:

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

The token is a credential for your device. It is generated locally, never leaves your
machine unless you paste it somewhere, and can be rotated.

## Architecture

```
MCP client  ->  McpHttpServer  ->  McpProtocol  ->  ToolRegistry
                                                        |
                                                        v
                                    SpockAdbService / DeviceLister / commands
                                                        |
                                                        v
                                                   ADB -> device
```

The MCP layer **owns no ADB logic**. Tools resolve devices through the same
`DebugBridgeProvider` and `DeviceLister` the tool window uses, and reuse the same command
classes. One implementation of every device operation means one set of behaviours, one set
of error messages, and no chance of the UI and the agent path drifting apart.

`ToolRegistry` is deliberately shared: a future in-plugin AI assistant uses the same tool
definitions and the same safety levels rather than a parallel implementation. Two
implementations would drift, and the one that drifted would be the one enforcing safety.

### Why a self-hosted HTTP server

Android Studio does **not** ship IntelliJ's built-in web server —
`org.jetbrains.ide.HttpRequestHandler` is absent from the distribution, so the usual
`RestService` route is unavailable. Rather than add Netty or Ktor to a plugin that otherwise
has a single dependency, the transport uses the JDK's own `com.sun.net.httpserver`: no new
dependencies, and identical behaviour in Android Studio and IntelliJ IDEA.

## Safety model

Every tool declares a level, as a property of the tool rather than a flag a client can set.

| Level | Behaviour | Tools |
|---|---|---|
| **Read-only** | Runs automatically. Cannot change device or app state. | `android_list_devices`, `android_get_device_info`, `android_list_packages`, `android_get_package_info`, `android_get_current_activity`, `android_get_activity_stack`, `android_get_current_fragments`, `android_get_logcat`, `android_get_processes`, `android_get_battery_info`, `android_get_network_info`, `android_take_screenshot`, `android_get_ui_hierarchy` |
| **Safe action** | Runs automatically. Changes state only in ways you routinely do by hand and can undo by repeating a normal action. | `android_select_device`, `android_launch_app`, `android_stop_app`, `android_restart_app`, `android_grant_permission`, `android_open_deep_link`, `android_input_text`, `android_tap`, `android_swipe`, `android_press_key` |
| **Destructive** | **Always** asks you first, per call. Never auto-approved. | `android_clear_app_data`, `android_revoke_permission`, `android_run_adb_command` |

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

## UI automation

`android_get_ui_hierarchy` returns uiautomator's XML: view class, resource id, text, content
description, bounds, and whether each node is clickable, enabled and selected. Agents should
drive `android_tap` from those bounds rather than guessing coordinates off a screenshot —
guessed coordinates are the main cause of flaky UI automation.

Screenshots are first-class MCP image content, so an agent can actually look at the screen.

## Example workflows

**Debug a crash.** `android_list_devices` → `android_get_current_activity` →
`android_get_logcat(minLevel: "E")` → `android_take_screenshot` → analyse.

**Test a deep link.** `android_open_deep_link(uri)` → `android_get_current_activity` →
`android_take_screenshot` → report which screen opened.

**Investigate a permission problem.** `android_get_package_info` to see declared vs granted →
`android_get_logcat` for the denial → `android_grant_permission` → `android_restart_app`.

## Not yet implemented

Recorded honestly so the gaps are not mistaken for features:

- **Screen recording** (`android_start_screen_recording` / `android_stop_screen_recording`).
  `screenrecord` needs a file pulled off the device afterwards and has a hard duration limit;
  it needs a file-transfer story first.
- **MCP activity panel.** Calls are recorded (`McpServerService.recentCalls()`) and
  destructive ones are logged, but there is no tool window tab showing them live yet.
- **stdio transport.** Clients that only speak stdio need a small bridge process; HTTP works
  with Claude Code and Cursor today.
- **Per-tool allow-lists** so a developer can disable individual tools.
- **File push/pull.**

## Testing

The protocol, the safety model and the transport are all tested without a device:
`FakeToolContext` stands in for the IDE and ADB. The tests that matter most assert that
destructive tools cannot succeed unasked, that catastrophic commands are refused before the
dialog, and that an unauthorised or wrong-token request is rejected — including a token that
is a prefix of the real one, since the comparison is constant-time.
