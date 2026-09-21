# Changelog

## [Unreleased]

### Added

- **See why background work has not run, and run it on demand.** A new **Background Work** tab
  shows the selected app's JobScheduler jobs, WorkManager's workers among them. For each job it
  shows the id, the service, whether it is periodic, the constraints holding it back right now,
  backoff, failure count, and next and last run. It also shows the app's pending alarms, with
  their next trigger as a device clock time and their repeat interval. **Run Now** forces the
  selected job with `cmd jobscheduler run -f`, and is disabled with the reason on devices that
  cannot do that. It finds a job's namespace on its own, which matters because WorkManager 2.10+
  puts every job in one on API 34. For a WorkManager job it says that WorkManager may still skip
  periodic or backed-off work that is not due, which was seen on a device, instead of claiming the
  Worker ran. A dump that does not parse is shown raw instead of as an empty list. Agents get
  the same through `android_get_scheduled_jobs` and `android_get_pending_alarms` (read-only), and
  `android_run_job_now` (safe action). Device conditions (Doze, standby buckets, battery) are left
  for a follow-up. (#89)

### Fixed

- **Developer options no longer stop responding after a failed read.** When the device refused a
  shell command — an emulator whose adbd has run out of file descriptors answers every one with
  `closed` — the read behind the section threw out of its background task, which the IDE reported
  as an internal error. Worse, the switches and animation scales had their listeners removed for
  the read and only got them back when it succeeded, so none of them did anything until a later
  read happened to work. A failed read is now logged, the controls keep showing what they showed,
  and they keep working.
- **A deep link that the device refused no longer reports success.** The Deep link field ran
  `am start`, threw away everything the device said, and showed `Opened deep link …` in a success
  notification — for a URI no activity handles, and for one that resolved to an activity the
  device refused to start. `android_open_deep_link` was closer but decided by looking for the
  word `Error`, which a `Permission Denial` trace does not contain. Both now run `am start -W`,
  parse what came back through one shared reader, and say which it was: opened, naming the
  activity that claimed the link, which is the thing you actually wanted to know; unhandled,
  naming the package when the intent was scoped to one; refused, with the device's own words
  attached; or brought to the front, which is a success that says so plainly — the existing task
  is resumed and the link is *not* delivered to the activity, so "Opened" was the wrong word for
  it too. The reverse mistake is guarded just as carefully, because a link that did open being
  called a failure is no better: output in wording the plugin does not recognise, `am` giving up
  on waiting, and a slow cold start that outlasts the device's idle timeout are all reported as
  sent-but-unconfirmed rather than guessed either way — and only lines the device itself printed
  decide any of this, never the URI it echoes back, so opening `myapp://help/SecurityException`
  no longer reports a refusal that never happened

## [4.0.4] - 2026-09-20

### Added

- **Connect an MCP client without handing out a credential, and revoke one that got out.** The
  panel had a single **Copy Config** button, and it copied the one configuration that *is* a
  secret — the HTTP form with the session token written into it — with a warning that arrived
  only once it was already on the clipboard. Pasted into a chat, that token is a live credential
  for the device and the filesystem, and there was no way to retire it short of quitting the IDE
  and hand-editing `spock-adb-mcp.xml`: `regenerateToken()` existed and had no caller anywhere.
  **Copy Config** is now a menu, tokenless forms first — the stdio configuration, and an HTTP one
  whose header reads `${SPOCK_ADB_MCP_TOKEN}` from the client's environment — with the literal
  form kept for clients that cannot expand environment variables and confirmed *before* anything
  is copied. **Install into this project** writes the configuration into the project's
  `.mcp.json` instead of routing it through the clipboard, merging into whatever servers are
  already in that file and refusing rather than overwriting a file that is not valid JSON; a
  configuration pasted into a chat never connected a client in the first place, since a client
  only reads its own config file. **Rotate Token** invalidates the current token, restarts the
  server, and offers the matching `export` line once — stdio clients re-read the token file and
  need no change. All four are also actions: `Spock: Install MCP Client Configuration Into This
  Project` and `Spock: Rotate MCP Token` join the two copy actions. Installing asks which
  transport to write — the axis is what the client can do, spawn a process or open a URL —
  because neither entry can leave this machine: stdio names this machine's JDK, plugin jar and
  IDE config by absolute path, and the HTTP URL names the port the OS handed this IDE on first
  start. `.mcp.json` in a project root is a file teams share, so after either install it offers
  to add it to the project's `.gitignore`, and only when that file does not already say so
- **View and edit an app's SharedPreferences and DataStore, without clearing data or adding a
  debug menu.** Reproducing a bug that depends on stored state meant clearing data and walking
  back through the app, and inspecting that state meant pulling a file through `run-as` and, for
  DataStore, decoding protobuf by hand. A new **App storage** section in the Devices tab lists every
  `shared_prefs/*.xml` and `files/datastore/*.preferences_pb` file of a debuggable app — the open
  project's app is selected and listed as soon as a device is, and any other installed app can be
  picked from a list or typed — and shows
  each as a typed table — boolean, int, long, float, double, string, string set, bytes — to edit,
  add to or delete from, then **Apply**. Apply force-stops the app first, because a running app
  writes its in-memory preferences back on its next `apply()` and silently undoes the edit. It
  refuses if the file changed since it was read — checked before the stop, so a stale edit costs
  nothing, and again after it — then writes, and reads the file back rather than trusting the
  write. **Revert last apply** restores what the file held before, and **Export** and
  **Import** save a known state and put it back. Nothing the editor does not understand is lost:
  unknown XML elements and unknown protobuf fields survive an edit, and the DataStore format is
  parsed by hand, so the plugin gains no protobuf dependency. EncryptedSharedPreferences are shown
  read-only, and Proto DataStore files with the app's own schema are listed as unsupported rather
  than decoded as garbage. Agents get the same through `android_list_app_storage` and
  `android_read_app_storage`, which are read-only, and `android_set_app_preference` and
  `android_delete_app_preference`, which are destructive and ask first, naming the value before
  and after. The `run-as` quoting and refusal handling from Clear Cache now lives in one shared
  helper; one consequence is that when a script ran and failed, its own error is reported rather
  than being mistaken for `run-as` failing to reach the app
- **Route a device's traffic through a local debugging proxy, without leaving the IDE.**
  Pointing a test device at Charles, Proxyman or mitmproxy meant dropping to a terminal,
  remembering `settings put global http_proxy`, and — the part that actually bites —
  remembering to clear it afterwards. A device left pointing at a proxy that is no longer
  listening fails every request with nothing on screen to say why, and the next person to
  pick it up has no reason to suspect the proxy. The Network section now has an HTTP proxy
  field with Set and Clear, and the value is remembered between sessions so it does not have
  to be retyped. Beneath it the panel shows what the selected device actually holds — direct,
  via a host, or unknown when it cannot be read — refreshed when a device is selected or
  reconnects and after every Set and Clear, so a proxy left over from yesterday is visible
  rather than rediscovered, and a cleared device does not look proxied just because the field
  still holds the last value. The same operations are exposed to agents as
  `android_get_http_proxy`, `android_set_http_proxy` and `android_clear_http_proxy`, sharing
  the panel's write-and-read-back step rather than keeping a second copy of it. Setting a
  proxy asks the developer first, naming the host: it destroys nothing, so by the letter of
  the safety model it is a safe action, but it redirects *all* device traffic through a host
  and survives a reboot, which is not something an agent should be able to leave behind
  unnoticed. Clearing is a safe action, and reading is read-only, so an agent can always
  check the state before and after without needing approval for the check. Both mutations,
  from the panel and from agents, read the value back and report what the device holds
  rather than what was asked for — `settings put` exits 0 even where the write does not
  take, and a developer told the proxy is set while traffic still goes direct has no way to
  tell which half is lying. An IPv6 proxy has to be bracketed, as in `[::1]:8888`; unbracketed,
  `fe80::1` is refused rather than quietly read as host `fe80:` on port 1
- **Clear Cache, without losing everything else.** The only way to reset an app from the panel
  was Clear Data, which runs `pm clear` and takes the login session, databases and shared
  preferences with it — so every test of an image or HTTP cache cost a re-login and a re-seed.
  A new **Clear Cache** button, and a matching `android_clear_app_cache` MCP tool, delete only
  the app's internal `cache/` and `code_cache/`. It goes through `run-as` with relative paths
  rather than `pm clear --cache-only`, because a device that predates that flag ignores it and
  clears the package in full — a silent total wipe when a cache drop was asked for. The price is
  that it needs a debuggable build; on a release build it says so and points at Clear Data.
  Success is not inferred from a silent `rm` either — the `rm` reports its own exit status in
  the same command, so a failure is reported as one instead of being announced as a clear. It
  asks no confirmation, since nothing it deletes is something the app cannot rebuild
- **Details on demand for the selected Logcat line.** Selecting a line opens a pane beneath the
  list with its time, level, tag, PID/TID and owning app, the raw text as the device sent it,
  and — the part that used to mean leaving the IDE for a terminal — the whole exception report
  the line belongs to, reassembled from the records around it, with **Copy raw line** and **Copy
  stack trace** beside it

### Changed

- **The in-IDE AI Assistant is not offered in this release.** The Assistant tab, its
  *Open AI Assistant* IDE action and its settings section are hidden, and the Logcat tab has no
  `Ask AI` control — there is no way to reach any of it from the UI, and the panel is not even
  constructed, so nothing reads an API key out of the keychain for a feature nobody can open.
  Nothing is deleted: the agent loop, the tool gate and its confirmations, the audit trail, the
  Logcat context builder and its redaction step are all intact and still under test, behind a
  single `AssistantFeature` switch. Stored settings are untouched, so turning it back on finds
  the configuration as it was
- **Logcat: scope and filter are two questions, not one list of presets.** `Current app`,
  `Errors only`, `Crashes`, `ANRs` and `Network` were one dropdown, so they could not be
  combined and each one silently answered a question it had not been asked: choosing `Crashes`
  widened the view from the app to every process on the device, and choosing `Current app` reset
  the level and emptied the search box. They are now two independent controls — a **scope** of
  `App`, `Related` or `All`, and a **filter** of `All logs`, `Errors`, `Crashes`, `ANRs` or
  `Network` — so `App + Crashes`, `Related + Errors` and `All + ANRs` are all expressible, and a
  filter can never move the scope out from under you. `Related` is the new one: the app's own
  processes plus the system components that act on it — `AndroidRuntime`, `ActivityManager`,
  `ActivityTaskManager`, `WindowManager`, `ConnectivityService`, `PackageManager` and the crash
  reporters — plus any system line that names the package, such as an `ANR in` report. It is
  deliberately a short list: a `Related` that admits the general run of system chatter is
  indistinguishable from `All`, and sends you back to `App` having lost the one line that
  explains what happened to your process. Scope and filter are separate fields in the model
  rather than a UI arrangement, so what each one does is fixed by its own tests
- **Logcat shows logs, not controls.** The toolbar carried eleven controls at equal visual
  weight across two rows, which in a docked tool window left the log itself a minority of the
  panel. It is now one reflowing row of what is used continuously — live/pause, scope, level,
  filter, search, `Ask AI` — with clear, export, copy, regex, auto-scroll, details and stop
  moved behind **⋯**. Start, Stop and Pause were three buttons for what a developer thinks of as
  one switch, so they are one: the first control starts the stream and thereafter pauses and
  resumes the view, with the stream left running so nothing is missed, and Stop — which lets go
  of the device — is in the overflow where it belongs
- **Logcat rows are readable at a glance.** Every row was drawn in one colour chosen by
  severity, so a screen with a few errors on it read as a wall of red, and each row began with a
  timestamp, a PID and a TID in the same weight as the message — the same three numbers on every
  line, crowding out the only part anyone reads. Rows now follow the reading order: a dimmed
  time, a small colour chip for the level, the tag in a secondary colour, and the message at full
  contrast. Colour is spent only where it carries information — the chip on every line, and the
  message itself for a crash, an ANR or an error. PID and TID are not in the row at all; they
  are in the details pane, one click away
- **Clicking one line of a multi-line log shows the whole thing.** An HTTP interceptor printing
  a JSON body, an exception and its frames, a tombstone — each is one log statement that reaches
  logcat as one record per line, and selecting any of them showed that line alone, which is
  exactly the moment the panel stopped being useful: clicking `"count": 12,` should give you the
  body it belongs to. The details pane now shows the whole block, named and counted in its
  heading, with a copy action for it. A block is either a stack trace, recognised by its shape,
  or a burst — consecutive records from the same process, thread and tag, each within 400ms of
  the one before — so a chatty tag does not collapse into one enormous group and two bursts a
  second apart stay two blocks
- **The log can be an editor, so text selects across lines — and is, by default.** It was a
  `JBList` with a cell renderer, which is the obvious choice and the wrong one for this: a list
  cell is all-or-nothing, so you could select *rows* but never drag across part of a message or
  from the end of one line into the next — the thing everyone does in a terminal. There is no
  way to add that to a list; text selection is what an editor is. The default view is now a real
  IntelliJ editor in viewer mode, which brings character-level selection, the platform's own
  copy, find-in-view and a scrolling model that does not fight you. **Row View**
  in the ⋯ menu switches back to the row list, which draws each record as a discrete thing and
  can paint what text cannot — its level marker is a coloured chip rather than a letter. The
  choice is remembered between sessions, and both views sit behind one interface, so the panel
  above them does not know which it is talking to. Streaming is unchanged in shape — batched on a timer, one document edit
  per batch rather than one per line — and auto-scroll no longer moves the caret, so a selection
  survives lines arriving underneath it. The line-to-record mapping is exact and a message
  carrying a newline can no longer split a record, which would have shifted every entry after it
  out of step with the screen
- **Selecting several lines keeps the details pane, and describes the selection.** Multi-row
  selection was supported and looked as though it was not: the pane appeared only for exactly
  one row, so highlighting a run of lines made it disappear — in the one case where the
  developer has said most clearly what they are interested in. A selection now shows its own
  shape — how many lines, which tags, which processes, the time span, the levels present — and
  copies raw, because a chosen set of lines is usually on its way into a bug report
- **Records with no message are no longer shown.** A device emits them — every `adb shell log`
  invocation ends with one — and they arrive as blank rows that cost a line of screen and say
  nothing. The raw record stays in the buffer
- **`App` scope can no longer show another process's logs.** An unresolved app was an empty PID
  set, and an empty set matched *everything* — so App and Related behaved exactly like All in
  the most ordinary moments of Android development: the second after pressing Live, an app that
  had been force-stopped, a `pidof` the device refused to answer. The toolbar said App, the
  panel filled, and nothing on screen suggested those lines belonged to other processes until
  they were in a bug report. The four states an empty set was standing in for — not asked,
  asking, asked and not running, asked and failed — are now named, and only a running app
  carries PIDs to match. App shows nothing it cannot prove is the app's; Related still works
  unresolved, because its other rules — related tags, lines naming the package — need no PID
- **`App` scope survives a process restart.** `pidof` answers once, and an Android process
  restarts constantly — Apply Changes, a force-stop, a crash, the system reclaiming memory.
  After any of those the filter was still matching a PID that no longer existed, so the app's
  own new logs were the ones being hidden, and the panel looked like an app that had stopped
  logging. The device announces both events with the PID in the line, so the scope now follows
  the app from the log itself, with no extra `adb` call
- **Clearing no longer freezes the IDE.** `logcat -c` is a blocking shell command with a
  ten-second timeout, and Clear ran it straight from a menu action — on the EDT. An unplugged,
  sleeping or wedged device would have frozen Android Studio for the full ten seconds. The view
  empties immediately, the device command runs on a pooled thread, and a failure is reported
  rather than leaving the developer believing a clear that never happened
- **Late replies can no longer talk over the device you switched to.** A `pidof` result, a
  stream's end-of-stream callback and a clear's outcome all arrive late and from other threads;
  each is now tagged with the session it belongs to and ignored if the panel has moved on.
  Switching devices also resets the buffer, the scope and the view, instead of leaving one
  device's lines under another device's filter
- **Typing in the search box no longer rebuilds the view per keystroke.** Every character
  re-filtered up to 20,000 records and rebuilt the whole document; typing "checkout" did that
  eight times. It is debounced, and a filter change is capped to the same visible limit the
  incremental path enforces — previously a filter change or a view switch could render twice it
- **The panel says when a scope could not be applied.** With the app not running there are no
  PIDs to filter by, so `App` quietly shows every process — the most misleading state the panel
  has, because it looks exactly like a working one. The status bar now says `App scope not
  applied — the app is not running`, from the same rule the AI context header uses, so the two
  cannot drift apart
- **A line that continues the statement above repeats none of its labelling.** One
  `Log.d` carrying a JSON body reaches logcat as one record per line, each with its own
  timestamp, so a twelve-line body arrived as twelve copies of `17:01:18.639  ApiClient` down
  the columns the eye lands on first — the body itself read last. Time, level and tag all belong
  to the statement rather than to each of its lines, so both views now blank all three for a
  continuation, holding the space so nothing moves. What counts as a continuation
  is the same rule the details pane uses to decide what a block is, so the two cannot disagree
- **A tag is printed when it changes, not on every line.** A burst of lines from one tag — an
  HTTP interceptor logging a request, a body and a response — repeated that tag down the whole
  column, twenty rows of `ApiClient` saying nothing in the position the eye lands on first. The
  tag now appears only when it differs from the line above; the space it would have taken is
  kept, so the message column does not move and the run still reads as one block. Read from the
  model rather than from what is on screen, so scrolling never changes which rows show a tag
- **Copying a log line is now the keyboard shortcut and the right button.** Copy lived only in
  the overflow menu, three clicks from the most common thing anyone does to a log line. The
  platform's own copy shortcut works on the list, and right-clicking offers **Copy Lines** (the
  raw records), **Copy Messages** (without the `10-04 12:34:56.789 3189 3189 E AndroidRuntime:`
  prefix, so a stack trace pasted into an issue is still a stack trace an IDE will link) and,
  on an exception, **Copy Stack Trace** for the whole report. Right-clicking outside the
  selection acts on the row under the cursor, as every list in the IDE does; inside it, the
  selection is kept, so a multi-line copy is not lost to a stray click
