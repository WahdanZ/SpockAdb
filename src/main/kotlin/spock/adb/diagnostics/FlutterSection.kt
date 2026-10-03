package spock.adb.diagnostics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import spock.adb.flutter.AppIdentity
import spock.adb.flutter.DeviceTime
import spock.adb.flutter.FlutterAttachOutcome
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterEventLog
import spock.adb.flutter.FlutterSessionSnapshot
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.analysis.FlutterErrorReader
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.FlutterLogcatErrors
import spock.adb.flutter.analysis.FlutterProblemTypes
import spock.adb.flutter.analysis.FlutterTimelineMapper
import spock.adb.flutter.analysis.FrameStats
import spock.adb.flutter.analysis.HttpProfileReader
import spock.adb.flutter.analysis.logcatTime
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceException
import java.time.ZoneOffset
import java.util.IdentityHashMap
import java.util.Locale

/**
 * What Diagnose knows of a Flutter app, gathered by whoever builds the probe (the Diagnose tab,
 * `android_get_debug_context`) before the report runs: how the attach went, and — when it gave
 * a session — what that session holds. [FlutterSection] only reads it, so it is tested with
 * plain values.
 *
 * @param outcome null when nobody could ask: no project to look in; [note] says why.
 */
class FlutterDiagnosticSource(
    val applicationId: String,
    /** From the APK: [FlutterBuild.RELEASE] has no session to look for. */
    val build: FlutterBuild,
    val outcome: FlutterAttachOutcome?,
    val note: String? = null,
    val live: Live? = null,
    /** The host's now, to place logcat's yearless stamps. */
    val hostNowMs: Long = System.currentTimeMillis(),
) {
    /** A connected session's state. */
    class Live(
        val identity: AppIdentity,
        val snapshot: FlutterSessionSnapshot,
        /** The session's guess from the UI isolate's extensions; null when it has none yet. */
        val buildMode: FlutterBuild?,
        /** Null when the event log does not hold this session (it connected before the log started). */
        val events: FlutterEventLog.Contents?,
        /** Null while not measured, or when the device would not say. */
        val deviceTime: DeviceTime?,
        val reads: Reads,
    )

    /** What the section asks the app: blocking calls, each bounded by the builder. */
    interface Reads {
        /** `_flutter.getDisplayRefreshRate`; null when the app will not say. */
        fun refreshRate(): Double?

        /** `ext.dart.io.getHttpProfile`, scrubbed. May throw [VmServiceException]. */
        fun httpProfile(): JsonObject?

        /**
         * The last [FlutterSection.LOGCAT_WINDOW_LINES] lines of logcat, `threadtime`, filtered to
         * [FlutterLogcatErrors.FILTER]: where Flutter prints framework errors while structured
         * errors are off. Null when adb would not say.
         */
        fun flutterLog(): String? = null
    }
}

/**
 * `flutter`: the app's Flutter session, for a Flutter app — none at all for any other app.
 *
 * Never says "no errors" from silence. Every attach outcome is reported in words; a live
 * session's errors are counted since Spock connected, the ones DDS replayed apart ("before Spock
 * connected"); with structured errors off, or in a profile build, they are read from what Flutter
 * printed to logcat instead, and the section says which; and where errors are invisible — an app
 * that replaced `FlutterError.onError` — the section says so.
 *
 * Each error group carries `nearbyLogs`: the ids, in `likelyProblems`, of the log problems any of
 * whose lines fell within [NEARBY_WINDOW_MS] of the group — listed right after the error, however
 * low they would rank alone — widened by the clock's
 * uncertainty, compared on the device's own epoch (design §3, §4a).
 *
 * Reads only; never starts rebuild recording, which writes to the app.
 */
object FlutterSection : DiagnosticSection<AndroidProbe> {
    override val id = "flutter"

    /** No tool returns more yet; the section is the whole answer. */
    override val detail: DetailRef? = null

    override fun appliesTo(probe: AndroidProbe): Boolean = probe.flutter != null

    override fun collect(probe: AndroidProbe): SectionReport {
        val source = probe.flutter ?: error("Not a Flutter app.")
        return FlutterSectionReport(source).build()
    }

    /** Log lines this close to a Flutter error, before the clock's uncertainty, are its context. */
    const val NEARBY_WINDOW_MS = 2_000L

    /** Bounds, like every section's. */
    const val MAX_ERROR_GROUPS = 5
    const val MAX_HTTP_FAILURES = 5
    const val MAX_ROUTES = 5

