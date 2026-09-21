package spock.adb.command

import spock.adb.ShellQuote

/**
 * The shell side of opening a deep link, kept free of the IDE and of `IDevice` so the command
 * and the verdict can be tested without a device.
 *
 * `am start` exits 0 whatever happens: a URI no activity handles, and one that resolves to an
 * activity the device refuses to start, are both reported only on stdout. The plugin used to
 * throw that output away and answer "Opened deep link …" either way, which is the one answer
 * that cannot be true — the developer is looking at a phone that did nothing.
 */
internal object AmStart {

    /**
     * ddmlib counts this as the longest gap it will accept *between* chunks of output, and
     * `-W` keeps `am` silent until the launch has finished. A cold start behind a slow
     * `Application.onCreate` therefore has to fit inside it, which the previous 15 seconds
     * did not reliably do.
     */
    const val TIMEOUT_SECONDS = 30L

    /**
     * `am start` for [uri], optionally scoped to [packageName].
     *
     * `-W` is deliberate: without it a success prints `Starting: Intent { … }` and nothing
     * else, so the component that claimed the link — the thing worth reporting — never
     * arrives. [TIMEOUT_SECONDS] caps the wait. `2>&1` removes any doubt about which stream
     * the diagnostics land on.
     *
     * Both values go through [ShellQuote.quote]. The URI was once interpolated inside double
     * quotes, which do not suppress command substitution: a URI containing `$(...)` or a
     * backtick ran shell on the device.
     */
    fun command(uri: String, packageName: String? = null): String = buildString {
        append("am start -W -a android.intent.action.VIEW -d ").append(ShellQuote.quote(uri))
        packageName?.let { append(" -p ").append(ShellQuote.quote(it)) }
        append(" 2>&1")
    }
}