- **The MCP session token now lives in `PasswordSafe` rather than in a settings file.** It was
  stored as a plain attribute in `spock-adb-mcp.xml` — a credential for the connected device
  *and* for the filesystem, sitting in a file that IDE settings sync copies between machines,
  that backup tools pick up, and that anything running as the developer can read without asking
  the OS for anything. The LLM API key beside it had always been kept in the keychain; the more
  dangerous of the two secrets was the one in the clear. A token written by an earlier version
  is moved into the keychain on the first startup after updating and the attribute is cleared,
  so clients already configured against it keep working; the settings property is retained under
  its old serialised name purely so those files can be cleaned up. The token is read once per
  IDE session and cached, because both transports check it on every connection and a keychain
  read per connection would be a prompt on some platforms. The `600` descriptor file the stdio
  launcher reads is unchanged — a separate process has no other way to authenticate
- **One refresh button in the header instead of two.** The device picker and the app picker each
  carried their own, drawn with the same icon and sitting side by side, so the only way to tell
  which list was about to be re-read was to hover and read a tooltip. They were never two ideas:
  refreshing devices already reloads the apps, because the reply runs through `setDevice`, which
  loads the app picker. The app picker's button is gone and the one that remains sits against the
  device combo and says it re-reads both
- **App storage browses the whole of an app's data, not just its preference files.** The panel
  listed `shared_prefs` and `files/datastore` and nothing else, which answered "what can I edit"
  and no other question — a developer looking for the database their app had just written, or
  wanting to confirm a cache was empty, could not see that any of it existed. It is a tree now:
  `databases`, `files`, `cache`, and whatever else the app has written, read one directory at a
  time as they are opened, because an app's cache can hold thousands of files and reading them
  to draw a row nobody expanded costs a round trip for nothing. **Listing is not editing.** A
  preference file opens in the table and can be written as before; everything else is shown as
  read-only text, and the boundary that decides which is which is the same one it always was —
  a write takes a file the editor has classified, which is why `android_set_app_preference`
  reaches exactly the files it always did