    /** Log problems paired with one Flutter error, the closest first. */
    const val MAX_NEARBY_LOGS = 5

    /** How much of logcat is read for framework errors: the window the `logs` section reads. */
    const val LOGCAT_WINDOW_LINES = AndroidProbe.DEFAULT_LOG_WINDOW_LINES
}

/** One report of [FlutterSection]: what it read, and its correlation step. */
internal class FlutterSectionReport(private val source: FlutterDiagnosticSource) {

    private val data = JsonObject()
    private val notes = JsonArray()
    private val problems = mutableListOf<LikelyProblem>()
    private val live = source.live
    private val zone = live?.deviceTime?.zone ?: ZoneOffset.UTC

    /** Each listed problem of an error group → the log problems around it, closest first; from [pair]. */
    private val paired = IdentityHashMap<LikelyProblem, List<LikelyProblem>>()

    /** An error group's own problem → the one naming it and a native failure beside it; from [merge]. */
    private val crossLayer = IdentityHashMap<LikelyProblem, LikelyProblem>()

    /** Error groups as listed, with the problem each became and its JSON, for [correlate]. */
    private val groups = mutableListOf<Triple<FlutterErrorReader.Group, LikelyProblem, JsonObject>>()

    fun build(): SectionReport {
        data.addProperty("attach", Redaction.scrub(FlutterWords.attach(source)))
        data.addProperty("connected", live != null)
        data.addProperty("build", (live?.buildMode ?: source.build).label)
        if (live != null) readLive(live)
        source.note?.let { notes.add(Redaction.scrub(it)) }
        if (notes.size() > 0) data.add("notes", notes)
        return SectionReport(data, problems, afterRanking = ::correlate, companions = ::pair, merges = ::merge)
    }

    private fun readLive(live: FlutterDiagnosticSource.Live) {
        data.add("identity", identity(live))
        data.add("clock", clock(live.deviceTime))
        val events = live.events
        if (events == null) notes.add(NOT_KEPT)
        data.add("errors", errors(live, events?.errors.orEmpty()))
        data.add("frames", frames(live, events?.frames.orEmpty()))
        data.add("http", http(live))
        data.add("navigation", navigation(events?.navigation.orEmpty()))
    }

    private fun identity(live: FlutterDiagnosticSource.Live) = JsonObject().apply {
        val snapshot = live.snapshot
        addProperty("serial", live.identity.serial)
        addProperty("applicationId", live.identity.applicationId)
        addProperty("pid", live.identity.pid ?: snapshot.vmPid?.toLong())
        addProperty("verifiedBy", live.identity.verifiedBy.label)
        addProperty("connectionKind", FlutterWords.connection(snapshot.connectionKind))
        addProperty("structuredErrors", FlutterWords.onOff(snapshot.structuredErrorsEnabled))
        addProperty("httpRecording", FlutterWords.recording(snapshot.httpRecording))
    }

    private fun clock(time: DeviceTime?) = JsonObject().apply {
        if (time == null) {
            addProperty("note", CLOCK_UNKNOWN)
            return@apply
        }
        addProperty("zone", time.zone.id)
        addProperty("uncertaintyMs", time.uncertaintyMs)
        time.note?.let { addProperty("note", it) }
    }

    /**
     * Framework errors from where the app sends them (design §3, plan P5b): `Flutter.Error` events
     * while structured errors are on; logcat while they are off or the build has no inspector. With
     * them on and no event, logcat is read too, so silence is silence in both places.
     */
    private fun errors(live: FlutterDiagnosticSource.Live, events: List<FlutterExtensionEvent>): JsonObject {
        val fromEvents = FlutterErrorReader.read(events)
        val from = ErrorSource.of(live)
        val silent = live.snapshot.structuredErrorsEnabled == true && fromEvents.isEmpty()
        val fromLogcat = if (from != ErrorSource.EVENTS || silent) readLogcat(live) else null
        val result = FlutterErrorReader.summariseErrors(
            (fromEvents + fromLogcat.orEmpty()).sortedBy { it.timestampMs },
            zone,
        )
        val listed = JsonArray()
        result.groups.zip(result.problems).take(FlutterSection.MAX_ERROR_GROUPS).forEach { (group, problem) ->
            val json = groupJson(group, problem)
            listed.add(json)
            groups += Triple(group, problem, json)
        }
        problems += result.problems
        errorNotes(live.snapshot, from, fromEvents.size, fromLogcat)
        return JsonObject().apply {
            addProperty("source", from.words)
            addProperty("sinceConnected", fromEvents.count { !it.history })
            addProperty("beforeSpockConnected", fromEvents.count { it.history })
            fromLogcat?.let { addProperty("inLogcat", it.size) }
            fromEvents.lastOrNull()?.errorsSinceReload?.takeIf { it >= 0 }?.let { addProperty("sinceReload", it + 1) }
            add("groups", listed)
            if (result.groups.size > listed.size()) addProperty("moreGroups", result.groups.size - listed.size())
        }
    }

