package spock.adb.flutter.dtd

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.NoDdsException
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceProbe
import spock.adb.flutter.vmservice.VmServiceRedirectException
import spock.adb.flutter.vmservice.VmServiceUri
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells which app a Dart Tooling Daemon listed is the one selected in Spock — by process, not by
 * name (plan H2). A DTD name gives the pubspec package and the device model, which two flavors
 * of one project, or two emulators of one image, share. `getVM()` gives the VM's `pid` (seen on a
 * device: it equals `pidof <applicationId>`) and its `startTime`, on the device's clock. A pid is
 * only unique on its own device — two emulators booted from one snapshot can give the app the
 * same pid — so a candidate is the selected app when its VM runs as one of the app's pids on the
 * selected device **and** started within [VM_START_LAG_MS] of that process.
 *
 * Each candidate is asked over a bare connection — H1's DDS probe, then `getVM` only: no stream,
 * no write — closed at once, before any [spock.adb.flutter.FlutterSession] is made, so nothing
 * ever changes in an app that turns out to be another. DTD lists DDS addresses, which any number
 * of clients share. An address that turns out to be a VM's own — it redirects to DDS, or it is a
 * VM with no DDS, which gets nothing past the probe ([VmServiceProbe.kindOf]) — is not asked
 * again within [DIRECT_PROBE_INTERVAL_MS], as for any direct VM (spike S10), until S22 shows what
 * an IDE's DTD lists.
 *
 * Blocking: call from a pooled thread. Never throws; a candidate that does not answer is not
 * confirmed. [vmProcess] and [clock] are for tests.
 */
class DtdAppIdentity(
    private val vmProcess: (VmServiceUri) -> VmProcess? = ::readVmProcess,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * What a VM says about its process: [pid], and [startTime] in ms since the epoch on the
     * device's clock (null when not given). [viaRedirect] when the address asked was the VM's own,
     * which handed Spock to DDS.
     */
    data class VmProcess(val pid: Long, val startTime: Long?, val viaRedirect: Boolean = false)

    sealed interface Result {
        /** [candidate] runs as [pid]; [startChecked] when the start times agreed too, not just the pid. */
        data class Confirmed(val candidate: DtdCandidate, val pid: Long, val startChecked: Boolean) : Result

        /** Several candidates pass: Spock does not pick one by rank. */
        data class Ambiguous(val candidates: List<Confirmed>) : Result

        object None : Result
    }

    /** Addresses that turned out to be a VM's own, and when they were last asked. */
    private val directAskedAt = ConcurrentHashMap<String, Long>()

    /**
     * Which of [candidates] is the selected app. [appStarts] maps each of its pids on the selected
     * device to that process's start, in ms since the epoch on the device's clock — null when the
     * device would not say. A candidate whose start is known on both sides and disagrees is
     * rejected; when either is unknown the pid alone decides. Exactly one passing is [Result.Confirmed];
     * more are [Result.Ambiguous], whatever their rank.
     */
    fun confirm(candidates: List<DtdCandidate>, appStarts: Map<Long, Long?>): Result {
        if (appStarts.isEmpty() || candidates.isEmpty()) return Result.None
        val passing = candidates.mapNotNull { candidate ->
            processOf(candidate)?.let { vm -> match(vm, appStarts)?.let { Result.Confirmed(candidate, vm.pid, it) } }
        }
        return when (passing.size) {
            0 -> Result.None
            1 -> passing.single()
            else -> {
                log.info("${passing.size} DTD apps pass as the selected app: ${passing.map { it.candidate }}")
                Result.Ambiguous(passing)
            }
        }
    }

    /** Null when [vm] is not the app; else whether the start times were compared. */
    private fun match(vm: VmProcess, appStarts: Map<Long, Long?>): Boolean? {
        if (vm.pid !in appStarts) return null
        val processStart = appStarts[vm.pid] ?: return false
        val vmStart = vm.startTime ?: return false
        return if (vmStart - processStart in -START_SLACK_MS..VM_START_LAG_MS) true else null
    }

    private fun processOf(candidate: DtdCandidate): VmProcess? {
        val uri = candidate.open()
        val key = "${uri.host}:${uri.port}"
        val now = clock()
        val lastDirect = directAskedAt[key]
        if (lastDirect != null && now - lastDirect < DIRECT_PROBE_INTERVAL_MS) {
            log.info("Not asking $candidate again yet: its address is a VM's own")
            return null
        }
        return try {
            vmProcess(uri)?.also { if (it.viaRedirect) directAskedAt[key] = now }
        } catch (_: NoDdsException) {
            directAskedAt[key] = now
            log.info("$candidate is a VM with no DDS in front of it: not the app's DDS")
            null
        } catch (e: VmServiceException) {
            // Messages from the client are redacted.
            log.info("Could not read the process of $candidate: ${e.message}")
            null
        }
    }

    companion object {
        private val log = Logger.getInstance(DtdAppIdentity::class.java)

        /** `getVM` answers from memory; an app that takes longer is not worth holding discovery for. */
        const val TIMEOUT_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 2_000L

        /**
         * How long after its process the VM may start. Measured on an emulator (2026-10-02, debug
         * sample, five cold starts from `am start`): 1.2–4.0 s, the first start slowest. A slow
         * phone in debug can pass 5 s, so 15 s: still far below the gap between two processes
         * that merely got the same pid on two devices, which have to start within the same few
         * seconds to be confused at all. An add-to-app engine created later than this is
         * rejected here and found through logcat.
         */
        const val VM_START_LAG_MS = 15_000L

        /** The process start is read to the clock tick, on top of `btime`'s whole second. */
        const val START_SLACK_MS = 1_500L

        /** The least time between two connections to one VM's own address (spike S10). */
        const val DIRECT_PROBE_INTERVAL_MS = 4_000L

        /** Nothing is subscribed, so any event is unasked for. */
        private const val MAX_QUEUED_EVENTS = 16

        /** A `getVM` answer lists isolates by reference: kilobytes. */
        private const val MAX_MESSAGE_CHARS = 1024 * 1024

        /**
         * The process the VM at [uri] runs in, from one `getVM` on a connection of its own; null
         * when the answer names no pid. A VM address handing its clients to DDS is followed there
         * once. The connection first asks what answers ([VmServiceProbe.kindOf]); a VM with no
         * DDS gets nothing more.
         *
         * @throws NoDdsException when the VM itself answered, with no DDS in front of it.
         * @throws VmServiceException when the VM does not answer. The message is redacted.
         */
        fun readVmProcess(uri: VmServiceUri): VmProcess? {
            var redirected = false
            val client = try {
                open(uri)
            } catch (e: VmServiceRedirectException) {
                redirected = true
                open(e.target)
            }
            val vm = client.use {
                if (VmServiceProbe.kindOf(it) == ConnectionKind.DIRECT_NO_DDS) throw NoDdsException()
                it.getVM()
            }
            val pid = pidOf(vm) ?: return null
            return VmProcess(pid, longOf(vm, "startTime"), redirected)
        }

        /** The `pid` of a `VM` object: a number, or a string holding one. */
        fun pidOf(vm: JsonObject?): Long? = longOf(vm, "pid")

        private fun longOf(vm: JsonObject?, key: String): Long? {
            val value = vm?.get(key) as? JsonPrimitive ?: return null
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