- **The Device tab is cards, and it says what the app actually is.** Seven titled separators
  read as one long list, so the grouping had to be read before it could be seen; each group is
  now a card with a heading and an icon, still collapsible and still remembering what you
  collapsed. They lay out in as many columns as the width allows — one in a tool window docked
  at 300px, two or three in a wide one, where a single column left the right-hand half empty.
  A new **App information** card answers which app the header is naming: package, version and
  build, UID, and whether it is running — a debug build and a release one look identical by name
  alone, and "not running" is what explains why Force stop appeared to do nothing. **Permissions**
  now says `8 granted / 2 denied` above its buttons, where the only way to see what the app held
  was to open the dialog and read a list. **Wi-Fi** names the network it is joined to rather than
  only saying the radio is on — an enabled radio with no connection read exactly like the office
  network. Destructive is **Danger zone**, and the three actions that destroy something are
  outlined in red rather than looking like the four beside them. Quick actions starts with
  Restart app, Attach debugger and Current activity pinned, so the row is useful before anybody
  has pinned anything; pin or unpin once and your list is the list
- **One tool window instead of seven tabs that each found their own way to a device.** The
  plugin registered six IDE content tabs; the Devices tab owned the device dropdown and pushed
  its choice at the others, so Logcat, Commands and the UI Inspector showed no sign of what they
  were attached to — and the app was nowhere at all, because every action resolved the open
  project's app module for itself. There is now a single content: a header naming the device and
  the app, a row of tabs beneath it, and a status line under those. What is chosen in the header
  is what every tab and every action uses, so **the app is a real choice** rather than whatever
  the project happened to resolve to — App storage takes its app from there too, instead of
  carrying a second picker that could disagree with the first. The status line keeps the last
  result on screen with how long it took (`✓ App restarted · 420 ms`), where a balloon said it
  once and went away. The tab row shows as many tabs as fit and puts the rest behind **More**,
  so a tool window docked at 300px still has its content rather than four rows of tabs. The tab
  you are on is drawn as such — the accent colour and an underline — because a toggle button in
  the IDE's own look is all but indistinguishable selected from not, which left the open tab to
  be inferred from whatever was below it
