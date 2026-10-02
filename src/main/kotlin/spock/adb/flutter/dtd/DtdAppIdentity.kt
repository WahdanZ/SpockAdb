package spock.adb.flutter.dtd

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceRedirectException
import spock.adb.flutter.vmservice.VmServiceUri

/**
 * Tells which app a Dart Tooling Daemon listed is the one selected in Spock — by process, not by
 * name (plan H2). A DTD name gives the pubspec package and the device model, which two flavors
 * of one project, or two emulators of one image, share. The VM's `getVM().pid` is the app's pid
 * on its device (seen on a device: it equals `pidof <applicationId>`), so a candidate whose VM
 * runs as one of the selected app's pids on the selected device is that app, and no other.
 *
 * Each candidate is asked over a bare connection — `getVM` only: no stream, no write — closed at
 * once, before any [spock.adb.flutter.FlutterSession] is made, so nothing ever changes in an app
 * that turns out to be another. The addresses are DDS's (DTD lists DDS), which any number of
 * clients share; a direct VM address is never probed (spike S10).
 *
 * Blocking: call from a pooled thread. Never throws; a candidate that does not answer is not
 * confirmed. [vmPid] is for tests.
 */
class DtdAppIdentity(private val vmPid: (VmServiceUri) -> Long? = ::readVmPid) {

    /** [candidate] runs as [pid], one of the selected app's. */
    class Confirmed(val candidate: VmServiceCandidate, val pid: Long)

    /**
     * The best-ranked of [candidates] whose VM runs as one of [appPids], the pids of the selected
     * app on the selected device; null when none does. Every candidate is asked, so that two
     * claiming one pid — which one VM per process rules out — are logged.
     */
    fun confirm(candidates: List<VmServiceCandidate>, appPids: Set<Long>): Confirmed? {
        if (appPids.isEmpty() || candidates.isEmpty()) return null
        val confirmed = candidates.mapNotNull { candidate ->
            pidOf(candidate)?.takeIf { it in appPids }?.let { Confirmed(candidate, it) }
        }
        if (confirmed.size > 1) {
            log.info(
                "${confirmed.size} DTD apps run as the selected app's pid: " +
                    "${confirmed.joinToString { "${it.candidate} (pid ${it.pid})" }}; using the best-ranked",
            )
        }
        return confirmed.firstOrNull()
    }

    private fun pidOf(candidate: VmServiceCandidate): Long? = try {
        vmPid(candidate.open()).also { log.debug("$candidate runs as pid $it") }
    } catch (e: VmServiceException) {
        // Messages from the client are redacted.
        log.info("Could not read the pid of $candidate: ${e.message}")
        null
    }

    companion object {
        private val log = Logger.getInstance(DtdAppIdentity::class.java)

        /** `getVM` answers from memory; an app that takes longer is not worth holding discovery for. */
        const val TIMEOUT_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 2_000L

        /** Nothing is subscribed, so any event is unasked for. */
        private const val MAX_QUEUED_EVENTS = 16

        /** A `getVM` answer lists isolates by reference: kilobytes. */
        private const val MAX_MESSAGE_CHARS = 1024 * 1024

        /**
         * The pid the VM at [uri] runs as, from one `getVM` on a connection of its own; null when
         * the answer has none. A VM address handing its clients to DDS is followed there once.
         *
         * @throws VmServiceException when the VM does not answer. The message is redacted.
         */
        fun readVmPid(uri: VmServiceUri): Long? {
            val client = try {
                open(uri)
            } catch (e: VmServiceRedirectException) {
                open(e.target)
            }
            return client.use { pidOf(it.getVM()) }
        }

        /** The `pid` of a `VM` object: a number, or a string holding one. */
        fun pidOf(vm: JsonObject?): Long? {
            val value = vm?.get("pid") as? JsonPrimitive ?: return null
            return when {
                value.isNumber -> value.asNumber.toLong()
                value.isString -> value.asString.trim().toLongOrNull()
                else -> null
            }?.takeIf { it > 0 }
        }

        private fun open(uri: VmServiceUri): VmServiceClient =
            VmServiceClient.connect(uri, TIMEOUT_MS, CONNECT_TIMEOUT_MS, MAX_QUEUED_EVENTS, MAX_MESSAGE_CHARS)
    }
}