    /** What the app's own pid printed to logcat as framework errors; null when logcat or the pid cannot be had. */
    private fun readLogcat(live: FlutterDiagnosticSource.Live): List<FlutterErrorReader.FlutterError>? {
        val pid = live.identity.pid ?: live.snapshot.vmPid?.toLong() ?: return null
        val log = live.reads.flutterLog() ?: return null
        // Without a measured clock the stamps are read as UTC, the zone the section then prints in.
        val time = live.deviceTime ?: DeviceTime(epochOffsetMs = 0, uncertaintyMs = 0, zone = ZoneOffset.UTC)
        return FlutterLogcatErrors.read(log, setOf(pid.toString())) { time.logcatToEpoch(it, source.hostNowMs) }
    }

    private fun groupJson(group: FlutterErrorReader.Group, problem: LikelyProblem) = JsonObject().apply {
        addProperty("summary", problem.summary)
        addProperty("count", group.count)
        addProperty("source", group.first.source.id)
        if (group.historyCount > 0) addProperty("beforeSpockConnected", group.historyCount)
        addProperty("firstSeen", deviceStamp(group.firstSeenMs))
        addProperty("lastSeen", deviceStamp(group.lastSeenMs))
    }

    /**
     * Where errors are when they are not here. Never "no errors": silence in every place looked
     * names the handler that would cause it.
     */
    private fun errorNotes(
        snapshot: FlutterSessionSnapshot,
        from: ErrorSource,
        fromEvents: Int,
        fromLogcat: List<FlutterErrorReader.FlutterError>?,
    ) {
        val since = "since Spock connected" + snapshot.connectedAtHostMs?.let { " at ${hostStamp(it)}" }.orEmpty()
        val inLogcat = "the last ${FlutterSection.LOGCAT_WINDOW_LINES} lines of logcat"
        val note = when {
            from != ErrorSource.EVENTS -> when {
                fromLogcat == null -> "${from.words}; logcat could not be read, so none are listed."
                fromLogcat.isEmpty() -> "${from.words}, and $inLogcat hold none from the app. $NOT_PROOF"
                else -> "${from.words}: they are listed with source `logcat`."
            }
            fromEvents > 0 -> null
            snapshot.structuredErrorsEnabled != true -> "No Flutter.Error $since. $NOT_PROOF"
            fromLogcat == null -> "No Flutter.Error $since, and logcat could not be read. $NOT_PROOF"
            fromLogcat.isEmpty() -> "No Flutter.Error $since, and no framework error in $inLogcat either. $NOT_PROOF"
            else ->
                "No Flutter.Error $since, but logcat holds framework errors, listed with source `logcat`: " +
                    "printed while structured errors were off, or by the app's own error handler."
        }
        note?.let(notes::add)
    }

    /** Where this report reads framework errors from, by the structured-errors state the session tracks. */
    private enum class ErrorSource(val words: String) {
        EVENTS("Flutter.Error events from the VM Service: structured errors are on"),
        LOGCAT_OFF(
            "Structured errors are off: Flutter prints framework errors to logcat (the first in full, later " +
                "ones as \"Another exception was thrown: …\"), and this section reads them there",
        ),
        LOGCAT_PROFILE(
            "A profile build has no inspector: Flutter prints framework errors to logcat, and this section " +
                "reads them there",
        ),
        ;

        companion object {
            fun of(live: FlutterDiagnosticSource.Live): ErrorSource = when {
                live.snapshot.structuredErrorsEnabled == false -> LOGCAT_OFF
                live.snapshot.structuredErrorsEnabled == null && live.buildMode == FlutterBuild.PROFILE ->
                    LOGCAT_PROFILE
                else -> EVENTS
            }
        }
    }

