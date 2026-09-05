# Spock ADB 5.0 — AI-native Android debugging

Working plan. Phases are executed **one at a time**, in order; each is a reviewable commit
with tests green before the next begins. Tick the boxes as they land.

## Starting point (verified against the tree at 4.0.3)

Eight of the ten headline capabilities already ship. The MCP server (HTTP + stdio, 36 typed
tools, bearer token, three-level safety model), the MCP Server panel (status, Tools catalogue,
live Activity monitor, bounded filterable history), Compose inspection via the uiautomator
semantics tree, screenshots as MCP image content, and Keymap-native actions are all present
and tested. `ToolRegistry` reserves itself in its own header for "the plugin's own AI layer".

So this is **not** a greenfield build, and the release must not be messaged as "introducing
MCP". It is: close the documented gaps, bring the AI agent inside the IDE, and polish.

## Decisions taken

| Question | Decision | Why |
|---|---|---|
| LLM providers in v1 | **Anthropic + a generic OpenAI-compatible client** | Two implementations behind `LlmClient` cover Anthropic, OpenAI, local Ollama and corporate proxies via a configurable base URL. |
| History persistence | **On by default, 500-entry cap, Settings toggle to disable and clear** | The audit trail's value is surviving the restart that loses in-memory state. Local file in the same trust boundary as the `idea.log` entries destructive calls already write. Off-by-default would disable it exactly when it is needed. |
| `android_pull_file` scope | Allow-list `/sdcard`, `/storage` and `/data/local/tmp`; anything else is **refused**, naming `android_run_adb_command` as the escape hatch | Pulling arbitrary device paths is a data-exfiltration vector. Being stricter than raw `adb` here is a deliberate product choice. Refusing beats prompting: a prompt puts the judgement on a developer mid-flow, where the safe answer is the one the agent already has a documented, confirmed route to. |
| Chat UI toolkit | **Swing**, not JCEF | Matches every other panel; JCEF is heavy and version-sensitive across AS 231+. Revisit only if markdown fidelity becomes a requirement. |
| Messaging | "Extending the MCP toolkit and bringing the AI agent into the IDE" | 4.x already shipped MCP. |

## Architecture

No new parallel systems. The assistant reuses `ToolRegistry` exactly as `McpProtocol` does —
per the registry's own stated intent — so the safety-enforcing code has one implementation.

```
Spock ADB tool window
 ├── Devices / Logcat / Commands / UI Inspector / MCP Server   (existing)
 └── AI Assistant                                              (new, Swing)
          ↓
     AssistantService (app service)
          ├── ConversationStore (bounded, in-memory)
          ├── LlmClient ──→ AnthropicClient | OpenAiCompatibleClient
          └── AgentLoop  ─┐
                          │
MCP client → McpHttpServer / McpStdioServer → McpProtocol ─┼→ ToolRegistry → ToolContext → DeviceLister/Commands → ddmlib → device
                                                           │       ▲
                                                           │   new tools register here only
                                                           └── one allow-list, one audit trail,
                                                               one destructive-confirm dialog
```

Invariants that must survive every phase:

- The MCP layer owns no ADB logic; the assistant owns no tool logic.
- `ToolContext.confirmDestructive` is the **only** destructive gate, and defaults to deny —
  including when the caller is the in-plugin assistant. An AI loop can never clear app data
  unasked.
- Assistant tool calls land in the same `McpRequestHistory` (client `spock-assistant`), so the
  Activity tab shows everything an agent did, whatever drove it.
- Zero new external dependencies; no change to `sinceBuild=231` or the open `untilBuild`.

## Compatibility rules for all new code

From `docs/COMPATIBILITY.md`, non-negotiable:

- No `sequence {}`, `iterator {}`, or `suspend` — coroutine state machines reference stdlib
  symbols absent on 2023.1 IDEs. The agent loop is therefore synchronous, on pooled threads.
