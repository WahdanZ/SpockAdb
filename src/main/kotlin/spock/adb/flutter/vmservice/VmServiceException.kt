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

/**
 * The VM answered the WebSocket upgrade with a redirect instead of a `101`: the Dart Development
 * Service has taken it over, and [target] is DDS's address. dart:io follows the redirect; the
 * JDK's client does not, so the caller connects to [target] itself. The message leaves the
 * address out.
 */
class VmServiceRedirectException(val target: VmServiceUri, from: String) :
    VmServiceException("$from hands its clients to the Dart Development Service")

/**
 * The address is the VM's own and no Dart Development Service runs in front of it: the app was
 * started without `flutter run` or `flutter attach`. A client that stays connected there makes
 * either of them fail to start DDS ("connection to device ended too early", spike S10), so the
 * session closed the connection rather than keep it. The message is fit to show.
 */
class NoDdsException : VmServiceException(
    "The app is running without a debugger session (no Dart Development Service). " +
        "Start it with `flutter run` or `flutter attach`; Spock did not stay connected, " +
        "because a direct connection would block them.",
)

/**
 * The UI isolate is paused in the debugger: an extension runs on its event loop and would not
 * answer until it resumes, so the call is not made [FR12].
 */
class VmServicePausedException(val isolateId: String, val pauseKind: String) :
    VmServiceException("The app is paused in the debugger ($pauseKind).")

/** No UI isolate to call: none runs Flutter, or several do and none has been chosen yet. */
class NoUiIsolateException(reason: String) : VmServiceException(reason)

/** A call that would change the app, refused on a connection that only watches. */
class ReadOnlyConnectionException(method: String) : VmServiceException(
    "$method would change the app, and this connection only watches it (no Dart Development Service).",
)

/** No answer within the call's timeout. The request is forgotten; a late answer is dropped. */
class VmServiceTimeoutException(val method: String, val timeoutMs: Long) :
    VmServiceException("$method got no answer in $timeoutMs ms")

/** The connection is gone — closed by Spock, by the app, or lost — so the call cannot finish. */
class VmServiceClosedException(reason: String, cause: Throwable? = null) :
    VmServiceException("VM Service connection closed: $reason", cause)