    private fun frames(live: FlutterDiagnosticSource.Live, events: List<FlutterExtensionEvent>) = JsonObject().apply {
        if (events.none { !it.history }) {
            addProperty("note", "No frames since Spock connected: an idle app reports none.")
            return@apply
        }
        val fps = runCatching { live.reads.refreshRate() }.getOrNull()
        val report = FrameStats.analyse(events, fps, live.buildMode)
        addProperty("frames", report.frames.size)
        addProperty("budgetMs", round(report.budgetMs))
        if (!report.budgetMeasured) addProperty("budgetAssumed", "${FrameStats.DEFAULT_FPS.toInt()} Hz")
        addProperty("overBudget", report.overBudget.size)
        report.build?.let { add("buildMs", distribution(it)) }
        report.raster?.let { add("rasterMs", distribution(it)) }
        if (live.buildMode != FlutterBuild.PROFILE) addProperty("note", NOT_REPRESENTATIVE)
        problems += report.problems
    }

    private fun http(live: FlutterDiagnosticSource.Live) = JsonObject().apply {
        val recording = live.snapshot.httpRecording
        addProperty("recording", FlutterWords.recording(recording))
        if (recording !in RECORDING) return@apply
        val profile = try {
            HttpProfileReader.read(live.reads.httpProfile())
        } catch (e: VmServiceException) {
            addProperty("note", Redaction.scrub("Could not read the HTTP profile: ${e.message}"))
            return@apply
        }
        val requests = HttpProfileReader.merge(emptyList(), profile.requests)
        val failed = requests.filter { it.failed }
        addProperty("requests", requests.size)
        addProperty("failed", failed.size)
        add(
            "failures",
            JsonArray().apply {
                failed.takeLast(FlutterSection.MAX_HTTP_FAILURES).forEach { add(HttpProfileReader.summary(it)) }
            },
        )
        addProperty("note", HTTP_SCOPE)
        problems += HttpProfileReader.problems(requests)
    }

    private fun navigation(events: List<FlutterExtensionEvent>) = JsonArray().apply {
        val placement = FlutterTimelineMapper.Placement(zone)
        events.takeLast(FlutterSection.MAX_ROUTES)
            .mapNotNull { FlutterTimelineMapper.navigation(it, placement)?.title }
            .forEach(::add)
    }

    /**
     * Before ranking, against every log problem the report found — not only those that will make
     * the list: a warning the app logged a second before the error must not lose its place to the
     * platform's start-up noise. Every occurrence counts, not only the last: a warning that
     * repeated is near the error if any of its lines is. At most [FlutterSection.MAX_NEARBY_LOGS]
     * per error, the closest first; the collector lists them right after the error.
     */
    private fun pair(all: List<LikelyProblem>): Map<LikelyProblem, List<LikelyProblem>> {
        val logs = timedLogs(all) ?: return emptyMap()
        groups.forEach { (group, problem, _) ->
            val near = logs
                .mapNotNull { (log, epochs) -> distance(group, epochs)?.let { log to it } }
                .sortedBy { it.second }
                .take(FlutterSection.MAX_NEARBY_LOGS)
                .map { it.first }
            if (near.isNotEmpty()) paired[anchor(problem)] = near
        }
        return paired
    }

    /**
     * Before [pair]: a listed error group and a native failure — a crash, a missing plugin, a channel
     * handler that failed ([nativeLayer]) — within the pairing window are one
     * fault seen from both sides, so they become one problem naming both layers, with each layer's
     * own problem as its parts. The closest pairs first; each problem joins at most one.
     */
    private fun merge(all: List<LikelyProblem>): List<LikelyProblem> {
        val natives = timedLogs(all)?.filter { (log, _) -> nativeLayer(log) } ?: return emptyList()
        val candidates = groups.flatMap { (group, problem, _) ->
            natives.mapNotNull { (native, epochs) -> distance(group, epochs)?.let { Triple(problem, native, it) } }
        }.sortedBy { it.third }
        val taken = IdentityHashMap<LikelyProblem, Unit>()
        candidates.forEach { (dart, native, gapMs) ->
            if (dart in crossLayer || native in taken) return@forEach
            taken[native] = Unit
            crossLayer[dart] = crossLayerProblem(dart, native, gapMs)
        }
        return crossLayer.values.toList()
    }