- **The device and the app an action is about are now pinned above the Devices tab.** The
  device dropdown was the first row of a scrolling column, so by the time you had scrolled to
  Network or App storage it was off screen — and the app was never on screen at all: every app
  action resolves the application ID of the open project's app module, which lived only in the
  project's build files. With two projects open there was nothing to say which app **Clear
  data** was about to empty. Both now sit in a fixed header: the device, and under it the
  package with "from this project" beside it, or "not resolved" when Gradle sync has not
  produced one yet. The dropdown itself carries the device name and Android version, with the
  serial, API level and architecture moved to its tooltip, where they no longer push the name
  out of a docked tool window
- **The Network section says what the device is set to before asking you to change it.**
  "Wi-Fi" and "Mobile Data" were two buttons that toggled: they said neither what the device
  was doing nor what pressing them would do, so the way to find out was to press one — which
  is how you switch off the connection you were using. Each is now a row reading `Wi-Fi  On
  [Turn off]`, read from the device when a device is selected, when the tab is shown, and
  after every toggle, because `svc` exits 0 whether or not the device honoured it. The proxy
  field gained a `192.168.1.10:8888` placeholder, its **Clear** button is now **Remove
  proxy** — it changes the device, not the field — and the line beneath it reads **Active
  proxy** rather than **Device**, since the field above it holds what Set *would* apply
- **App storage grew a file path, two searches, and a count of what Apply would write.** The
  editor is no longer a fixed 420px box: it takes whatever height the sections above it leave,
  so collapsing them or undocking the tool window gives the file list and the table the room.
  The open file's full path is shown above the table — an app routinely keeps the same name
  under both `shared_prefs` and `datastore` — with a **Search files** field over the list and a
  **Search keys** field over the table. Above **Apply changes** a line now says `3 unsaved
  changes`, or names the row that cannot be written yet rather than waiting for Apply to
  refuse. **Undo Apply** is now **Revert last apply**: it wrote the file back to what the
  device held before the last apply, and read as "discard what I typed"
- **Quick actions: pin the ones you use, in the order you want them.** The tab is fifteen
  buttons of near-identical visual weight under six headings, so the two or three somebody runs
  twenty times a day sit wherever the grouping happened to put them — often two sections down,
  behind a heading that has to be kept expanded. Right-click any action to pin it to a **Quick
  actions** row at the top; pinned buttons can be dragged over one another, or moved with
  **Move left** / **Move right** in the same menu. Pinning moves the button rather than copying
  it: the same action twice on one screen is worse than either place alone. The order is
  remembered between sessions
- **A search over the actions.** The header has a `Search actions…` field that narrows the tab
  to the actions whose name or tooltip matches every word typed, in any order — and opens the
  sections holding a match, since a match inside a collapsed section is one you cannot see.
  Clearing it puts the tab back exactly as it was, expansion included: the search never
  switches an action off, it only hides it for as long as it is being searched. Sections with
  no action buttons — Developer options, Network, Send to device, App storage — match on what
  they hold, so "proxy" finds the proxy field and "animation" finds the scales. A search that
  matches nothing says so rather than leaving the tab blank
- **Developer options reads as a form rather than two columns at opposite edges.** The
  animation labels sat at the far left and their dropdowns at the far right, so matching a
  setting to its value meant tracking across the width of the tool window. Label and dropdown
  now sit side by side, the three dropdowns share a width, and there is more air between groups
  than between the controls inside one. The values read **Off**, **0.5×**, **1×** — what the
  system settings screen calls them — rather than `0.0`, `0.5`, `1.0`, and a scale that is not
  **1×** is shown in bold, with a **Reset animation scales** button that is enabled only when
  there is something to reset
- **MCP activity is a table with headings, and the server's status stays put.** A row carried
  a tick and a cross side by side — one for how much the tool was allowed to do, the other for
  whether the call worked — with no headings to say which was which. They are two questions, so
  they are now two of five columns: **Time · Tool · Access · Result · Duration**, with the
  unlabelled `Any` dropdown above them now labelled **Result**. Selecting a call puts the error
  first, before the request that caused it, and long messages wrap instead of running off the
  edge; request and response are laid out over several lines rather than arriving as one. The
  generic **Copy** is **Copy details**, and copy request and copy response are disabled when no
  call is selected. On the Tools tab each of the fifty-odd identifiers now carries its one-line
  description. "Configuration copied" no longer overwrites the transports and the tool count:
  it appears beside Copy Config and takes itself back down, and the line about clients now
  reads "No client connected yet. Copy the configuration to connect one.", with the protocol
  reason moved into its tooltip
