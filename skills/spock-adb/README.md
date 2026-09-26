# Spock ADB Agent Skill

[`SKILL.md`](SKILL.md) teaches an agent *how* to debug an Android app with the Spock ADB MCP
tools: start from the bounded debug context, narrow to the app, act safely before
destructively, and re-read after every change — with eight playbooks (UI bug, state bug,
crash / ANR, process death, background work, deep links, accessibility, Compose recomposition).

The MCP server gives an agent the tools. This skill gives it the order to use them in. It is
plain Markdown in the [Agent Skills](https://agentskills.io) format, so any client that loads
skills can use it; the example below is Claude Code.

## Set it up in Claude Code

1. Start the server and install its client config into your Android project — see
   [docs/MCP.md, Quick start](../../docs/MCP.md#quick-start). That writes `.mcp.json`:

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

2. Copy the skill into the project's skills directory (or `~/.claude/skills/` for every
   project):

   ```bash
   mkdir -p .claude/skills && cp -R <path to SpockAdb>/skills/spock-adb .claude/skills/
   ```

3. Restart Claude Code. `/mcp` lists `spock-adb`; the skill loads by itself when a task is
   about a running Android app, or ask for it by name ("use the spock-adb skill").

Other clients: put `SKILL.md` wherever the client reads skills or custom instructions from. The
server config for each transport is in [docs/MCP.md](../../docs/MCP.md).

## Try it on the sample app

The [sample app](../../sample/README.md) has a screen for every playbook. Install it with
`./gradlew -p sample :app:installDebug`, open it on the device, then ask:

| Playbook | Open in the sample | Ask the agent |
|---|---|---|
| Crash / ANR | *Debug context* | "Something on this screen is failing. What?" |
| UI bug | *UI Inspector — Compose reliability fixtures* → *Taps* | "Tap the Save button and tell me what happened." |
| State bug | *Storage* | "What does the app have stored in settings.xml, and does the screen match?" |
| Process death | *Process death* (press *Count* a few times first) | "Which of these counters survive process death?" |
| Background work | *Background work* | "Why hasn't the constrained WorkManager job run?" |
| Deep links | any | "Check that `spocksample://open/item/42?ref=spock` opens item 42." |
| Accessibility | *UI Inspector — Compose reliability fixtures* → *Audit* | "Audit this screen for accessibility problems." |

A good run for the process-death prompt: the agent reads the counters, calls
`android_simulate_process_death`, reads them again, and reports that only the saved-state
counter survived, with the old and new pid.

## Keeping it true

`SkillToolNamesTest` fails the build when this skill, the README or `docs/MCP.md` names an
`android_*` tool the registry does not have, or when the skill's list of destructive tools
differs from the registry's. Rename or add a tool, and the test says which document to update.
