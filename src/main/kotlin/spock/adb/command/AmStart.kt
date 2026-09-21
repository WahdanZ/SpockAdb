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
     * `am start` for [uri], optionally scoped to [packageName].
     *
     * `-W` is deliberate: without it a success prints `Starting: Intent { … }` and nothing
     * else, so the component that claimed the link — the thing worth reporting — never
     * arrives. The caller's existing timeout caps the wait. `2>&1` removes any doubt about
     * which stream the diagnostics land on.
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
) {

    enum class Outcome { STARTED, BROUGHT_TO_FRONT, NOT_RESOLVED, PERMISSION_DENIED, DEVICE_ERROR, UNRECOGNISED }

    val succeeded: Boolean
        get() = outcome == Outcome.STARTED ||
            outcome == Outcome.BROUGHT_TO_FRONT ||
            outcome == Outcome.UNRECOGNISED

    /** One sentence for a notification or a tool result. [raw] is attached separately. */
    val message: String
        get() = when (outcome) {
            Outcome.STARTED -> component?.let { "Opened $uri — $it." } ?: "Opened $uri."
            Outcome.BROUGHT_TO_FRONT -> "Opened $uri — its task was already in the foreground."
            Outcome.NOT_RESOLVED -> "No activity on this device handles $uri."
            Outcome.PERMISSION_DENIED ->
                "$uri resolved to an activity this device refused to start. It is probably not exported."
            Outcome.DEVICE_ERROR -> "The device refused $uri: ${diagnosticLine() ?: "no reason given"}"
            Outcome.UNRECOGNISED -> buildString {
                append("Sent $uri; the device did not say whether it started.")
                firstNonBlankLine()?.let { append(" Device output: $it") }
            }
        }

    private fun diagnosticLine(): String? = raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(ERROR_PREFIX) || it.startsWith(STATUS_PREFIX) }

    private fun firstNonBlankLine(): String? =
        raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    companion object {

        private const val ERROR_PREFIX = "Error:"
        private const val STATUS_PREFIX = "Status:"
        private const val ACTIVITY_PREFIX = "Activity:"

        /** What `am` prints when the app is already in front. It is not a failure. */
        private const val ALREADY_FOREGROUND =
            "Warning: Activity not started, its current task has been brought to the front"

        /**
         * Reads what `am start -W` printed for [uri].
         *
         * Only stable prefixes are matched, never whole sentences, and unknown wording is
         * [Outcome.UNRECOGNISED] — which counts as success. A device whose phrasing the plugin
         * does not know must not be reported as a failure nobody can substantiate, and a
         * `Warning:` line on its own is never one: the app being in the foreground already is
         * the link working.
         */
        fun parse(uri: String, raw: String): AmStartResult {
            val lines = raw.lineSequence().map { it.trim() }.toList()
            val outcome = when {
                // Neither of these contains the word "Error", which is why looking for it
                // reported a refused link as opened.
                raw.contains("Permission Denial") || raw.contains("SecurityException") ->
                    Outcome.PERMISSION_DENIED

                lines.any { it.startsWith(ERROR_PREFIX) && it.contains("unable to resolve Intent") } ->
                    Outcome.NOT_RESOLVED

                lines.any { it.startsWith(ERROR_PREFIX) } -> Outcome.DEVICE_ERROR

                else -> statusOutcome(lines, raw)
            }
            return AmStartResult(
                uri = uri,
                outcome = outcome,
                component = lines.firstOrNull { it.startsWith(ACTIVITY_PREFIX) }
                    ?.removePrefix(ACTIVITY_PREFIX)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                raw = raw,
            )
        }

        private fun statusOutcome(lines: List<String>, raw: String): Outcome {
            val status = lines.firstOrNull { it.startsWith(STATUS_PREFIX) }
                ?.removePrefix(STATUS_PREFIX)
                ?.trim()
                ?: return Outcome.UNRECOGNISED

            return when {
                !status.equals("ok", ignoreCase = true) -> Outcome.DEVICE_ERROR
                raw.contains(ALREADY_FOREGROUND) -> Outcome.BROUGHT_TO_FRONT
                else -> Outcome.STARTED
            }
        }
    }
}
