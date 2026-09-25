# Documentation index

Four documents live here, and they are not interchangeable — each is written for a different
reader with a different question. This page exists so you land on the right one first.

| Document | Read it if you're asking | Audience |
|---|---|---|
| [MCP.md](MCP.md) | "How do I let an AI agent (Claude Code, Cursor, …) drive my device?" | Anyone installing or using the MCP server |
| [AI.md](AI.md) | "What does the in-IDE Assistant do, and what leaves my machine?" | Anyone enabling the in-IDE AI assistant |
| [COMPATIBILITY.md](COMPATIBILITY.md) | "Which Android Studio / IntelliJ IDEA versions are supported?" (the table at the top) — or "why does `verifyPlugin` keep catching things I didn't expect?" (everything after it) | Users (the table) and contributors changing `sinceBuild` (the rest) |
| [PLAN-5.0.md](PLAN-5.0.md) | "How was the AI-native 5.0 release actually built, phase by phase?" | Contributors and maintainers — not user-facing documentation |

If none of those match what you're trying to do, the [README](../README.md) covers day-to-day
use of the tool window, and [CONTRIBUTING.md](../CONTRIBUTING.md) covers setup, building and
releasing.

## Why a separate MCP.md and AI.md

Both sit on the same machinery — one `ToolRegistry`, one safety model, one audit trail — but
they answer different questions. MCP.md is about an *external* client you configure and point
at the plugin. AI.md is about a model running *inside* the IDE that uses the same tools without
you having to configure a client at all. The privacy implications differ (a remote client you
chose vs. whatever provider you configure in Settings), so they're kept as separate documents
rather than sections of one, even though the tool safety model they describe is identical.

## Why PLAN-5.0.md is here but isn't "documentation" in the usual sense

It's a working plan — phases, decisions taken and why, what's landed so far — kept in version
control because that's where the reasoning for those decisions is easiest to find later. It
isn't written for someone installing the plugin; it's written for whoever picks up the next
phase. If you're looking for how to *use* the AI assistant or the MCP server, MCP.md and AI.md
are the current, user-facing description of what already ships — PLAN-5.0.md describes how it
got there.