- **The HTTP proxy field remembers every proxy you have set, not just the last one.** One
  remembered value covered the developer who always points at the same Charles; it did nothing
  for the one switching between a local proxy and a device lab, who retyped the other one every
  time. The field is now a dropdown of the proxies set on this machine, most recent first —
  setting one again moves it up rather than listing it twice, and the list holds eight, so it
  stays a working set rather than a log of everything ever typed. Picking one fills the field
  and nothing else: it reaches the device when **Set** is pressed, never on a click in the
  dropdown. Right-click the field to forget the list, which changes nothing on the device. The
  single proxy remembered by an earlier version becomes the first entry rather than being lost
  to the upgrade
- **The Commands tab says what it is about to run on, and what happened when it did.** The
  target device was a line at the bottom of the panel, far from Run; it now sits under the
  command it applies to. The field shows an example of what goes after `adb shell`. The state
  of a run is reported beside the output — **● Running…**, **✓ Completed in 0.4 s**, **✗ Exit 1
  after 0.2 s**, **⊘ Stopped after 30.0 s** — where before, a command that failed, one that was
  cancelled and one that worked all ended in "Done.". The exit status is asked for in the same
  shell, because ddmlib's gives none. History entries carry the time they were run, favourites
  have a dropdown of their own rather than a starred handful at the top of fifty recent
  commands, and **Find** sits with the output it searches instead of with the input
- **Actions are labelled with what they do.** **Debugger** is **Attach debugger**, **Process
  Death** is **Simulate process death**, **Manage…** is **Manage permissions…**, **Open on
  Device** is **Open developer options**, **Clear & Restart…** is **Clear data and restart…**,
  and the Commands tab's **Favourite** is **Add to favourites**, becoming **Remove from
  favourites** once a command is saved. **Current activity** and **Current fragment** say in a
  tooltip that they open the class in the editor

### Fixed

- **The assistant read the API key on the UI thread, stalling the tool window as it opened.**
  Building the panel asked whether the assistant was configured, and answering that reads the key
  out of `PasswordSafe` — the OS keychain, which blocks. It ran twice on the EDT before the tool
  window had finished appearing, and the IDE reported it as `Slow operations are prohibited on
  EDT` with this plugin named as the cause. The panel now reads the configuration on a pooled
  thread and repaints from the answer, keeping a snapshot for the repaints that only change which
  buttons are enabled; the same blocking read in the send path is gone with it. A key changed
  while the panel is open is still picked up — the read is repeated when the panel is shown and
  when the Settings dialog closes, and a send is checked again on the pooled thread that runs it
- **The Settings dialog read the key on the UI thread as it opened.** `Settings > Tools > Spock
  ADB` says whether a key is stored for the selected provider, and answering that read the
  keychain from `reset()`, so the dialog stayed shut while it waited — and did it again on every
  provider switch. The read moved to a pooled thread, and a reply that arrives after the dialog
  has closed, or after the provider has changed again, is dropped rather than painted. Storing
  and removing a key still happen where they did: those are the commit points, and the assistant
  panel re-reads its configuration the moment the dialog closes, so a write still in flight would
  have it report the wrong thing. Neither needs a keychain read any more — the label now follows
  from what was just written
- **Current fragment answered "no fragments" for every app that had them.** It read
  `dumpsys activity top`, which on Android 13 and later reports no fragment state at all — the
  activity is there, its FragmentManager is not. It now dumps the selected app by name, which
  still carries the fragments, and which fixes a second thing the old command got wrong: `top`
  is whatever is in the foreground, so with another app in front it reported that app's
  fragments, or nothing, without ever saying it had looked somewhere else
- **The MCP tab hid its details, and put them back every time you opened them.** The panel
  chooses between a splitter and a stacked layout from its own height, against a threshold
  chosen when it was a tool window tab with the whole window to itself. Under the shared header
  and tab row, with the status line below, it has some ninety pixels less — so an ordinary tool
  window fell under the threshold, the details collapsed to a title bar, and expanding them
  re-ran the same check and collapsed them again. The threshold is now stated as what a split
  actually needs, a list worth scrolling plus a pane worth reading, so it does not have to be
  re-tuned the next time something is added above the panel. Stacked, the details pane also
  took its preferred height — for a pretty-printed response, most of the panel — and squeezed
  out the list it was explaining; it is capped now
- **Half of an app's runtime permissions were invisible, and Grant all granted half of them.**
  The permission reader filtered what the device reported against a list of names written into
  the plugin — the dangerous permissions as they stood in Android 6 — so every runtime
  permission added since was silently dropped: `POST_NOTIFICATIONS`, the whole `READ_MEDIA_*`
  family, the Android 12 Bluetooth permissions, `ACCESS_BACKGROUND_LOCATION`,
  `ACTIVITY_RECOGNITION`. On an API 34 emulator that is fifteen of the thirty-two permissions
  Chrome holds. The match was a substring one as well, so a custom `com.example.permission.CAMERA`
  counted as the Android permission of that name. The filtering is gone: `dumpsys package` has a
  `runtime permissions:` section, which is the device itself saying which of the app's
  permissions are runtime ones, current for whatever Android it is running. **Manage
  permissions** now lists all of them and **Grant all** grants all of them
- **Every failed permission change was announced as a success.** `pm grant` and `pm revoke`
  print nothing when they work and an exception when they do not, and set no exit status either
  way — and the plugin discarded their output, so a permission the app had never requested, or
  one granted at install and unchangeable, was reported as granted. They are read now. **Grant
  all** no longer stops at the first refusal either: it names how many changed and what the
  device refused, so one permission the platform will not touch does not silently cost you the
  other thirty-one
- **The package name reached `pidof` unquoted.** `dumpsys` was given a quoted argument and
  `pidof` was not, and the validator deliberately allows `$` because component names contain
  it — so a package with a `$` in its name was expanded by the shell first, and the card
  reported whatever process that expansion happened to name
- **The Wi-Fi and mobile data buttons could be dead without looking it.** The row took the
  button's enabled state from the read that fills its label in, so every path where that read
  did not land — the row attached after the device list had already been published, a read
  retired by a newer one that then returned early, a device that never answered — left a button
  that looked ordinary and did nothing when pressed. Nothing was logged, because nothing ran.
  Whether a device is selected is now the only thing that decides the button; the read fills in
  the label and no longer touches it. The row also reads the device as soon as it is attached
  rather than waiting to be asked, and a press with no device selected says so instead of
  returning in silence
