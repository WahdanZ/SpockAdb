package spock.adb.command

import spock.adb.ShellQuote

/**
 * Running a script as an app through `run-as`, and reading back what happened.
 *
 * Two things are hard here and were solved once for clearing the cache; everything that goes
 * through `run-as` shares them rather than repeating them:
 *
 *  - **Quoting.** The package and the script cross two shells — `adb shell`'s and the one
 *    `run-as` starts — so both are quoted with [ShellQuote.quote], never by hand.
 *  - **Refusal versus failure.** A refused `run-as` never starts the shell, so it prints its
 *    complaint and no status line. A script that ran ends with `rc=<status>`, printed by the
 *    script itself. The status line is therefore proof the script ran, and its absence is
 *    what a refusal looks like.
 */
internal object RunAs {

    /** Every script passed here must end by echoing `rc=<status>` — see [classify]. */
    fun command(packageName: String, script: String): String =
        "run-as ${ShellQuote.quote(packageName)} sh -c ${ShellQuote.quote(script)}"

    /**
     * What the device printed, classified.
     *
     * Only the final non-empty line can be the status: the app's own output may well contain
     * something that looks like one, and a status line followed by more output is not ours.
     */
    fun classify(output: String): RunAsOutcome {
        val lines = output.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val status = lines.lastOrNull()?.let { EXIT_STATUS.matchEntire(it) }?.groupValues?.get(1)?.toInt()
        // Whatever the device printed before the status line: the script's own diagnostics,
        // or the refusal from run-as when the status line never arrived.
        val before = if (status != null) lines.dropLast(1) else lines
        val said = before.joinToString("\n").trim()

        return when {
            status == 0 -> RunAsOutcome.Succeeded(before)
            // The script ran, so whatever it said is about the script, not about run-as —
            // `sh: base64: not found` must not be reported as run-as failing to find the app.
            status != null -> RunAsOutcome.Failed(status, said)
            said.contains("not debuggable", ignoreCase = true) -> RunAsOutcome.NotDebuggable(said)
            said.contains("unknown", ignoreCase = true) || said.contains("not found", ignoreCase = true) ->
                RunAsOutcome.Unreachable(said)
            else -> RunAsOutcome.Failed(null, said)
        }
    }

    private val EXIT_STATUS = Regex("""rc=(-?\d+)\s*$""")
}

internal sealed interface RunAsOutcome {

    /** The script ran and reported status 0. [lines] is its output before the status line, trimmed. */
    data class Succeeded(val lines: List<String>) : RunAsOutcome

    /** `run-as` refused because the installed build is not debuggable. */
    data class NotDebuggable(val said: String) : RunAsOutcome

    /** `run-as` could not find the package for the current user. */
    data class Unreachable(val said: String) : RunAsOutcome

    /**
     * The script reported a non-zero [status], or — when [status] is null — nothing ran and the
     * refusal was not one of the recognised shapes. [said] is quoted verbatim, possibly empty.
     */
    data class Failed(val status: Int?, val said: String) : RunAsOutcome
}
