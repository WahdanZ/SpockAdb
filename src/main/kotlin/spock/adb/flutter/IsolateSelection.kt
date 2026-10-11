package spock.adb.flutter

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceRpcException

/** An isolate as `getIsolate` describes it, reduced to what selection needs. */
data class FlutterIsolate(
    val id: String,
    val name: String,
    val extensionRpcs: Set<String>,
    /** `pauseEvent.kind`: `Resume` or `None` when running, `PauseBreakpoint` and the like when not. */
    val pauseKind: String?,
) {
    /**
     * A service extension runs on the isolate's own event loop, so on a paused isolate it never
     * answers: calls would hang until they time out [FR12].
     */
    val paused: Boolean get() = pauseKind in PAUSED_KINDS

    val hasFlutterExtensions: Boolean get() = extensionRpcs.any { it.startsWith(FLUTTER_EXTENSION_PREFIX) }

    fun withExtension(rpc: String): FlutterIsolate = copy(extensionRpcs = extensionRpcs + rpc)

    companion object {
        const val FLUTTER_EXTENSION_PREFIX = "ext.flutter."
        const val INSPECTOR_PREFIX = "ext.flutter.inspector."
        const val STRUCTURED_ERRORS = "ext.flutter.inspector.structuredErrors"

        val PAUSED_KINDS = setOf(
            "PauseStart",
            "PauseExit",
            "PauseBreakpoint",
            "PauseInterrupted",
            "PauseException",
            "PausePostRequest",
        )

        /** Null for a `Sentinel` (an isolate that exited between `getVM` and `getIsolate`). */
        fun from(isolate: JsonObject): FlutterIsolate? {
            if (isolate.get("type")?.asStringOrNull() != "Isolate") return null
            val rpcs = (isolate.get("extensionRPCs") as? JsonArray)?.mapNotNull { it.asStringOrNull() }?.toSet()
            return FlutterIsolate(
                id = isolate.get("id")?.asStringOrNull() ?: return null,
                name = isolate.get("name")?.asStringOrNull().orEmpty(),
                extensionRpcs = rpcs.orEmpty(),
                pauseKind = (isolate.get("pauseEvent") as? JsonObject)?.get("kind")?.asStringOrNull(),
            )
        }
    }
}

/** Which isolate is the app's UI isolate, or why there is not exactly one. */
sealed interface IsolateSelection {

    data class Selected(val isolate: FlutterIsolate) : IsolateSelection

    /** Several running Flutter isolates (add-to-app, several engines): the caller chooses [FR22]. */
    data class Ambiguous(val candidates: List<FlutterIsolate>) : IsolateSelection

    /** Every candidate is paused in the debugger; calls to it would hang [FR12]. */
    data class Paused(val isolate: FlutterIsolate) : IsolateSelection {
        val message: String
            get() = "The app is paused in the debugger (${isolate.pauseKind}). Resume it in the IDE, then try again."
    }

    data class NoFlutterIsolate(val reason: String) : IsolateSelection
}

/**
 * Finds the UI isolate: the one behind a Flutter view, or failing that the one exposing
 * Flutter's service extensions. Blocking, on the caller's thread.
 */
internal class IsolateSelector(private val client: VmServiceClient) {

    fun select(): IsolateSelection {
        val viewIsolates = viewIsolateIds()
        val candidates = if (!viewIsolates.isNullOrEmpty()) {
            viewIsolates.mapNotNull(::describe)
        } else {
            val all = vmIsolateIds().mapNotNull(::describe)
            all.filter { FlutterIsolate.STRUCTURED_ERRORS in it.extensionRpcs }
                .ifEmpty { all.filter { it.hasFlutterExtensions } }
        }
        return decide(candidates)
    }

    fun describe(isolateId: String): FlutterIsolate? = try {
        FlutterIsolate.from(client.getIsolate(isolateId))
    } catch (_: VmServiceRpcException) {
        // Gone between listing and asking: an isolate exiting during a hot restart.
        null
    }

    /**
     * The isolates of the engine's views, from `_flutter.listViews` — an RPC the engine serves
     * and DDS forwards. Null when it is not served, which leaves the extension-based fallback.
     */
    private fun viewIsolateIds(): List<String>? {
        val result = try {
            client.call(LIST_VIEWS)
        } catch (_: VmServiceRpcException) {
            return null
        }
        val views = result.get("views") as? JsonArray ?: return null
        return views.mapNotNull { view ->
            ((view as? JsonObject)?.get("isolate") as? JsonObject)?.get("id")?.asStringOrNull()
        }.distinct()
    }

    private fun vmIsolateIds(): List<String> {
        val isolates = client.getVM().get("isolates") as? JsonArray ?: return emptyList()
        return isolates.mapNotNull { (it as? JsonObject)?.get("id")?.asStringOrNull() }
    }

    companion object {
        const val LIST_VIEWS = "_flutter.listViews"

        fun decide(candidates: List<FlutterIsolate>): IsolateSelection {
            if (candidates.isEmpty()) {
                return IsolateSelection.NoFlutterIsolate(
                    "No isolate exposes Flutter's service extensions: not a Flutter app, a release build, " +
                        "or the app is still starting.",
                )
            }
            val running = candidates.filterNot { it.paused }
            return when {
                running.size == 1 -> IsolateSelection.Selected(running.single())
                running.size > 1 -> IsolateSelection.Ambiguous(running)
                else -> IsolateSelection.Paused(candidates.first())
            }
        }
    }
}

internal fun JsonElement.asStringOrNull(): String? = if (this is JsonPrimitive && isString) asString else null
