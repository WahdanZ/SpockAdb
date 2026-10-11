package spock.adb.flutter.dtd

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceLimits
import spock.adb.flutter.vmservice.VmServiceRpcException
import spock.adb.flutter.vmservice.VmServiceUri

/**
 * One app a Dart Tooling Daemon knows, from `ConnectedApp.getVmServices`.
 *
 * [uri] is the app's DDS address (spike S12). [name] reads `Kind: Flutter - Device: <device> -
 * Package: <package>`; [kind], [deviceModel] and [packageName] are its parts, null when the name
 * has another shape. [deviceModel] is how `flutter` names the device — `ro.product.model` as adb
 * and `flutter` reword it ([DtdDiscovery.normalise]) — and [packageName] is the pubspec `name`,
 * not the Android applicationId. The name is any registered client's to choose, so it is kept
 * to one line of at most [MAX_NAME_CHARS].
 */
class DtdVmService(
    val uri: VmServiceUri,
    val name: String,
    val kind: String?,
    val deviceModel: String?,
    val packageName: String?,
) {
    override fun toString(): String = "$name at ${uri.redacted()}"

    companion object {
        /**
         * Each part read on its own, so a name missing one still yields the others. A device name
         * may hold " - "; a pubspec name may not, so the package is read from the end.
         */
        private val KIND = Regex("""^\s*Kind:\s*(.*?)\s*(?:\s-\s+(?:Device|Package):|$)""")
        private val DEVICE = Regex("""\bDevice:\s*(.*?)\s*(?:\s-\s+Package:|$)""")
        private val PACKAGE = Regex("""\bPackage:\s*(\S+)\s*$""")
        private val CONTROL = Regex("""\p{Cntrl}""")

        /** Far longer than any `Kind: … - Device: … - Package: …` flutter writes. */
        const val MAX_NAME_CHARS = 200

        /**
         * The apps in a `VmServicesResponse`. An entry whose address is missing, unreadable or
         * not on loopback is dropped: it is no use to Spock, and its token must not travel on.
         */
        fun parse(result: JsonObject): List<DtdVmService> {
            val services = result.get("vmServices") as? JsonArray ?: return emptyList()
            return services.mapNotNull { element ->
                val service = element as? JsonObject ?: return@mapNotNull null
                val uri = service.text("uri")?.let(VmServiceUri::parseOrNull) ?: return@mapNotNull null
                val name = cleanName(service.text("name").orEmpty())
                DtdVmService(
                    uri = uri,
                    name = name,
                    kind = KIND.part(name),
                    deviceModel = DEVICE.part(name),
                    packageName = PACKAGE.part(name),
                )
            }
        }

        /**
         * Control characters — newlines included — become spaces, any VM Service address or token
         * in it is scrubbed (the name ends up in logs and descriptions), then the name is capped.
         */
        private fun cleanName(raw: String): String = Redaction.scrub(raw.replace(CONTROL, " ")).take(MAX_NAME_CHARS)

        private fun Regex.part(name: String): String? = find(name)?.groupValues?.get(1)?.ifBlank { null }

        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString
    }
}

/**
 * A Dart Tooling Daemon failed to answer. The message never carries its secret nor a VM Service
 * token. [rpcCode] is the JSON-RPC error code when the daemon answered with an error —
 * [VmServiceRpcException.METHOD_NOT_FOUND] from a DTD with no `ConnectedApp` service (older SDKs).
 */
class DtdException(message: String, val rpcCode: Int? = null) : VmServiceException(message)

/**
 * A client for a Dart Tooling Daemon: JSON-RPC 2.0 over WebSocket, the same core as
 * [VmServiceClient]. Spock only asks it which apps are running; it registers nothing and sets
 * nothing.
 *
 * Blocking: call from a pooled thread. Every exception is a [DtdException] with the daemon's
 * secret scrubbed by value ([DtdUri.scrub]), and none chains a cause, whose message could quote
 * the address.
 */
class DtdClient private constructor(
    private val uri: DtdUri,
    private val rpc: VmServiceClient,
) : AutoCloseable {

    /**
     * The apps registered with the daemon. Needs no secret (spike S12).
     *
     * @throws DtdException when the daemon does not answer, or answers with an error.
     */
    fun getVmServices(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<DtdVmService> {
        val result = guarded { rpc.call(GET_VM_SERVICES, JsonObject(), timeoutMs) }
        return DtdVmService.parse(result)
    }

    override fun close() = rpc.close()

    private fun <T> guarded(block: () -> T): T {
        val failure: VmServiceException = try {
            return block()
        } catch (e: VmServiceException) {
            e
        }
        throw DtdException(uri.scrub(failure.message.orEmpty()), (failure as? VmServiceRpcException)?.code)
    }

    companion object {
        const val GET_VM_SERVICES = "ConnectedApp.getVmServices"

        /** A daemon answers this from memory; a slow one is not worth holding discovery for. */
        const val DEFAULT_TIMEOUT_MS = 3_000L

        /** A loopback connect that takes longer is to a pid reused by something that is not a DTD. */
        const val CONNECT_TIMEOUT_MS = 2_000L

        /** Spock subscribes to nothing on a daemon, so any event is unasked for: hold few. */
        const val MAX_QUEUED_EVENTS = 16

        /** A `getVmServices` answer is a few hundred bytes per app; a megabyte is a fault. */
        const val MAX_MESSAGE_CHARS = 1024 * 1024

        /**
         * Opens a connection to the daemon at [uri].
         *
         * @throws DtdException when it cannot be opened. The message is redacted.
         */
        fun connect(
            uri: DtdUri,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
            connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        ): DtdClient {
            val label = "the Dart Tooling Daemon at ${uri.redacted()}"
            val failure: VmServiceException = try {
                val limits = VmServiceLimits(timeoutMs, connectTimeoutMs, MAX_QUEUED_EVENTS, MAX_MESSAGE_CHARS)
                return DtdClient(uri, VmServiceClient.open(uri.webSocketUri, label, limits))
            } catch (e: VmServiceException) {
                e
            }
            // Not chained, as in guarded(): the cause's message may quote the address.
            throw DtdException(uri.scrub(failure.message.orEmpty()))
        }
    }
}
