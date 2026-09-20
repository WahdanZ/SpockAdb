package spock.adb.logcat

/**
 * What is known about the processes of the app under development.
 *
 * This used to be a bare `Set<Int>`, and an empty one meant four different things: not asked
 * yet, currently asking, asked and the app is not running, and asked and the question failed.
 * The filter could not tell them apart, so it did the only thing it could and matched
 * everything — which made **App** behave exactly like **All** during the most ordinary moments
 * of Android development: the second after pressing Live, an app that has been force-stopped, a
 * device that answered `pidof` with nothing.
 *
 * That is a failure that looks like success. The toolbar said App, the panel filled with lines,
 * and nothing about the screen suggested those lines belonged to other processes — until they
 * were copied into a bug report, or sent to a model as "the app's logs".
 *
 * So the state is named, and [State.RUNNING] is the only one that carries PIDs to match.
 */
data class AppProcesses(
    val state: State,
    val pids: Set<Int> = emptySet(),
    /** The application id, known even while the PIDs are not. */
    val packageName: String = "",
) {

    enum class State {
        /** No app has been resolved — no project app, or nothing asked yet. */
        UNKNOWN,

        /** `pidof` is in flight. */
        RESOLVING,

        /** The app is running and [pids] is what it is running as. */
        RUNNING,

        /** The app was looked for and is not running: it has no processes to match. */
        NOT_RUNNING,

        /** The device could not be asked, so nothing is known about the app's processes. */
        FAILED,
    }

    /** True only when the PIDs can be trusted to be the app's, and complete. */
    val isResolved: Boolean get() = state == State.RUNNING

    fun contains(pid: Int): Boolean = pid in pids

    /** A line that names the package is about this app whichever process logged it. */
    fun isNamedIn(message: String): Boolean =
        packageName.isNotBlank() && message.contains(packageName, ignoreCase = true)

    fun withPids(resolved: Set<Int>): AppProcesses = copy(
        state = if (resolved.isEmpty()) State.NOT_RUNNING else State.RUNNING,
        pids = resolved,
    )

    /** A process of this app started, seen in the log rather than asked for. */
    fun plus(pid: Int): AppProcesses = copy(state = State.RUNNING, pids = pids + pid)

    /**
     * A process of this app died.
     *
     * The PID is dropped so its successor's lines are not mistaken for it, and the state falls
     * back to [State.NOT_RUNNING] once the last one is gone. Lines the dead process already
     * logged stop matching, which is the honest answer: they are no longer this app's running
     * state, and the buffer still holds them for the All scope.
     */
    fun minus(pid: Int): AppProcesses {
        val remaining = pids - pid
        return copy(state = if (remaining.isEmpty()) State.NOT_RUNNING else State.RUNNING, pids = remaining)
    }

    companion object {
        val UNKNOWN = AppProcesses(State.UNKNOWN)

        fun resolving(packageName: String) = AppProcesses(State.RESOLVING, packageName = packageName)

        fun failed(packageName: String) = AppProcesses(State.FAILED, packageName = packageName)
    }
}
