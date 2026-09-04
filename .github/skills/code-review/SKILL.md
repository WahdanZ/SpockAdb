# Spock ADB Code Review Skill

## Purpose

Perform a deep, production-quality code review for the Spock ADB IntelliJ / Android Studio plugin.

The goal is not to maximize the number of comments. The goal is to identify issues that can affect:

- correctness
- stability
- Android Studio compatibility
- ADB reliability
- developer experience
- UI/UX
- performance
- security
- testing
- Jetpack Compose support
- MCP functionality
- AI functionality
- maintainability

Review the actual repository and implementation before making conclusions.

Never invent issues.

---

# Review Process

Before reviewing a change:

1. Inspect the repository structure.
2. Understand the existing architecture.
3. Identify the affected modules and files.
4. Inspect related implementations.
5. Check existing tests.
6. Check existing IntelliJ Platform APIs.
7. Check existing Android/ADB abstractions.
8. Check existing UI patterns.
9. Check compatibility configuration.
10. Check whether the reported problem is already handled elsewhere.

Review the changed code together with the surrounding code when necessary.

Do not recommend a rewrite unless there is a concrete reason.

Prefer small, maintainable, incremental improvements.

---

# Severity

Use only these priorities:

### P0 — Critical

Use for:

- IDE crashes
- severe runtime failures
- data loss
- security vulnerabilities
- unrestricted destructive operations
- MCP security boundary violations
- functionality that makes the plugin unusable

### P1 — High

Use for:

- major bugs
- broken common workflows
- Android Studio compatibility problems
- incorrect device targeting
- UI freezes
- serious ADB failures
- unsafe MCP behavior

### P2 — Medium

Use for:

- missing error handling
- missing important tests
- architectural problems
- performance problems
- significant UX issues
- important edge cases

### P3 — Low

Use for:

- minor maintainability issues
- small UI inconsistencies
- naming
- documentation
- low-impact refactoring

Do not use severity to represent personal preference.

---

# Review Categories

Every finding must belong to one of:

1. Code Quality
2. Bugs
3. Missing Tests
4. Missing Golden/UI Tests
5. UI/UX Issues
6. Architecture
7. Compatibility
8. Performance
9. Security
10. ADB Reliability
11. MCP
12. Jetpack Compose
13. AI
14. Accessibility

---

# 1. Code Quality

Check for:

- unnecessary complexity
- duplicated logic
- unclear naming
- large classes
- large methods
- dead code
- incorrect abstractions
- poor separation of concerns
- unnecessary dependencies
- incorrect coroutine usage
- blocking operations
- EDT violations
- swallowed exceptions
- poor error handling
- resource leaks
- process leaks
- lifecycle problems

Do not report code style as an issue unless it creates a real maintainability problem.

Follow existing project conventions when they are reasonable.

---

# 2. Bugs

Look for:

- incorrect logic
- nullability issues
- race conditions
- state inconsistencies
- incorrect lifecycle handling
- incorrect device selection
- incorrect package detection
- incorrect Activity detection
- incorrect Fragment detection
- incorrect command construction
- incorrect command parsing
- unexpected ADB output
- timeout failures
- cancellation failures
- concurrent execution problems
- stale state
- incorrect error handling

Pay special attention to:

- multiple connected devices
- emulators
- physical devices
- offline devices
- unauthorized devices
- different Android versions

Never assume the first ADB device is the intended device.

---

# 3. ADB Reliability

For every ADB-related change verify:

- command correctness
- argument handling
- quoting
- escaping
- stdout handling
- stderr handling
- exit codes
- timeout
- cancellation
- selected device
- device disconnects
- emulator behavior
- physical device behavior
- Android version differences
- unavailable commands
- unexpected command output

Prefer structured or stable output formats over fragile text parsing.

Verify that destructive commands require appropriate confirmation.

---

# 4. Android Studio / IntelliJ Compatibility

This is a high-priority review area.

Check:

- IntelliJ Platform APIs
- Android Studio APIs
- deprecated APIs
- removed APIs
- unstable APIs
- plugin.xml
- plugin dependencies
- sinceBuild
- untilBuild
- Gradle configuration
- IntelliJ Platform Gradle Plugin
- Kotlin version
- Java version

Do not recommend changing versions blindly.

When identifying a compatibility issue:

1. Identify the exact API/configuration.
2. Explain the affected versions.
3. Explain the failure mode.
4. Recommend a stable alternative.
5. Recommend compatibility abstraction when necessary.

Do not assume that leaving `untilBuild` empty guarantees compatibility.

If compatibility cannot be verified from the code, explicitly state that it needs Plugin Verifier or platform testing.

