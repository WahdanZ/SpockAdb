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
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext


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

    /**
     * Current Android Studio versions attach through [DebugSessionStarter] rather than through
     * [AndroidDebugger]. Try that path first, while keeping the old API below for older IDEs.
     */
    override fun getCurrentImplementation() {
        if (ModernDebuggerAttach.attach(androidDebugger, project, client)) return

        // AS 2024+ takes a trailing run configuration, which is optional and unused here.
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
        val candidates = declaredAttachMethods()
        val method = candidates.singleOrNull()?.takeIf { it.parameterCount >= REQUIRED_PARAMETERS }
            ?: throw unsupported(candidates, cause)

        val arguments = arrayOfNulls<Any?>(method.parameterCount)
        arguments[0] = project
        arguments[1] = client
        method.trySetAccessible()
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
    private fun declaredAttachMethods(): List<java.lang.reflect.Method> {
        val methods = mutableListOf<java.lang.reflect.Method>()
        var current: Class<*>? = androidDebugger.javaClass
        while (current != null) {
            methods += current.declaredMethods.filter { it.name == ATTACH }
            current = current.superclass
        }
        return methods.distinct()
    }

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

/**
 * Android Studio moved debugger attachment out of [AndroidDebugger]. The replacement
 * is intentionally looked up at runtime: it is absent from the older Studio builds that this
 * plugin supports, and directly linking it would prevent the plugin from loading there.
 */
internal object ModernDebuggerAttach {
    private const val STARTER_CLASS =
        "com.android.tools.idea.execution.common.debug.DebugSessionStarter"
    private const val ATTACH_TO_CLIENT_AND_SHOW_TAB = "attachDebuggerToClientAndShowTab"
    private const val CREATE_STATE = "createState"

    /**
     * Invokes the current Android Studio debugger entry point when available.
     *
     * `false` means this IDE does not have a compatible shape, so the caller may try the legacy
     * `AndroidDebugger.attachToClient` path. Once the current method is invoked, its exception is
     * propagated — falling back after a genuine attach failure would mask the useful error.
     */
    fun attach(androidDebugger: Any, project: Any, client: Any): Boolean {
        val starterClass = try {
            Class.forName(STARTER_CLASS, false, androidDebugger.javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            return false
        }
        return attach(starterClass, androidDebugger, project, client)
    }

    /** Kept separate so the signature matching is testable without an Android Studio runtime. */
    internal fun attach(
        starterClass: Class<*>,
        androidDebugger: Any,
        project: Any,
        client: Any,
    ): Boolean {
        val state = createState(androidDebugger) ?: return false
        val call = attachCall(starterClass, project, client, androidDebugger, state) ?: return false
        return invokeAttach(call, project, client, androidDebugger, state)
    }

    private fun createState(androidDebugger: Any): Any? {
        val factory = declaredMethodsFor(androidDebugger.javaClass, CREATE_STATE)
            .singleOrNull { it.parameterCount == NO_PARAMETERS }
            ?: return null
        factory.trySetAccessible()
        return invokeStateOrNull(factory, androidDebugger)
    }

    private fun attachCall(
        starterClass: Class<*>,
        project: Any,
        client: Any,
        androidDebugger: Any,
        state: Any,
    ): AttachCall? {
        val method = declaredMethodsFor(starterClass, ATTACH_TO_CLIENT_AND_SHOW_TAB).singleOrNull {
            it.matchesAttachSignature(project, client, androidDebugger, state)
        } ?: return null
        method.trySetAccessible()
        val receiver = if (Modifier.isStatic(method.modifiers)) null else starterInstance(starterClass) ?: return null
        return AttachCall(method, receiver)
    }

    private fun Method.matchesAttachSignature(
        project: Any,
        client: Any,
        androidDebugger: Any,
        state: Any,
    ): Boolean {
        if (name != ATTACH_TO_CLIENT_AND_SHOW_TAB || parameterCount !in ATTACH_PARAMETER_COUNTS) return false

        val commonArguments = listOf(project, client, androidDebugger, state)
        val commonMatch = parameterTypes.take(commonArguments.size)
            .zip(commonArguments)
            .all { (type, argument) -> type.isInstance(argument) }

        return when (parameterCount) {
            REGULAR_ATTACH_PARAMETER_COUNT -> commonMatch
            SUSPEND_ATTACH_PARAMETER_COUNT -> commonMatch &&
                Continuation::class.java.isAssignableFrom(parameterTypes.last())
            else -> false
        }
    }

    private fun starterInstance(starterClass: Class<*>): Any? = try {
        starterClass.getField("INSTANCE").get(null)
    } catch (_: ReflectiveOperationException) {
        null
    }

    private fun invokeAttach(
        call: AttachCall,
        project: Any,
        client: Any,
        androidDebugger: Any,
        state: Any,
    ): Boolean = when (call.method.parameterCount) {
        SUSPEND_ATTACH_PARAMETER_COUNT -> invokeOrFalse {
            call.method.invoke(call.receiver, project, client, androidDebugger, state, AttachContinuation)
        }
        REGULAR_ATTACH_PARAMETER_COUNT -> invokeOrFalse {
            call.method.invoke(call.receiver, project, client, androidDebugger, state)
        }
        else -> false
    }

    private fun declaredMethodsFor(type: Class<*>, name: String): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = type
        while (current != null) {
            methods += current.declaredMethods.filter { it.name == name }
            current = current.superclass
        }
        return methods
    }

    private fun invokeStateOrNull(method: Method, receiver: Any): Any? = try {
        method.invoke(receiver)
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    } catch (_: ReflectiveOperationException) {
        null
    }

    private fun invokeOrFalse(invocation: () -> Any?): Boolean = try {
        invocation()
        true
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    } catch (_: ReflectiveOperationException) {
        false
    }

    /**
     * Android Studio 2025.1 declares this as a suspend function. Its fifth Java-level parameter
     * is a [Continuation]; later releases expose a regular four-argument function instead.
     */
    private object AttachContinuation : Continuation<Any?> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<Any?>) {
            result.getOrThrow()
        }
    }

    private data class AttachCall(val method: Method, val receiver: Any?)

    private const val NO_PARAMETERS = 0
    private const val REGULAR_ATTACH_PARAMETER_COUNT = 4
    private const val SUSPEND_ATTACH_PARAMETER_COUNT = 5
    private val ATTACH_PARAMETER_COUNTS = REGULAR_ATTACH_PARAMETER_COUNT..SUSPEND_ATTACH_PARAMETER_COUNT
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