- **Wi-Fi and mobile data announced changes the device had refused.** `svc wifi disable` exits
  0 whether or not it did anything, and from Android 10 a good many builds do not let the adb
  shell switch Wi-Fi at all — so the plugin ran the command, the device ignored it, and the
  tool window reported "Disabled Wifi network" over a connection that was still up. The state
  is now read back after every toggle and a device that did not move says so, quoting whatever
  the shell said and, for Wi-Fi, why a modern device refuses. Wi-Fi also goes through
  `cmd -w wifi set-wifi-enabled`, the route that still works, on Android 11 and later
- **The animation scales showed the wrong device, and sometimes the wrong value.** Developer
  options were read only when the tool window was shown, so selecting a second device left the
  first one's switches and scales on screen — ready to be changed on a device they were never
  read from. They are now re-read whenever the selected device changes. The value was matched
  against the dropdown as text, so a device answering `1` where the list holds `1.0` selected
  nothing, and anything unreadable fell back to `0.0`, which the dropdown showed as **Off**: a
  device with animations running, displayed as a device with them switched off. The answer is
  now matched as a number, and one that names no scale selects nothing rather than guessing
- **Panels that would not fit a docked tool window.** The Commands tab put three buttons in a
  fixed row beside the command field, so at 300px the field was squeezed to nothing; they wrap
  onto their own line now. The MCP activity table gave its columns fixed widths, which left the
  tool name — the column the table exists for — nothing at all in a narrow window; every column
  now gives a little, down to a floor that keeps it readable. Both are covered by a test that
  lays them out at the width a tool window is routinely docked at
- **An action switched off left a hole where it had been, and a label with nothing under it.**
  The action grids were laid out from source order and merely hid what was switched off, so a
  two-column section with one action off showed a gap rather than closing up — and switching
  off **Send text** or **Deep link** hid the field and its button but left the label beside
  them. The grids are now filled from what is actually shown, and the whole row goes with it
- **A write refused because the file had changed on the device threw away your edits.** The
  editor re-reads the file after every write, including a refused one — so a write that was
  correctly refused, because the app or an agent had replaced the file since it was read,
  replaced the rows on screen with the device's, and the unapplied edits behind the refusal
  were gone. Nothing was written in that case, so there is nothing to show: the rows stay,
  Apply is disabled, and the editor says `File changed on device — reload before applying`
  until the file is read again
- **An action added in a new release never reached anyone who already had settings.** The
  visible-actions list is built from `SpockAction` once, on first run, and loading stored
  settings then replaced it wholesale — so an action introduced later was absent from every
  existing user's list. Because the settings dialog is built from that same list, there was
  no entry to switch the new action on or off with; it was not merely off, it was
  unreachable. Actions missing from stored settings are now merged in on load, switched on,
  as a fresh install would have had them. Choices already made are untouched, and entries
  for actions that no longer exist are still left alone
- **The All activities popup showed the parser's own bookkeeping instead of the stack.** Rows
  read `0-com.example.myapplication`, `0-com.example.myapplication.MainActivity` and — where
  `dumpsys` had given up a line the plugin could not read — the bare string `0-`, with package
  and activity at the same weight and indentation done in tab characters. The popup is now an
  **Activity Stack**: a heading per task naming the app — its display name over its package
  where the device can prove one, `My Application` rather than `com.example.myapplication` —
  its activities beneath it shortened to the part that is not the package, the task holding the
  resumed activity badged `CURRENT`, and the full package or class name in a tooltip for a
  narrow tool window that truncates a row. There is no shell command that simply prints an app's
  name: it is read from the app's `ApplicationInfo`, which either holds the name outright or
  points at a string resource, and in the second case the resource is resolved against the app's
  own resources and accepted only when it turns out to be the one the manifest named — so a task
  is never labelled with the wrong app's name, and an app whose name cannot be proven is shown
  as its package, as before. Both reads happen in one batched shell call each, and neither can
  fail the popup. An
  activity `dumpsys` does not name is dropped in the parser rather than rendered, and the task
  it belonged to says `No resumed activity` instead of showing an empty row. Two older faults
  went with it: an activity appearing in two tasks always opened the first task's class,
  because the class was looked up by the row's position in a list of strings; and package and
  class names were cut short at their first digit or underscore — `com.android.launcher3` was
  read as `com.android.launcher` — so those activities could never be opened at all

[Unreleased]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.3...HEAD
## [4.0.3] - 2026-09-12

### Fixed

- **Restart with Debugger crashed instead of falling back on newer Android Studio.**
  `AndroidJavaDebugger.attachToClient` has gained and lost a trailing parameter across releases,
  and the plugin was written to try the new shape and fall back to the old one — but the fallback
  was unreachable. jOOR reports a missing method as a `ReflectException` *caused by*
  `NoSuchMethodException`, and the check that decided "this IDE has a different API" looked only
  at the throwable it was handed, never at what that wrapped. Every miss was therefore treated as
  a real error, so the compatibility path never ran and the developer got
  `RuntimeException: ReflectException: NoSuchMethodException: No similar method attachToClient`.
  The check now walks the cause chain, cycle-guarded because it runs on the EDT. If neither known
  shape fits, the attach is driven from the signature the class actually declares, and if that
  fails too the message names the real signature instead of a reflection library — so the next
  report of this carries what is needed to fix it. `BackwardCompatibleGetter` moved to its own
  file, free of IntelliJ types, so the rule is covered by tests rather than only by inspection
- **`android_take_screenshot` never worked inside Android Studio.** It called
  `IDevice.getScreenshot()`, which the IDE ships as a stub that fails with "This method is not
  used in Android Studio", so every call returned that message instead of an image. Capture now
  goes through `screencap -p` on the shell, like every other tool. The bytes come back base64
  encoded because ddmlib's shell channel decodes its output as text and would otherwise corrupt
  the PNG, and the result is checked for a PNG signature so a `FLAG_SECURE` screen is reported
  as such rather than returned as a broken image
- **Every project-dependent MCP tool failed whenever two projects were open.** The tool
  context resolved the project with `openProjects.singleOrNull { !it.isDisposed }`, so a
  second open project turned `android_get_current_activity`, `android_get_activity_stack`,
  `android_get_current_fragments` and the default logcat package filter into "No project is
  open" — a message that was both wrong and unactionable. Resolution now follows the same
  rule as device resolution: use the selected project, or the only one open, and otherwise
  **refuse to guess** and name the candidates. Picking the focused window instead would be
  wrong exactly when it matters most, with an agent working while the developer looks
  elsewhere
- **Starting and stopping the MCP server ran on the EDT.** Starting binds two sockets and
  writes the stdio endpoint descriptor; stopping waits for live stdio sessions to end before
  releasing their threads. Stopping the server from the MCP panel with a client attached
  therefore froze the tool window until that wait expired. Both transitions now run on a
  pooled thread, the controls show the transition and are disabled while it runs, and
  Restart chains stop → start rather than issuing them together