---

# 5. UI / UX

Review:

- layout
- spacing
- alignment
- typography
- icons
- action hierarchy
- tool window width
- information density
- scrolling
- dialogs
- empty states
- loading states
- error states
- confirmation flows
- keyboard navigation
- accessibility
- dark theme
- light theme

Follow IntelliJ / Android Studio native UI conventions.

Prefer IntelliJ Platform UI components over custom components when appropriate.

The UI should remain usable in a narrow IDE tool window.

Avoid large dashboards that consume unnecessary space.

Do not redesign working UI without a concrete usability reason.

---

# 6. MCP

Spock ADB provides an MCP interface for Android devices.

Review MCP implementations for:

- tool definitions
- strongly typed arguments
- predictable responses
- error handling
- cancellation
- timeouts
- concurrency
- device selection
- stale state
- request monitoring
- request history
- transport behavior
- client handling

Prefer semantic tools.

Good:

```text
android_get_ui_tree
android_get_logcat
android_take_screenshot
android_launch_app
android_tap_element
android_input_text
android_open_deep_link
````

Avoid exposing everything as:

```text
android_run_adb_command
```

Generic shell execution should be treated as high-risk functionality.

---

# 7. MCP Server UI

If the change adds MCP support, verify that MCP is exposed as a first-class feature.

The MCP panel should provide, where supported:

* server status
* start
* stop
* restart
* connected clients
* available tools
* request history
* live requests
* request details
* request arguments
* request result
* execution duration
* errors
* device used

The UI must not fake client or request information that the MCP transport does not actually provide.

---

# 8. MCP Security

Classify tools into:

### Read-only

Examples:

* device info
* package info
* current Activity
* UI tree
* logcat
* screenshot
* battery information

### Safe actions

Examples:

* launch app
* tap
* input text
* press back
* open deep link

### Destructive

Examples:

* uninstall
* clear app data
* revoke all permissions
* delete files
* arbitrary shell commands

Destructive actions must require explicit confirmation.

Report any path that allows an AI agent to silently perform destructive operations.

Never assume an MCP request is safe simply because it came from an AI client.

---

# 9. Jetpack Compose

Compose is a first-class target for Spock ADB.

Check support for:

* Jetpack Compose
* Compose Semantics
* semantics tree
* testTag
* contentDescription
* roles
* state
* clickable elements
* editable elements
* Compose Navigation
* Material 2
* Material 3
* mixed View + Compose applications

Do not assume Activity/View hierarchy is sufficient.

For Compose applications, consider:

```text
Activity
  └── Compose UI
       └── Semantics Tree
```

For hybrid applications:

```text
Activity
  └── View hierarchy
       └── Compose subtree