    private fun crossLayerProblem(dart: LikelyProblem, native: LikelyProblem, gapMs: Long): LikelyProblem {
        val gap = String.format(Locale.ROOT, "%.1f s", gapMs / MILLIS_PER_SECOND)
        val half = (DiagnosticShell.MAX_VALUE_CHARS - CROSS_LAYER_WORDS) / 2
        return LikelyProblem(
            type = FlutterProblemTypes.CROSS_LAYER,
            severity = LikelyProblem.Severity.ERROR,
            summary = "In Dart and on Android, $gap apart — Dart: ${DiagnosticShell.clip(dart.summary, half)}; " +
                "Android: ${DiagnosticShell.clip(native.summary, half)}",
            count = dart.count,
            lastSeen = dart.lastSeen,
            section = FlutterSection.id,
            seenAt = dart.seenAt + native.seenAt,
            parts = listOf(dart, native),
        )
    }

    /** The log problems in [all] with the device epoch of each of their lines; null without a clock. */
    private fun timedLogs(all: List<LikelyProblem>): List<Pair<LikelyProblem, List<Long>>>? {
        val time = live?.deviceTime ?: return null
        return all.filter { it.section == LogsSection.id }.mapNotNull { problem ->
            val times = problem.seenAt.mapNotNull { time.logcatToEpoch(it, source.hostNowMs) }
            times.takeIf { it.isNotEmpty() }?.let { problem to it }
        }
    }

    /**
     * How far the closest of [epochs] is from [group]'s span, when within the pairing window
     * widened by the clock's uncertainty; null when farther, or without a clock.
     */
    private fun distance(group: FlutterErrorReader.Group, epochs: List<Long>): Long? {
        val time = live?.deviceTime ?: return null
        val closest = epochs.minOf { epochMs ->
            when {
                epochMs < group.firstSeenMs -> group.firstSeenMs - epochMs
                epochMs > group.lastSeenMs -> epochMs - group.lastSeenMs
                else -> 0L
            }
        }
        return closest.takeIf { it <= FlutterSection.NEARBY_WINDOW_MS + time.uncertaintyMs }
    }

    /** The problem listed for an error group: its own, or the cross-layer one it became part of. */
    private fun anchor(problem: LikelyProblem): LikelyProblem = crossLayer[problem] ?: problem

    /** Points each listed error group at its own entry and at the log problems paired with it. */
    private fun correlate(ranked: RankedProblems) {
        groups.forEach { (_, problem, json) ->
            ranked.idOf(anchor(problem))?.let { json.addProperty("problem", it) }
            crossLayer[problem]?.let { json.addProperty("crossLayer", it.parts.last().summary) }
        }
        silentHandlerNote(ranked)
        if (live?.deviceTime == null) return
        groups.forEach { (_, problem, json) ->
            val near = paired[anchor(problem)].orEmpty()
            val ids = near.mapNotNull(ranked::idOf)
            json.add("nearbyLogs", JsonArray().apply { ids.forEach(::add) })
            // Only when the error itself is below the cut: its companions follow it there.
            if (near.size > ids.size) json.addProperty("moreNearbyLogs", near.size - ids.size)
        }
    }

    /**
     * The screen shows an error, structured errors are on, and none arrived: an app that
     * replaced `FlutterError.onError` hides it from both channels. Read from the UI section.
     */
    private fun silentHandlerNote(ranked: RankedProblems) {
        if (live?.snapshot?.structuredErrorsEnabled != true || groups.isNotEmpty()) return
        val texts = ranked.sections[UiSection.id]?.getAsJsonArray("text") ?: return
        val shown = texts.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            .firstOrNull { ERROR_ON_SCREEN.containsMatchIn(it) } ?: return
        notes.add(
            DiagnosticShell.clip(
                "The screen shows \"$shown\", yet no Flutter.Error arrived: $CUSTOM_HANDLER",
                MAX_NOTE_CHARS,
            ),
        )
        if (!data.has("notes")) data.add("notes", notes)
    }

    /** As logcat prints the device's time, so a group reads like the log lines beside it. */
    private fun deviceStamp(epochMs: Long): String = logcatTime(epochMs, zone)

    private fun hostStamp(hostMs: Long): String {
        val deviceMs = live?.deviceTime?.let { hostMs + it.epochOffsetMs } ?: hostMs
        return deviceStamp(deviceMs)
    }

    private fun distribution(value: FrameStats.Distribution) = JsonObject().apply {
        addProperty("p50", round(value.p50Ms))
        addProperty("p90", round(value.p90Ms))
        addProperty("worst", round(value.worstMs))
    }