- `McpServerService.start()` is idempotent: starting an already-running server returns the
  bound port instead of replacing the HTTP server and stranding the previous stdio bridge's
  threads

### Build

- **Gradle and Kotlin daemon memory are now configured.** Gradle's defaults — 512 MiB heap,
  384 MiB metaspace — are no longer enough for this project: CI failed the Plugin Verifier job
  with "Gradle build daemon has been stopped: since the JVM garbage collector is thrashing"
  during `compileJava`, before verification began, and the Kotlin compile daemon failed locally
  with "Not enough memory to run compilation". The Kotlin daemon is a separate process and does
  not inherit `org.gradle.jvmargs`, so it is sized on its own line

### Compatibility

- **`sinceBuild` raised from `231` to `232`**, dropping Android Studio Hedgehog (2023.1) and
  IntelliJ IDEA 2023.1. The Marketplace verifier reported IDEA 2023.1.7 as **Critical**: the
  Android plugin bundled there has no `com.android.tools.idea.execution`, which
  `Restart App With Debugger` links against. `verifier-ignored-problems.txt` suppressed that
  finding for the local verifier, so CI passed while the Marketplace did not — the two
  disagreed because one of them was told to look away. 2023.1 is the only build missing the
  package, so raising the floor removes the problem rather than hiding it
- The verification matrix moves with it: Android Studio `2023.2.1.25` and IntelliJ IDEA
  Community `2023.2.8` are the new floors
- **`verifier-ignored-problems.txt` is deleted.** It held exactly one entry, for that 2023.1
  finding, and it is the mechanism by which the local verifier and the Marketplace verifier
  came to disagree. `./gradlew verifyPlugin` now reports Compatible on all five targets with
  nothing suppressed at all

### Added

- **`android_get_debug_context`** — the whole triage bundle in one call: current activity, the
  UI semantics tree with its framework identified, recent logcat, and optionally a screenshot.
  Assembling those separately cost three or four round trips, and by the time the last landed
  the screen could have moved on, so the bundle described no single moment. A failing section
  reports its failure in place and the rest still come back — a screenshot blocked by
  `FLAG_SECURE` must not cost you the crash sitting beside it in logcat
- **`android_push_file` and `android_pull_file`.** Device paths are restricted to `/sdcard`,
  `/storage` and `/data/local/tmp`, which is deliberately stricter than `adb`: it will hand over
  anything the shell user can read, and an agent that can be talked into pulling another app's
  database is an exfiltration path wearing a debugging tool's clothes. The local destination of
  a pull is **not** a parameter — a tool that writes where its caller asks lets anything holding
  the MCP token drop a file anywhere on the filesystem — so pulls land in one known directory
  and the tool reports where. The source of a push is restricted to the open project or
  that same directory, so the pair cannot be composed into a read of any file on the
  machine. Transfers are capped at 50 MB in both directions
- **`android_start_screen_recording` and `android_stop_screen_recording`**, one session per
  device, capped at three minutes. Recording stops with `SIGINT` rather than `SIGKILL` so
  `screenrecord` writes the MP4 index on the way out — a killed recording leaves a file no
  player will open — and the remote file is deleted only once the pull has succeeded
- `android_select_project` — says which open project later calls are about, so the ambiguity
  above names a fix the agent can actually perform. Unnecessary with a single project open

### Internal

- `StubbedIDeviceApiTest` fails the build when anything calls an `IDevice` method Android
  Studio leaves unimplemented. Each throws "This method is not used in Android
  Studio" at runtime while compiling and unit-testing cleanly, because a test that builds its
  own `AndroidDebugBridge` gets stock ddmlib where they all work. It scans compiled bytecode
  rather than source, since Kotlin's property syntax hides the call — `device.screenshot` is a
  call to `getScreenshot()` that no text search would find. This is the bug that shipped in
  `android_take_screenshot`
- `McpSmokeTest` calls every read-only tool against a real device through a running server.
  The live checks are opt-in via `SPOCK_MCP_URL` and `SPOCK_MCP_TOKEN`, so an ordinary
  `./gradlew test` skips them, but its coverage assertion always runs — a read-only tool cannot
  be added without deciding how it is smoke-tested

## [4.0.2] - 2026-09-04

### Added

- **stdio transport for the MCP server**, served by the same `McpProtocol`, `ToolRegistry`,
  safety model, device services and audit trail as the HTTP transport — a tool call arriving
  over stdio is confirmed, recorded and logged exactly as the same call over HTTP
- `Tools → SpockAdb → Copy MCP Client Configuration (stdio)`. **It contains no credential**:
  the client is pointed at an endpoint descriptor, and the token stays in that `600` file
- `McpStdioServer` — newline-delimited JSON-RPC framing, request cancellation via
  `notifications/cancelled` (the call is interrupted and its response suppressed), and a
  worker pool so a cancellation arriving behind a slow tool call is still read
- `McpBridgeServer` — a Unix domain socket in a `700` directory, falling back to loopback TCP
  where `AF_UNIX` is unavailable or the path is too long for `sun_path`. Every connection
  presents the token on both transports; one that never does is closed after ten seconds, and
  the session pool is bounded, so nothing that reaches the endpoint can hold threads open
- `SpockAdbStdioLauncher` — the process an MCP client spawns. A dependency-free Java byte
  relay with no knowledge of MCP, so nothing about the protocol is implemented twice. It
  claims the real stdout and redirects `System.out` to stderr, so no log line can corrupt the
  protocol stream

## [4.0.1] - 2026-09-04

### Added

- `scripts/verify-marketplace-descriptor.sh` — gates the built descriptor before it reaches JetBrains Marketplace: the plugin id must be `com.wahdan.com.wahdan.spockAdb`, there must be no `until-build` cap, `since-build` must match `pluginSinceBuild`, and the version must match the release tag. Runs in `build.yml` on every pull request and in `release.yml` immediately before `publishPlugin`

### Documentation

- `docs/COMPATIBILITY.md` records why the Marketplace served 1.0.2 to modern IDEs for four years: 2.0.x shipped an `until-build` cap that silently expired the release, and 3.0.x renamed the plugin id so it could never reach listing 11591
- `CONTRIBUTING.md` no longer claims a release is live "within a few minutes" — a green `release.yml` means uploaded, not approved and served — and says how to confirm which version an IDE is actually offered

## [4.0.0] - 2026-09-04

### Fixed