```

Review whether the implementation can correctly inspect and interact with Compose UI.

Prefer semantic interaction over coordinate-based interaction.

---

# 10. Compose UI Interaction

Where technically supported, prefer:

* testTag
* semantics
* text
* role
* content description
* accessibility information

over:

* raw x/y coordinates

Coordinate-based interaction should be a fallback.

Review tools such as:

```text
android_get_ui_tree
android_find_ui_element
android_tap_element
android_long_press_element
android_input_text
android_scroll_to_element
android_assert_visible
android_assert_text
android_assert_enabled
```

Verify that their behavior is deterministic and safe.

---

# 11. AI

AI functionality must be optional.

Review:

* provider abstraction
* API key handling
* privacy
* sensitive data
* logcat exposure
* source code exposure
* device information exposure
* prompt construction
* context limits
* timeouts
* failures
* hallucinated actions
* unsafe commands

AI should distinguish between:

* observed facts
* inferred information
* hypotheses
* recommendations

AI must never silently execute destructive commands.

Do not hard-code API keys.

---

# 12. AI + Android Debugging

If AI analyzes Android debugging information, verify whether it can correctly use:

* logcat
* stack traces
* crash information
* ANR information
* current Activity
* current Compose route
* UI tree
* screenshots
* package information
* device information

Prefer combining structured information with screenshots rather than relying only on vision.

A useful debugging context can contain:

```text
Device
+
Application
+
Current Activity
+
Compose Route
+
UI Tree
+
Screenshot
+
Logcat
```

---

# 13. Keyboard Shortcuts

Spock ADB actions should use the IntelliJ Platform Action system.

Prefer native:

```text
AnAction
ActionManager
Keymap
```

over creating a custom shortcut engine.

Review whether important operations are exposed as IntelliJ Actions.

Potential actions:

* Restart App
* Force Stop
* Force Kill
* Clear App Data
* Open Current Activity
* Open Current Fragment
* Open Logcat
* Take Screenshot
* Start Screen Recording
* Open Deep Link
* Start MCP Server
* Stop MCP Server
* Restart MCP Server
* Open MCP Panel
* AI Explain Error

Actions should:

* appear in Find Action
* be configurable in Keymap
* respect context
* be disabled when required resources are unavailable

Do not introduce conflicting default shortcuts.

---

# 14. Context-Aware Actions

Verify that actions behave correctly when:

* no device is connected
* one device is connected
* multiple devices are connected
* the selected device disconnects
* no application is selected
* the application is no longer installed

Never silently run commands against an unexpected device.

---

# 15. Testing

Check for:

* unit tests
* integration tests
* parser tests
* ADB command tests
* device-selection tests
* MCP tool tests
* error handling tests
* concurrency tests
* compatibility tests
* UI tests

The core ADB layer should be testable without a physical device.

Every meaningful bug fix should include a regression test when practical.

---

# 16. Missing Tests

Report missing tests when:

* new business logic has no tests
* a bug fix has no regression test
* ADB parsing has no tests
* MCP tools have no tests
* device selection has no tests
* destructive operations have no tests
* important error states are untested

Do not request tests for trivial getters/setters or framework-generated behavior.

---

# 17. Golden / UI Tests

For UI changes, determine whether golden tests are valuable.

Consider golden tests for:

* MCP panel
* MCP request details
* device selector
* dialogs
* error states
* empty states
* dark theme
* light theme
* narrow tool-window layouts

Only report missing golden tests when visual regression testing provides meaningful value.

---

# 18. Accessibility

Check:

* keyboard navigation
* focus handling
* accessible names
* icon-only actions
* tooltips
* disabled state communication
* screen-reader compatibility where applicable
* semantic labels
* action discoverability

---

# 19. Performance

Look for:

* blocking the EDT
* synchronous ADB commands
* excessive polling
* unnecessary refreshes
* unbounded Logcat
* unbounded MCP history
* large screenshots kept in memory
* repeated device discovery
* expensive parsing on UI thread
* memory leaks
* unnecessary background work

Pay special attention to live Logcat and MCP monitoring.

---

# 20. Finding Format

Every finding must use this format:

### [P1] Short descriptive title

**Category:** Compatibility

**File:** `path/to/file.kt:42`

**Problem:**

Explain exactly what is wrong.

**Why it matters:**

Explain the real impact.

**Recommendation:**

Provide a concrete fix.

**Evidence:**

Reference the relevant implementation or behavior.

Do not write vague comments such as:

> "This could be improved."

Explain the concrete problem.

---

# 21. Code Review Output

Return the review using exactly this structure:

# Code Review

## Summary

One short paragraph describing the overall quality of the change.

## 1. Code Quality

Findings or:

`No issues found.`

## 2. Bugs

Findings or:

`No issues found.`

## 3. Missing Tests

Findings or:

`No issues found.`

## 4. Missing Golden/UI Tests

Findings or:

`No issues found.`

## 5. UI/UX Issues

Findings or:

`No issues found.`

## 6. Architecture

Findings or:

`No issues found.`

## 7. Compatibility

Findings or:

`No issues found.`

## 8. Performance

Findings or:

`No issues found.`

## 9. Security

Findings or:

`No issues found.`

## 10. ADB Reliability

Findings or:

`No issues found.`

## 11. MCP

Findings or:

`No issues found.`

## 12. Jetpack Compose

Findings or:

`No issues found.`

## 13. AI

Findings or:

`No issues found.`

## 14. Accessibility

Findings or:

`No issues found.`

---

# Final Assessment

## Priority Summary

| Priority | Count |
| -------- | ----: |
| P0       |     0 |
| P1       |     0 |
| P2       |     0 |
| P3       |     0 |

## Test Assessment

* Unit tests:
* Integration tests:
* MCP tests:
* UI/golden tests:
* Regression tests:

## Compatibility Assessment

List the important Android Studio / IntelliJ compatibility risks.

## MCP Readiness

Choose:

`Not Ready`

`Needs Work`

`Ready`

Explain the decision.

## Overall Recommendation

Choose one:

* Approve
* Approve with minor changes
* Request changes
* Major rework required

Explain briefly.

---

# Review Philosophy

The goal is to find meaningful engineering problems, not maximize comments.

Prefer:

5 high-quality findings

over:

30 low-value findings.

Never invent issues.

Never report personal style preferences as bugs.

Never request unnecessary rewrites.

Always prioritize:

1. Correctness
2. Stability
3. Compatibility
4. Security
5. Developer experience
6. Maintainability
7. Performance
8. Tests
9. UI polish

```
```