- `-Xjvm-default=all` stays global.
- Explicit `getService(...)`; no reified service accessors.
- `java.net.http`, `HttpServer`, `PasswordSafe` and Gson are all present on platform 231.
- `verifyPlugin` must stay green on AS 231/242/251 and IDEA 231/251.

---

## Phase 1 — `android_get_debug_context` (P0)

One call returning the triage bundle an agent currently needs three or four calls to assemble.

- [x] Extract a shared `LogcatReader` from `GetLogcatTool` in `mcp/tools/InspectionTools.kt`
      (PID resolution + `logcat -d -v threadtime` construction) so the new tool does not
      duplicate the pidof-not-grep logic.
- [x] `mcp/tools/DebugContextTool.kt` — `READ_ONLY`. Args: `include` (array of
      `activity|logcat|ui|screenshot`, default all but screenshot), `maxLogcatLines`
      (default 200, cap 2000), `maxUiDepth`, `packageName`, `deviceSerial`.
- [x] Always emit `UiTree.frameworkNote()` in the UI section, so an assistant never misses the
      `testTagsAsResourceId` caveat on a Compose screen.
- [x] Per-section caps and a 60s overall budget — this is the heaviest tool in the registry.
- [x] Register in `ToolRegistry`.
- [x] `DebugContextToolTest` — section matrix, caps honoured, no-device message is actionable.

### Landed in Phase 1

`LogcatReader` extracted; `Schema.stringArray` + `optionalStringList` added;
`UiTreeReader.render` gained a counted `maxDepth`; `DebugContextTool` registered, with 11 tests.

Two guards worth knowing about before writing another tool, both of which failed first:

- `McpSmokeTest` refuses to pass unless every `READ_ONLY` tool appears in its `PROBES` or
  `QUERIES` table, so a new read-only tool must be classified there.
- `ToolSafetyTest` pins the exact membership of each safety level.

## Phase 2 — File transfer and screen recording (P1)

- [x] `mcp/tools/FileTools.kt` — `PushFileTool`, `PullFileTool` over `IDevice.pushFile/pullFile`.
      Path validation rejects `..` and other apps' `/data/data`; 50 MB cap; pulls outside the
      allow-list are `DESTRUCTIVE` and prompt.
- [x] `mcp/tools/ScreenRecordTools.kt` — `StartScreenRecordTool` / `StopScreenRecordTool`,
      `SAFE_ACTION`, single tracked session, 180s auto-stop (platform hard cap), pull to a
      local path then `rm` the remote file.
      **Constraint found in Phase 1:** `IDevice.startScreenRecorder` is on
      `StubbedIDeviceApiTest.UNIMPLEMENTED_IN_ANDROID_STUDIO` — Android Studio's
      `AdblibIDeviceWrapper` throws "This method is not used in Android Studio" for it. Recording
      must go through `executeShellCommand("screenrecord …")`, and the guard test will fail the
      build if it does not. `getSyncService` is likewise unimplemented, so pushes and pulls must
      use `IDevice.pushFile`/`pullFile` directly rather than opening a sync service.
- [x] Tool descriptions state plainly that a recording captures whatever is on screen.
- [x] `FileToolsTest` (path validation, size caps), `ScreenRecordToolsTest` (state machine:
      start twice → error, stop without start → error).

### Landed in Phase 2

`DevicePaths` allow-list, `PushFileTool`, `PullFileTool`, `ScreenRecorder` +
`Start`/`StopScreenRecordTool`, with 18 tests.

Two deliberate narrowings of the plan, both tightening rather than loosening it:

- A pull outside the allow-list is **refused**, not confirmed. One conditional gate that
  sometimes prompts would be a second implementation of the safety model; refusing and pointing
  at `android_run_adb_command` (already `DESTRUCTIVE`, already confirmed per call) keeps exactly
  one.
- The local destination of a pull is **not a parameter**. Letting a caller choose it hands
  anything with the MCP token an arbitrary filesystem write.

