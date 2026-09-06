# The in-IDE AI assistant

`Spock ADB → Assistant` asks a model about the device in front of you and lets it use the
plugin's own tools to find the answer, instead of describing what is usually true of Android.

It is the same machinery as the MCP server, pointed inwards: the same `ToolRegistry`, the same
per-call confirmation on destructive tools, the same per-tool switches, and the same activity
history. Nothing the assistant can do is something an external agent could not; the difference
is that you do not have to configure a client to get it.

## What leaves your machine

**Everything the assistant reads is sent to the provider you configure.** Your questions, and
every tool result the model asks for — screenshots of the device, logcat output, installed
package lists, the view hierarchy, whatever the tools return.

That is not a footnote. A screenshot of a staging app, a logcat line carrying a token, a package
list that names an unreleased product: all of it goes to the provider in the request body. The
plugin adds no telemetry of its own and sends nothing anywhere else, but it cannot make the
provider's copy go away.

Three things follow, and the plugin is built around them:

- **The assistant is off until you give it a key.** No key, no requests.
- **Attaching device context is a toggle, and it applies to the first message of a conversation
  only.** Repeating it every turn would resend a stale snapshot and pay for it each time.
- **Turning tools off turns off what the model can read.** `Settings → Tools → Spock ADB → Tool
  Access` is the control: switch off `android_take_screenshot` and no screenshot can be sent,
  because the tool refuses rather than returning an image. See [MCP.md](MCP.md#turning-tools-off).

If you are working on something that must not leave the building, the honest answer is not to
enable the assistant.

## Setting it up

`Settings → Tools → Spock ADB → AI Assistant`.

| Field | Notes |
|---|---|
| **Provider** | Anthropic, or any OpenAI-compatible `/chat/completions` endpoint. |
| **Model** | Empty means the provider's default. An OpenAI-compatible endpoint has no sensible default, so it has to be named. |
| **Base URL** | Empty means the provider's default. Point this at a gateway or a local server if you have one. |
| **API key** | Stored in the IDE password safe, never in a settings file. |

The key field is **write-only**: a key already stored is never read back into it. A settings
screen that renders a secret puts it in every screen share and screenshot for no benefit, since
you cannot verify a key by looking at it. Leaving the field blank keeps whatever is stored;
**Remove Key** is how you clear it.

## Using it

Type a question and press **Ctrl+Enter** (or Send). **Esc** stops.

**Attach debugging context** runs `android_get_debug_context` once, at the start of a
conversation, and prepends the result: the current activity, the UI semantics, recent logcat.
That is most of what "why is this screen wrong" needs, in one call rather than four the model
has to know to make. Switch it off when your question is not about the current screen.

Stopping ends the turn at the next step rather than tearing down the connection mid-request. If
the model was about to run tools, none of them run — clearing app data and *then* noticing Stop
was pressed is not a race worth having.

**Clear** starts a new conversation. The model keeps context across turns within one
conversation, so a follow-up question does not re-read the device unless it needs to.

## What it can do to your device

Exactly what the tool list allows, under exactly the same rules as an external agent:

- Read-only and safe-action tools run automatically.
- **Destructive tools ask you, per call, in the IDE's own modal** — never as an inline chat
  approval, which is far too easy to wave through. Denial is the default.
- Tools you have switched off refuse, and say so, and the attempt is recorded.

Every call appears in the MCP panel's Activity tab with the client shown as `spock-assistant`,
next to what external agents did. One place answers "what touched my device".

## Limits

- **25 iterations per turn.** The model ⇄ tool cycle stops there and says so. It is the only
  guard against a runaway bill in this version, so it is deliberately not generous; a debugging
  task that genuinely needs more is one to drive by hand.
- **No retries.** A provider error is shown verbatim and not retried — retrying a rejected
  request spends money to be rejected again.
- **Screenshots are described to the model, not inlined, on later turns.** A base64 image resent
  on every turn would fill the context window and cost real money each time.
- **One conversation per IDE window.** Closing the tool window cancels a turn in flight.