- **Threading**: `currentBackStack` and `currentApplicationBackStack` now run ADB on a background thread and show popups on EDT; PSI lookups wrapped in `ReadAction.compute`
- **Crash**: `GetFragmentsCommand` — safe split access with `getOrNull` instead of hardcoded index, avoids `ArrayIndexOutOfBoundsException`
- **Crash**: `GetApplicationBackStackCommand` — safe array bounds (`getOrNull`) and removed `!!` force-unwrap on `find {}` result
- **Crash**: `Debugger` — replaced `client!!` with null-safe early return to avoid `NullPointerException` when the debug client is unavailable
- **Resource leak**: `AdbControllerImp` now implements `Disposable` and removes the `AndroidDebugBridge` device-change listener in `dispose()`
- **Resource leak**: `BaseAction` — `AdbControllerImp` is disposed immediately after the synchronous device-list read, instead of being incorrectly registered against `Project` as a long-lived disposable parent
- **Resource leak**: `AdbDrawerViewer` — `AdbControllerImp` is now registered against `toolWindow.disposable` instead of `Project`; the controller's lifetime correctly matches the tool window, not the entire project

### Changed

- `BaseAction` no longer uses `Disposer.register(project, controller)` — the controller is disposed explicitly after `connectedDevices()` returns, which is safe because the call is synchronous

### Added

- `BaseAction` now declares `ActionUpdateThread.BGT` and disables its actions when no project is open
- Tests for `ShellOutputReceiver` chunking and trailing-newline handling, and for the device state enums

### Compatibility

- Lowered `sinceBuild` from `253` (Panda canary only) to `231` (Hedgehog 2023.1.1), adding support for all stable Android Studio releases from 2023 onward
- Compile target updated to Android Studio Meerkat (2025.1.1) — latest stable build
- `untilBuild` remains open-ended so new releases are accepted without a plugin update

### Device management

- The device dropdown now shows model, Android version, API level, architecture, and whether the device is an emulator or a handset, instead of just the raw ddmlib name. Offline, unauthorized and bootloader devices are labelled as such
- **The selected device is persisted between sessions.** `AppSetting.selectedDevice` has existed since settings were introduced but was never read or written
- Menu actions now ignore devices that cannot accept commands, and say why when none are usable (for example "Pixel 7 is unauthorized"), rather than failing part-way through a command
- When no device was previously selected the plugin now prefers an online device rather than whichever happened to be first
- Device metadata is read on a background thread; `IDevice.getProperty` blocks, so this must never happen while the dropdown is being rendered
- Confirmation prompts name the target device, so it is unambiguous which of several attached devices an action will affect

### Security

- **Shell injection via the device.** ADB commands are built by string interpolation and run through the device shell. The two fields the user types into were interpolated inside hand-written quotes — `input text '$p'` and `am start ... -d "$p"` — so a value containing the matching quote character closed it early and everything after ran as shell on the connected device. Pasting a crafted deep link was enough. All interpolated values now go through `ShellQuote.quote`, which single-quotes and escapes embedded quotes
- Every other interpolation site was hardened the same way: package names, activity components, permission names and animation scales
- **Confirmation for destructive operations.** Uninstall, Clear App Data, Clear App Data & Restart and Revoke All Permissions were single clicks with no prompt, sitting beside read-only actions. Each now asks first, and names the target device so it is unambiguous which of several attached devices will be affected

### Removed

- Debug `println` statements from `SpockAdbViewer` and `GetApplicationBackStackCommand`
- Large commented-out dead code blocks in `SpockAdbViewer`, `ConnectDeviceOverIPCommand`, and `CheckBoxDialog`
- Duplicate empty `setting.addActionListener {}` in `SpockAdbViewer`

### Internal

- Fixed exception message `"Bazinga!!"` in `GetApplicationPermission` → professional message
- Renamed `kippAppProcess` → `killAppProcess` (typo fix) in `ProcessDeathCommand`

## [3.0.1]

### Compatibility

- Lowered `sinceBuild` from `253` to `231`, intended to support Android Studio Hedgehog (2023.1.1) and later

## [3.0.0]

### Added

- Open Developer Options button in the developer panel
- Open Deep Link button — fire any URI intent directly from the IDE

### Fixed

- Activity detection on Android 13+: fallback from `mResumedActivity` to `topResumedActivity`
- Fragment detection: switch to `dumpsys activity top` and filter by visibility and parent to show only active fragments
- Threading violations: ADB shell commands now run on a background thread; UI updates posted back to EDT
- Stale listener bug: developer options listeners are removed before updating combo boxes to prevent duplicate ADB calls
- Replaced deprecated `createListPopupBuilder` API with `createPopupChooserBuilder`

### Changed

- Back stack activity detection on Android 11+: use `grep Hist` instead of legacy `sed` approach

## [2.0.3]

### Fixed

- Android Studio latest version compatibility

## [2.0.2]

### Fixed

- Android Studio latest version compatibility

## [2.0.1]

### Added

- Added button to open developer options
- Added button to open deep links

### Changed

- Don't Keep Activities only shows if setting is enabled or not (although setting seemed to change, the behaviour was maintained)

### Fixed

- Adds support for getting the backstack activities in Android 11

## [2.0.0]

### Added

- Get Current App BackStack (Activities and nested fragments)
- Add plugin actions e.g. GetCurrentFragment, RestartApp, etc.
- Allow choosing which buttons to show

### Fixed

- Support latest version of Android Studio
- Fix get current fragment
- Fix: if two instances of Android Studio are open, the plugin does not work properly

## [1.0.9]

### Changed

- The activity stack now shows activities by app package so the user can clearly see which package an activity belongs to
- The fragment stack can now show nested fragments and follows the same display rules as the activity stack command

## [1.0.8]

### Added

- Toggle on/off Wi-Fi or mobile data
- Add text to be input on the device

## [1.0.7]

### Added

- Restart app with debugger
- Uninstall and Clear App Data and Restart
- Toggle "Show Taps" setting
- Toggle "Show Layout Bounds" setting
- Toggle "Don't Keep Activities" setting
- Grant or Revoke all app permissions at once
- Change scale of Window Animation, Transition Animation, and Animator Duration

## [1.0.0]

### Added

- Navigate to current active Activity in your IDE
- Current BackStack Activities
- Navigate to current active Fragments
- Clear application data
- Enable and Disable Permissions of your application
- Kill or Restart Application

[Unreleased]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.4...HEAD
[4.0.4]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.3...v4.0.4
[4.0.3]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.2...v4.0.3
[4.0.2]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.1...v4.0.2
[4.0.1]: https://github.com/WahdanZ/SpockAdb/compare/v4.0.0...v4.0.1
[4.0.0]: https://github.com/WahdanZ/SpockAdb/compare/v3.0.1...v4.0.0
[3.0.1]: https://github.com/WahdanZ/SpockAdb/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.3...v3.0.0
[2.0.3]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.2...v2.0.3
[2.0.2]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.1...v2.0.2
[2.0.1]: https://github.com/WahdanZ/SpockAdb/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.9...v2.0.0
[1.0.9]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/WahdanZ/SpockAdb/compare/v1.0.0...v1.0.7
[1.0.0]: https://github.com/WahdanZ/SpockAdb/commits/v1.0.0