## Phase 3 — Assistant core (P0)

- [x] `assistant/LlmClient.kt` — synchronous interface called from pooled threads, streaming
      via callback, explicit cancellation predicate. No coroutines.
- [x] `AdbTool.toToolSpec()` — one mapping from the existing `inputSchema`, so tool definitions
      are never written twice. **No `provider` parameter**: a tool is a name, a description and
      a JSON Schema for both providers, and only the envelope differs — that belongs to the
      client writing the request body. Passing the provider in would have put two providers'
      knowledge in the mapping *and* still left each client shaping its own request.
- [x] `assistant/AnthropicClient.kt` and `assistant/OpenAiCompatibleClient.kt` on
      `java.net.http.HttpClient` + Gson. Configurable base URL. Errors surfaced verbatim; no
      retries in v1. Model `claude-opus-5`; `thinking` is deliberately unset (on by default on
      that family, and the older `budget_tokens` form is a 400, not a knob).
- [x] `assistant/AgentLoop.kt` — model ⇄ tool-call cycle, hard cap 25 iterations, every call
      recorded to `McpRequestHistory` as `spock-assistant`. Reaches the registry through an
      `AgentTools` seam so the loop is testable without an IDE, a device or 42 real tools;
      `RegistryAgentTools` is the adapter and holds no tool logic of its own.
- [x] API key in `PasswordSafe` only — never in `PersistentStateComponent` XML, logs or history
      (`assistant/AssistantKeyStore.kt`, read on demand so a rotated key applies to the next
      request rather than the next restart).
- [x] `AgentLoopTest` (scripted fake `LlmClient`: tool cycle, iteration cap, denied
      confirmation fed back to the model, cancellation), `ToolSpecMappingTest`,
      `AnthropicStreamTest` and `OpenAiStreamTest` (fragmented tool arguments, parallel calls
      by index, a half-streamed call dropped rather than run, mid-stream error, refusal).
- [x] ~~`assistant/AssistantToolContext.kt`~~ — **not written, deliberately.** `McpToolContext`
      already *is* "the same `DeviceLister`/`DebugBridgeProvider`, reusing the confirmation
      dialog". A subclass would add a name and no behaviour, and would be a second place for the
      safety model to drift to. The assistant is handed the same context the transports get.

### Landed in Phase 3

