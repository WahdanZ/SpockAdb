package spock.adb.flutter.vmservice

/**
 * Asks what answers at an address — DDS or the VM itself — on a connection that makes that one
 * call and is closed at once, whatever the answer.
 *
 * Spike S10's race: an app started by `flutter run` prints its VM's address a moment before
 * flutter_tools starts DDS on that VM, and DDS will not start while the VM has another client
 * ("Existing VM service clients prevent DDS from taking control"; on a device, `flutter attach`
 * failed with "connection to device ended too early"). A client on the direct address in that
 * window can make the developer's `flutter run` fail. So the probe sends nothing but
 * `getDartDevelopmentServiceVersion` — no `streamListen`, no extension — and holds the
 * connection only for that call; a VM that has handed over to DDS answers the upgrade with a
 * `302`, followed without holding anything on the VM. The window is narrowed, not closed: a
 * [Result.DirectNoDds] soon after the app started may be DDS not up yet, so the caller decides
 * whether to wait and ask again (a start-up grace window) or report it.
 */
object VmServiceProbe {

    sealed interface Result {
        /** DDS answered at [target]: the address asked, or the one the VM redirected to. */
        data class Dds(val target: VmServiceUri) : Result

        /** The VM itself answered, with no DDS in front of it. Not an error: see [VmServiceProbe]. */
        data object DirectNoDds : Result

        /** Nothing usable answered; [reason] is fit to show and carries no auth code. */
        data class Unreachable(val reason: String) : Result
    }

    /** Blocking, up to the connector's timeouts: call it from a pooled thread. */
    fun probe(
        uri: VmServiceUri,
        connector: (VmServiceUri) -> VmServiceClient = { VmServiceClient.connect(it) },
    ): Result {
        var target = uri
        val result = try {
            val client = try {
                connector(uri)
            } catch (e: VmServiceRedirectException) {
                target = e.target
                connector(e.target)
            }
            client.use { kindOf(it) }
        } catch (e: VmServiceException) {
            return Result.Unreachable(e.message.orEmpty())
        }
        return when (result) {
            ConnectionKind.DDS -> Result.Dds(target)
            ConnectionKind.DIRECT_NO_DDS -> Result.DirectNoDds
        }
    }

    /**
     * The probe's one call, on a connection that has sent nothing yet. Only "method not found"
     * means the VM itself; any other failure is the connection's.
     *
     * @throws VmServiceException when the call fails any other way.
     */
    fun kindOf(client: VmServiceClient, timeoutMs: Long = client.defaultTimeoutMs): ConnectionKind = try {
        client.call(DDS_VERSION, timeoutMs = timeoutMs)
        ConnectionKind.DDS
    } catch (e: VmServiceRpcException) {
        if (e.code != VmServiceRpcException.METHOD_NOT_FOUND) throw e
        ConnectionKind.DIRECT_NO_DDS
    }

    /** Served by DDS only; the VM itself answers it with "method not found". */
    const val DDS_VERSION = "getDartDevelopmentServiceVersion"
}
