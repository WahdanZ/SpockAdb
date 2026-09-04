package spock.adb.mcp

/**
 * Chooses which of the IDE's open projects an MCP call is about.
 *
 * An MCP client connects to the IDE, not to a project, so with more than one project open
 * there is a real ambiguity: the application ID, the sources an Activity is resolved
 * against and the default logcat filter all come from a project, and picking the wrong one
 * hands the agent confident answers about the wrong app.
 *
 * The rule deliberately mirrors device resolution: resolve when there is one right answer,
 * and **refuse to guess** when there is not, naming the candidates so the caller can choose.
 * Picking the focused window instead would be wrong exactly when it matters most — an agent
 * working while the developer is looking at something else.
 *
 * Generic and free of IntelliJ types, so the rules are unit tested directly rather than only
 * inside a running IDE.
 */
object ProjectResolution {

    sealed interface Outcome<out T> {

        /** Nothing is open, so there is nothing to choose between. */
        object None : Outcome<Nothing>

        data class Resolved<T>(val project: T, val source: Source) : Outcome<T>

        /** Several are open and none was chosen. [names] is what may be selected from. */
        data class Ambiguous(val names: List<String>) : Outcome<Nothing>
    }

    /** Why a project was chosen, so a caller can say so rather than appear to have guessed. */
    enum class Source {
        /** Named by an earlier `android_select_project`. */
        SELECTED,

        /** The only one open, so there was nothing to choose. */
        ONLY_OPEN,
    }

    /**
     * @param candidates the open projects.
     * @param selectedKey what was last selected, matched against [keysOf] case-insensitively.
     * @param keysOf every identifier a candidate answers to — its name and its path.
     * @param nameOf the human name, used only to describe an ambiguity.
     */
    fun <T> resolve(
        candidates: List<T>,
        selectedKey: String?,
        keysOf: (T) -> List<String>,
        nameOf: (T) -> String,
    ): Outcome<T> {
        if (candidates.isEmpty()) return Outcome.None

        // A stale selection — the project it named has since been closed — falls through
        // rather than failing: a choice should not outlive the project it was about.
        if (!selectedKey.isNullOrBlank()) {
            candidates.firstOrNull { candidate ->
                keysOf(candidate).any { it.isNotBlank() && it.equals(selectedKey, ignoreCase = true) }
            }?.let { return Outcome.Resolved(it, Source.SELECTED) }
        }

        candidates.singleOrNull()?.let { return Outcome.Resolved(it, Source.ONLY_OPEN) }

        return Outcome.Ambiguous(candidates.map(nameOf).sorted())
    }
}