`LlmClient`, `LlmMessage`/`LlmToolCall`/`LlmToolResult`/`ToolSpec` (one neutral shape, since a
loop written against either provider's own would have to be rewritten for the other),
`AnthropicClient` + `AnthropicStream`, `OpenAiCompatibleClient` + `OpenAiStream`, `AgentLoop`,
`AgentTools`/`RegistryAgentTools`, `AssistantKeyStore`, `toToolSpec`.

Each provider's SSE parsing is split from its HTTP call, which is what makes the frame handling
testable from a canned stream: 22 tests run without a network, an IDE or a key.

Still open before Phase 4 can use this: nothing in the loop, but no `AssistantService` owns a
conversation yet — that arrives with the UI it exists to serve.

## Phase 4 — Assistant UI (P0)

- [ ] `assistant/AssistantPanel.kt` — Swing: transcript, input, Send, Stop, "attach debugging
      context" toggle that injects a `android_get_debug_context` result into the first message.
- [ ] Tab in `spock/AdbDrawerViewer.kt`, disposer registered like its siblings.
- [ ] `OpenAssistantAction`, following `OpenMcpPanelAction`. No default keyboard shortcut —
      established project policy, users bind via Keymap.
- [ ] Settings section: provider dropdown, model, base URL, write-only API key field.
- [ ] States: empty (no key → link to Settings), loading (Send disabled, Stop enabled), error
      (rendered as a transcript line with the HTTP status, not a modal). Destructive
      confirmation stays the existing IDE modal — never an inline chat approval.
- [ ] Streaming appends throttled to the EDT on the Logcat panel's 100ms flush pattern.
- [ ] Transcript bounded (~500 messages), `JBColor`/`JBUI` only, Ctrl+Enter sends, Esc stops.

## Phase 5 — Safety and control (P0)

- [ ] `McpSettings.disabledTools: MutableSet<String>`; predicate consulted in
      `McpProtocol.dispatch` **and** the assistant loop. The shared registry is never mutated.
- [ ] A disabled tool returns a clear "disabled by user" error and is still recorded.
- [ ] Settings UI: checkbox list grouped by safety level.
- [ ] History persistence: append-only NDJSON under `<config>/spock-adb/`, capped by
      `historySize`, batched writes off the EDT, loaded on start.
- [ ] Tests: disabled-tool error path, NDJSON round-trip and cap, and a regression asserting
      no new tool can perform a destructive action unconfirmed (extend `ToolSafetyTest`).

## Phase 6 — UI/UX polish (P2)

- [ ] Devices tab: show which device MCP clients are targeting — `McpServerService.selectedSerial`
      and the tool-window selection are independent today, which is a real "agent hit the wrong
      phone" trap.
- [ ] MCP panel: stdio session count in the header, reporting only what `McpBridgeServer` can
      actually observe.
- [ ] UI Inspector: promote `TestTagSupport.UNAVAILABLE` from a note to a banner carrying the
      exact `testTagsAsResourceId = true` snippet.
- [ ] `McpServerPanel`: collapse the 72/28 details split below ~500px using the existing
      `CollapsibleSection`.

## Phase 7 — Compatibility gate (P0)

- [ ] `./gradlew detekt test`
- [ ] `./gradlew verifyPlugin` green on all five IDEs; any new finding fixed or justified in
      `verifier-ignored-problems.txt`.
- [ ] `verify-marketplace-descriptor.sh`.
- [ ] Settings migration: state XML written by 4.0.3 loads without the new fields.

## Phase 8 — Documentation (P1)

- [ ] `docs/MCP.md` — new tools, per-tool allow-list; remove them from "Not yet implemented".
- [ ] `docs/AI.md` — new, with the privacy section first: **every chat message and every tool
      result, including screenshots, logcat and package lists, leaves the machine for the
      configured provider.** Context attachment is opt-in per conversation; the plugin adds no
      telemetry of its own.
- [ ] README plugin-description block; `CHANGELOG.md` under `[Unreleased]`.
- [ ] Bump `pluginVersion` to 5.0.0.

---

## Security and privacy ledger

| Risk | Mitigation |
|---|---|
| Chat + tool results sent to an LLM provider | Stated plainly in Settings and `docs/AI.md`; context attachment opt-in; no plugin telemetry. |
| API key | `PasswordSafe` only. |
| `android_pull_file` exfiltration | Path allow-list; prompt outside it. |
| Assistant tool loop | Same default-deny modal, 25-iteration cap, Stop button, shared audit trail. |
| Screen recording | Captures whatever is on screen — said in the tool description; remote file deleted after pull. |
| New network surface | None inbound. The LLM client is outbound-only. |

## Explicitly out of scope

Screen recording beyond the 3-minute platform cap; MCP prompts/resource subscriptions;
per-client session presence (the stateless HTTP transport genuinely cannot observe it);
persistent or multiple named conversations; Layout-Inspector-grade Compose internals such as
recomposition counts (rejected by design — undocumented JVMTI protocol, debuggable builds
only); remote/non-loopback binding; multi-device fan-out; implementing the unrelated
`connectDeviceOverIp` stub.

## Open risks

- Agent-loop correctness under cancellation and partial streaming is the single riskiest item.
- No usage metering in v1; the 25-iteration cap is the only guard against a surprise bill.
- The real-device smoke matrix (API levels, `FLAG_SECURE` screens, offline/unauthorized
  states) stays manual — there is no emulator CI today.
