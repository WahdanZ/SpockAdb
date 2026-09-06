package spock.adb.debugger

import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.debug.AndroidDebugger
import com.android.tools.idea.execution.common.processhandler.AndroidProcessHandler
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.joor.Reflect
import org.joor.Reflect.on
import java.lang.reflect.InvocationTargetException


class Debugger(private val project: Project, private val device: IDevice, private val packageName: String) {

    fun attach() {
        var client: Client? = null
        waitUntil {
            client = device.getClient(packageName)
            AndroidDebugger.EP_NAME.extensions.isNotEmpty() && client != null
        }
        for (androidDebugger in AndroidDebugger.EP_NAME.extensions) {
            if (androidDebugger.supportsProject(project)) {
                invokeLater { closeOldSessionAndRun(androidDebugger, device.getClient(packageName) ?: client ?: return@invokeLater) }
                break
            }
        }
    }

    private fun closeOldSessionAndRun(androidDebugger: AndroidDebugger<*>, client: Client) {
        terminateRunSessions(client)
        AttachToClient(androidDebugger, project, client).get()
    }

    // Disconnect any active run sessions to the same client
    private fun terminateRunSessions(selectedClient: Client) {
        TerminateRunSession(selectedClient, project).get()
    }
}

class TerminateRunSession(
    private val selectedClient: Client,
    private val project: Project
) : BackwardCompatibleGetter<Unit>() {
    override fun getCurrentImplementation() {
        val pid = selectedClient.clientData.pid
        // find if there are any active run sessions to the same client, and terminate them if so
        for (handler in ExecutionManager.getInstance(project).getRunningProcesses()) {
            if (handler is AndroidProcessHandler) {
                val client = handler.getClient(selectedClient.device)
                if (client != null && client.clientData.pid == pid) {
                    handler.detachProcess()
                    handler.notifyTextAvailable(
                        "Disconnecting run session: a new debug session will be established.\n",
                        ProcessOutputTypes.STDOUT
                    )
                    break
                }
            }
        }
    }

    override fun getPreviousImplementation() {
        val pid = pidFrom(selectedClient)
        // find if there are any active run sessions to the same client, and terminate them if so
        for (handler in RunningProcessesGetter(project).get()) {
            if (handler is AndroidProcessHandler) {
                val device = on(selectedClient).call("getDevice").get<IDevice>()
                val client = handler.getClient(device)
                if (client != null && pidFrom(client) == pid) {
                    handler.detachProcess()
                    handler.notifyTextAvailable(
                        "Disconnecting run session: a new debug session will be established.\n",
                        ProcessOutputTypes.STDOUT
                    )
                    break
                }
            }
        }

    }

    private fun pidFrom(client: Client) = on(client).call("getClientData").call("getPid").get<Int>()!!
}

/**
 * Hands the client to the IDE's own debugger, whichever shape its API is in.
 *
 * `attachToClient` has gained and lost a trailing parameter across Android Studio releases, so
 * the arity is discovered rather than assumed: the two known shapes first, then whatever the
 * class actually declares. Guessing wrong used to end in a stack trace naming a reflection
 * library, which tells a developer nothing they can act on.
 */
class AttachToClient(
    private val androidDebugger: AndroidDebugger<*>,
    private val project: Project,
    private val client: Client
) : BackwardCompatibleGetter<Unit>() {

    /** AS 2024+ takes a trailing run configuration, which is optional and unused here. */
    override fun getCurrentImplementation() {
        on(androidDebugger).call(ATTACH, project, client, null)
    }

    // Same reason as [BackwardCompatibleGetter.get]: what an IDE of an unknown version throws
    // for a missing method is not something narrower can be named for.
    @Suppress("TooGenericExceptionCaught")
    override fun getPreviousImplementation() {
        try {
            on(androidDebugger).call(ATTACH, project, client)
        } catch (e: Throwable) {
            if (!isApiMismatch(e)) throw e
            attachByDeclaredSignature(e)
        }
    }

    /**
     * Last resort: call whatever single `attachToClient` the class declares, padding the
     * arguments it wants beyond the project and the client with nulls.
     *
     * The two hard-coded shapes are the ones seen so far, not the ones that will exist. Reading
     * the signature means the next release that adds or drops a trailing optional parameter is
     * handled rather than reported as a crash.
     */
    // SpreadOperator: how a varargs Method.invoke is called at all, on an array of at most four
    // elements built once per attach. ThrowsCount: each exit names a different outcome — no such
    // signature, the call was rejected, or the call ran and failed — and the third must not be
    // reported as the first, which is the mistake this whole change is fixing.
    @Suppress("SpreadOperator", "ThrowsCount")
    private fun attachByDeclaredSignature(cause: Throwable) {
        val candidates = androidDebugger.javaClass.methods.filter { it.name == ATTACH }
        val method = candidates.singleOrNull()?.takeIf { it.parameterCount >= REQUIRED_PARAMETERS }
            ?: throw unsupported(candidates, cause)

        val arguments = arrayOfNulls<Any?>(method.parameterCount)
        arguments[0] = project
        arguments[1] = client
        try {
            method.invoke(androidDebugger, *arguments)
        } catch (e: InvocationTargetException) {
            // The method ran and threw. That is a real attach failure, not a signature this IDE
            // does not have, and reporting it as the latter would send the next reader hunting
            // for an API change that is not there.
            throw e.targetException ?: e
        } catch (e: ReflectiveOperationException) {
            throw unsupported(candidates, e)
        } catch (e: IllegalArgumentException) {
            // Wrong shape after all — a primitive trailing parameter cannot take the null pad.
            throw unsupported(candidates, e)
        }
    }

    /**
     * Gives up, naming what this IDE actually offers.
     *
     * The point of the message: the next report of this failure carries the real signature, so
     * the fix is reading one line rather than installing that Android Studio version.
     */
    private fun unsupported(candidates: List<java.lang.reflect.Method>, cause: Throwable) =
        UnsupportedOperationException(
            "Could not attach the debugger. ${androidDebugger.javaClass.name} does not accept " +
                "attachToClient(Project, Client) or attachToClient(Project, Client, RunConfiguration) " +
                "in this IDE. ${candidates.describe()} Restarting the app without a debugger still works.",
            cause,
        )

    private companion object {
        const val ATTACH = "attachToClient"

        /** The project and the client; anything after them is passed as null. */
        const val REQUIRED_PARAMETERS = 2

        fun List<java.lang.reflect.Method>.describe(): String = when {
            isEmpty() -> "It declares no attachToClient method at all."
            else -> "It declares: " + joinToString(", ") { method ->
                "attachToClient(" + method.parameterTypes.joinToString(", ") { it.simpleName } + ")"
            } + "."
        }
    }
}

private class RunningProcessesGetter(
    val project: Project
) : BackwardCompatibleGetter<Array<ProcessHandler>>() {
    override fun getCurrentImplementation(): Array<ProcessHandler> {
        return ExecutionManager.getInstance(project).getRunningProcesses()
    }

    override fun getPreviousImplementation(): Array<ProcessHandler> {
        return on<ExecutionManager>().call("getInstance", project).call("getRunningProcesses")
            .get<Array<ProcessHandler>>()
    }
}

fun waitUntil(timeoutMillis: Long = 30000L, step: Long = 100L, condition: () -> Boolean) {
    val endTime = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < endTime) {
        if (condition()) {
            return
        }
        Thread.sleep(step)
    }
}

fun invokeLater(runnable: () -> Unit) = ApplicationManager.getApplication().invokeLater(runnable)

@Suppress("DEPRECATION")
inline fun <reified T> on(): Reflect = on(T::class.java)