    private fun round(value: Double): Double = String.format(Locale.ROOT, "%.1f", value).toDouble()

    private companion object {
        const val MAX_NOTE_CHARS = 400
        const val MILLIS_PER_SECOND = 1_000.0

        /** The words of a cross-layer summary around its two halves, so the whole fits the summary's bound. */
        const val CROSS_LAYER_WORDS = 56

        /** dart:io is recording: there is a profile to read. */
        val RECORDING = setOf(HttpRecording.EnabledBySpock, HttpRecording.AdoptedBySpock, HttpRecording.AlreadyOn)
        const val NOT_KEPT =
            "Spock connected before it kept this session's events; what the app reports from now on is counted."
        const val CLOCK_UNKNOWN =
            "The device's clock is not measured yet: times below are UTC, and no log lines are paired."
        const val NOT_REPRESENTATIVE = "Not a profile build: frame times are not representative."
        const val CUSTOM_HANDLER = "an app that replaced FlutterError.onError (a crash reporter: Crashlytics, " +
            "Sentry) reports framework errors to neither the VM Service nor logcat."
        const val NOT_PROOF = "That is not proof of none: $CUSTOM_HANDLER"
        const val HTTP_SCOPE = "dart:io traffic only (package:http, dio); cupertino_http, cronet_http and " +
            "native SDKs are not visible."

        /** What an error on screen reads like: the overflow banner's text, the red screen's. */
        val ERROR_ON_SCREEN = Regex("""overflowed by|RenderFlex|Exception caught by|was thrown building""")
    }
}

/** A [FlutterAttachOutcome] and the session's facts, in words a developer and an agent can act on. */
internal object FlutterWords {

    fun attach(source: FlutterDiagnosticSource): String {
        val app = source.applicationId
        return when (val outcome = source.outcome) {
            null -> source.note ?: "Spock did not look for a Flutter session."
            is FlutterAttachOutcome.Connected ->
                "Connected to ${outcome.identity}" + if (outcome.reused) "." else ", just now."
            is FlutterAttachOutcome.NotRunning ->
                "$app is not running on ${outcome.serial}: there is no Flutter session."
            is FlutterAttachOutcome.ReleaseBuild ->
                "$app is a release build: it has no Dart VM Service, so there is nothing to connect to."
            is FlutterAttachOutcome.NotReady -> if (outcome.frozen) {
                outcome.reason
            } else {
                "The app is starting; Flutter session not ready yet. ${outcome.reason}"
            }
            is FlutterAttachOutcome.NoDdsSession -> outcome.message
            is FlutterAttachOutcome.Ambiguous ->
                "Several running Flutter apps pass as $app and Spock does not guess between them: " +
                    outcome.candidates.joinToString("; ") { it.identity.toString() } + ". ${outcome.reason}"
            is FlutterAttachOutcome.NotFound -> "No Flutter session found for $app: ${outcome.reason}"
            is FlutterAttachOutcome.Failed -> "Could not connect to $app's Flutter session: ${outcome.message}"
        }.trim()
    }

    fun connection(kind: ConnectionKind?): String = when (kind) {
        ConnectionKind.DDS -> "dds"
        ConnectionKind.DIRECT_NO_DDS -> "direct VM, no DDS (read-only)"
        null -> "unknown"
    }

    fun onOff(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }

    fun recording(recording: HttpRecording?): String = when (recording) {
        HttpRecording.EnabledBySpock -> "on: Spock turned it on, and turns it off again when it disconnects"
        HttpRecording.AdoptedBySpock ->
            "on: Spock turned it on in an earlier session that ended first, and turns it off when it disconnects"
        HttpRecording.AlreadyOn -> "on: it was on already (DevTools or the IDE), and Spock leaves it as it is"
        HttpRecording.Pending -> "pending: dart:io has not registered its HTTP extension in the app yet"
        is HttpRecording.Off -> when (recording.reason) {
            HttpRecording.Reason.SETTING_OFF ->
                "off: \"Record Flutter HTTP traffic automatically\" is off in Settings → Tools → Spock ADB"
            HttpRecording.Reason.NOT_DDS -> "off: not a DDS connection, so Spock changes nothing in the app"
            HttpRecording.Reason.RELEASE_OR_UNKNOWN_MODE -> "off: the build mode is not known yet"
            HttpRecording.Reason.FAILED -> "off: switching it on failed"
        }
        null -> "not decided yet"
    }
}
