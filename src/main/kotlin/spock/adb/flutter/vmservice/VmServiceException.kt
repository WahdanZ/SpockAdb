package spock.adb.flutter.vmservice

import com.google.gson.JsonElement

/** A VM Service call that did not produce a result. Messages never carry the auth code. */
open class VmServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The VM Service answered with a JSON-RPC `error`. */
class VmServiceRpcException(
    val method: String,
    val code: Int,
    val rpcMessage: String,
    val data: JsonElement?,
) : VmServiceException("$method failed: $rpcMessage ($code)") {
    companion object {
        /** JSON-RPC: the method does not exist (an extension not registered, an RPC not served). */
        const val METHOD_NOT_FOUND = -32_601

        /** vm_service: `streamListen` on a stream this client already listens to. */
        const val STREAM_ALREADY_SUBSCRIBED = 103

        /** vm_service: an extension called on an isolate that has not registered it. */
        const val EXTENSION_NOT_REGISTERED = 113
    }
}

/** No answer within the call's timeout. The request is forgotten; a late answer is dropped. */
class VmServiceTimeoutException(val method: String, val timeoutMs: Long) :
    VmServiceException("$method got no answer in $timeoutMs ms")

/** The connection is gone — closed by Spock, by the app, or lost — so the call cannot finish. */
class VmServiceClosedException(reason: String, cause: Throwable? = null) :
    VmServiceException("VM Service connection closed: $reason", cause)
