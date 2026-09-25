package spock.adb.mcp.tools

import spock.adb.CancellationSignal

/**
 * [base], with a caller's own stop flag added to its [cancellationSignal].
 *
 * For a caller that cancels by setting a flag rather than interrupting a thread: the assistant's
 * Stop button, since `AgentLoop` deliberately never interrupts — that would tear down the model's
 * HTTP connection instead of ending the turn. Everything else is [base]'s.
 */
class CancellableToolContext(
    private val base: ToolContext,
    private val flag: () -> Boolean,
) : ToolContext by base {

    /**
     * Cancelled when [flag] is set or [base]'s own signal is. [base]'s is taken here, on the
     * calling thread, because the default one captures whichever thread asks first.
     */
    override fun cancellationSignal(): CancellationSignal {
        val baseSignal = base.cancellationSignal()
        return CancellationSignal { flag() || baseSignal.isCancelled() }
    }
}