/** What the device did with a deep link, as far as its own output says. */
data class AmStartResult(
    val uri: String,
    val outcome: Outcome,
    /** The activity that claimed the link, when `am -W` named one. */
    val component: String?,
    /** Everything the device printed, for the notification and the agent to read. */
    val raw: String,
    /** The package the intent was scoped to, which changes what "nothing handled it" means. */
    val packageName: String? = null,
) {

    enum class Outcome {
        STARTED,
        BROUGHT_TO_FRONT,
        WAIT_TIMED_OUT,
        NOT_RESOLVED,
        PERMISSION_DENIED,
        DEVICE_ERROR,
        UNRECOGNISED,
    }

    /**
     * Written as an exhaustive `when` rather than a set membership test so that adding an
     * outcome has to state which side of this it falls on.
     */
    val succeeded: Boolean
        get() = when (outcome) {
            // Not confirmed is not the same as failed: reporting a link that opened as a
            // failure is the mistake this class exists to avoid, in both directions.
            Outcome.STARTED, Outcome.BROUGHT_TO_FRONT, Outcome.WAIT_TIMED_OUT, Outcome.UNRECOGNISED -> true
            Outcome.NOT_RESOLVED, Outcome.PERMISSION_DENIED, Outcome.DEVICE_ERROR -> false
        }

    /** One sentence for a notification or a tool result. [raw] is attached separately. */
    val message: String
        get() = when (outcome) {
            Outcome.STARTED -> component?.let { "Opened $uri — $it." } ?: "Opened $uri."
            // `am` raises the existing task and does not deliver the intent, so there is no
            // onNewIntent and the app stays on whatever screen it was already showing.
            Outcome.BROUGHT_TO_FRONT ->
                "Brought $uri's app to the front; its existing task was resumed, so the link " +
                    "may not have been delivered to the activity."
            Outcome.WAIT_TIMED_OUT ->
                "Sent $uri; the device stopped waiting for the launch to finish, so whether it " +
                    "opened is unconfirmed."
            Outcome.NOT_RESOLVED ->
                packageName?.let { "No activity in $it handles $uri (is the app installed?)." }
                    ?: "No activity on this device handles $uri."
            Outcome.PERMISSION_DENIED ->
                "$uri resolved to an activity this device refused to start. It is probably not " +
                    "exported." + said(refusalLine())
            Outcome.DEVICE_ERROR -> "The device refused $uri: ${diagnosticLine() ?: "no reason given"}"
            Outcome.UNRECOGNISED ->
                "Sent $uri; the device did not say whether it started." + said(firstNonBlankLine())
        }

    private fun said(line: String?): String = line?.let { " The device said: $it" }.orEmpty()

    private fun refusalLine(): String? = deviceLines(raw).firstOrNull { it.isRefusal() }

    private fun diagnosticLine(): String? {
        val lines = deviceLines(raw)
        return lines.firstOrNull { it.startsWith(ERROR_PREFIX) || it.startsWith(STATUS_PREFIX) || it.isCrash() }
            ?: lines.firstOrNull { it.isNotEmpty() }
    }

    private fun firstNonBlankLine(): String? =
        raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    companion object {

        private const val ERROR_PREFIX = "Error:"
        private const val ERROR_TYPE_PREFIX = "Error type"
        private const val STATUS_PREFIX = "Status:"
        private const val ACTIVITY_PREFIX = "Activity:"
        private const val STARTING_PREFIX = "Starting:"

        /** What `am` prints when the app is already in front. It is not a failure. */
        private const val ALREADY_FOREGROUND =
            "Warning: Activity not started, its current task has been brought to the front"

        /**
         * Reads what `am start -W` printed for [uri], optionally scoped to [packageName].
         *
         * Only stable prefixes are matched, never whole sentences, and they are matched on
         * the device's *own* lines — the echoed `Starting: Intent { … }` dump is skipped,
         * because it contains the URI verbatim and a URI is attacker-supplied text. Without
         * that, opening `myapp://help/SecurityException` reported a refusal that never
         * happened.
         *
         * The trade-off runs one way on purpose. Wording the plugin does not know is
         * [Outcome.UNRECOGNISED], which counts as success: a device whose phrasing we cannot
         * read must not be reported as a failure nobody can substantiate, and a `Warning:`
         * line on its own is never one — the app already being in the foreground is the link
         * working. What that leniency must not swallow is the device saying outright that
         * something blew up, so an `Exception`/`java.lang.…`/`Error type` line is a
         * [Outcome.DEVICE_ERROR] even though `am` never prefixes it with `Error:`.
         */
        fun parse(uri: String, raw: String, packageName: String? = null): AmStartResult {
            val lines = deviceLines(raw)
            return AmStartResult(
                uri = uri,
                outcome = classify(lines),
                component = lines.firstOrNull { it.startsWith(ACTIVITY_PREFIX) }
                    ?.removePrefix(ACTIVITY_PREFIX)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                raw = raw,
                packageName = packageName,
            )
        }

        private fun classify(lines: List<String>): Outcome = when {
            // Neither of these contains the word "Error", which is why looking for it
            // reported a refused link as opened.
            lines.any { it.isRefusal() } -> Outcome.PERMISSION_DENIED
            lines.any { it.startsWith(ERROR_PREFIX) && it.contains("unable to resolve Intent") } ->
                Outcome.NOT_RESOLVED
            lines.any { it.startsWith(ERROR_PREFIX) || it.isCrash() } -> Outcome.DEVICE_ERROR
            else -> statusOutcome(lines)
        }

        private fun statusOutcome(lines: List<String>): Outcome {
            val status = lines.firstOrNull { it.startsWith(STATUS_PREFIX) }
                ?.removePrefix(STATUS_PREFIX)
                ?.trim()
                ?: return Outcome.UNRECOGNISED

            return when {
                status.equals("ok", ignoreCase = true) ->
                    if (lines.any { it.contains(ALREADY_FOREGROUND) }) {
                        Outcome.BROUGHT_TO_FRONT
                    } else {
                        Outcome.STARTED
                    }
                // `am` gave up waiting for the window to be drawn. The activity has almost
                // certainly started anyway, so this is unconfirmed rather than refused.
                status.equals("timeout", ignoreCase = true) -> Outcome.WAIT_TIMED_OUT
                else -> Outcome.DEVICE_ERROR
            }
        }

        /**
         * The device's own output lines, with the echoed intent dump removed.
         *
         * `am` opens the dump with `Starting: Intent {` and closes it with the matching brace,
         * and a URI containing a newline spreads its own text across the lines in between. An
         * unbalanced dump — a URI with a stray `{` — swallows the rest of the output, which
         * lands on [Outcome.UNRECOGNISED]: sent, unconfirmed, rather than a verdict read off
         * text the caller wrote.
         */
        private fun deviceLines(raw: String): List<String> {
            val lines = mutableListOf<String>()
            var depth = 0
            raw.lines().forEach { line ->
                val trimmed = line.trim()
                if (depth == 0 && !trimmed.startsWith(STARTING_PREFIX)) {
                    lines += trimmed
                    return@forEach
                }
                depth += trimmed.count { it == '{' } - trimmed.count { it == '}' }
                if (depth < 0) depth = 0
            }
            return lines
        }

        private fun String.isRefusal(): Boolean =
            contains("Permission Denial") || contains("SecurityException")

        /** The device reporting that something threw, which `am` does not prefix with `Error:`. */
        private fun String.isCrash(): Boolean =
            startsWith("Exception") ||
                startsWith("java.lang.") ||
                startsWith(ERROR_TYPE_PREFIX) ||
                contains("Exception occurred while executing")
    }
}
