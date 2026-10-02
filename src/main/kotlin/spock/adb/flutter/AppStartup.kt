package spock.adb.flutter

import spock.adb.flutter.dtd.DtdAppIdentity
import spock.adb.flutter.vmservice.AnnouncedCandidate
import spock.adb.flutter.vmservice.VmServiceCandidate

/**
 * One process's start, followed across [FlutterSessionService.ensureSession] calls until its pids
 * change, and the rules for when its VM may be asked directly (spike S10). Read and written only
 * under that app's attach lock.
 *
 * The startup window is anchored on what is known of the start, latest first: the VM's
 * announcement in logcat when it is seen, else the process start. Measured on emulator-5554
 * (Flutter 3.22.2, 2026-10-02): the VM announced itself 6 s after the process started, and
 * `flutter run`'s DDS came later still — a window counted from the process start alone closed
 * before DDS was there.
 */
internal class AppStartup(val pids: Set<Long>, val startedAt: Long) {
    var lastDirectProbeAt: Long? = null

    /** Set once the process is known to run without DDS: no more direct probes for these pids. */
    var noDds: AppIdentity? = null

    /** When the VM's announcement was logged, on the host's clock; null until seen. */
    var vmAnnouncedAt: Long? = null
        private set

    /** The VM's port on the device, from its announcement; null until seen. */
    var vmDevicePort: Int? = null
        private set

    /** Since when a forward to [vmDevicePort] that Spock did not make exists; null while none does. */
    var foreignSince: Long? = null
        private set

    /** Until when a VM not reachable, or without DDS, may still be `flutter run` attaching. */
    val deadline: Long
        get() = maxOf(startedAt + STARTUP_GRACE_MS, (vmAnnouncedAt ?: Long.MIN_VALUE / 2) + VM_LINE_GRACE_MS)

    fun inWindow(now: Long): Boolean = now < deadline

    /**
     * Notes what [found] says about the VM: its announcement's time once — [announcedAt] reads
     * the logcat stamp on the host's clock, null when it cannot, and then the first sight is
     * taken — and, each time, whether a Flutter tool holds a forward to it ([foreignForward],
     * null when adb cannot say). Returns what changed, for the log; null when nothing did.
     */
    fun observe(
        found: VmServiceCandidate,
        now: Long,
        announcedAt: (String) -> Long?,
        foreignForward: (Int) -> Boolean?,
    ): String? {
        val notes = mutableListOf<String>()
        val announced = found as? AnnouncedCandidate
        if (announced != null && vmDevicePort == null) {
            vmDevicePort = announced.devicePort
            val at = announcedAt(announced.announcedStamp)?.coerceAtMost(now) ?: now
            vmAnnouncedAt = at
            notes += "the VM Service was announced ${now - at} ms ago, ${at - startedAt} ms after the process started"
        }
        val port = vmDevicePort ?: return notes.joinToString("; ").ifEmpty { null }
        when (foreignForward(port)) {
            true -> if (foreignSince == null) {
                foreignSince = now
                notes += "a Flutter tool forwards the VM Service's device port"
            }
            false -> if (foreignSince != null) {
                foreignSince = null
                notes += "the Flutter tool's forward is gone"
            }
            null -> Unit
        }
        return notes.joinToString("; ").ifEmpty { null }
    }

    /**
     * After a "no DDS" verdict: a Flutter tool that now forwards the VM — someone ran `flutter
     * attach` — makes it worth asking again. Clears the verdict when so.
     */
    fun toolAppeared(now: Long, foreignForward: (Int) -> Boolean?): Boolean {
        val port = vmDevicePort ?: return false
        if (noDds == null || foreignForward(port) != true) return false
        noDds = null
        foreignSince = now
        return true
    }

    /**
     * Why the direct VM must not be connected to yet, or null when it may be:
     * - in the first [DIRECT_PROBE_MIN_AGE_MS] after the process started or the VM announced
     *   itself, `flutter run` is forwarding and starting DDS;
     * - while a Flutter tool holds a forward to the VM and DDS is not confirmed, it is attaching
     *   or attached, and a client on the VM before DDS keeps DDS out (S10): no probe for up to
     *   [FOREIGN_TOOL_HOLD_MS] from when the forward was first seen;
     * - and never twice within [DIRECT_PROBE_INTERVAL_MS].
     */
    fun directProbeWait(now: Long): FlutterAttachOutcome.NotReady? {
        val age = now - maxOf(startedAt, vmAnnouncedAt ?: startedAt)
        val held = foreignSince?.let { now - it }
        val since = lastDirectProbeAt?.let { now - it }
        return when {
            inWindow(now) && age < DIRECT_PROBE_MIN_AGE_MS -> FlutterAttachOutcome.NotReady(
                "The app's VM appeared $age ms ago: waiting for `flutter run` to attach before asking it directly.",
                DIRECT_PROBE_MIN_AGE_MS - age,
            )
            held != null && held < FOREIGN_TOOL_HOLD_MS -> FlutterAttachOutcome.NotReady(
                "A Flutter tool is attaching to the app (it forwards the app's VM Service): Spock waits for its " +
                    "debugger session rather than connect to the VM itself.",
                FlutterSessionService.RETRY_MS,
            )
            since != null && since < DIRECT_PROBE_INTERVAL_MS -> FlutterAttachOutcome.NotReady(
                "Waiting before asking the app's VM again: `flutter run` may still be attaching.",
                DIRECT_PROBE_INTERVAL_MS - since,
            )
            else -> null
        }
    }

    companion object {
        /**
         * How long after a process starts a missing or DDS-less VM may still be `flutter run`
         * attaching, with no announcement seen: as long as a DTD's VM may start after its process
         * ([DtdAppIdentity.VM_START_LAG_MS], measured), so both paths wait as long.
         */
        const val STARTUP_GRACE_MS = DtdAppIdentity.VM_START_LAG_MS

        /**
         * How long after the VM's announcement `flutter run` may still be forwarding and starting
         * DDS. On emulator-5554 (3.22.2) DDS came seconds after an announcement that was itself 6 s
         * after the process start; 15 s leaves room for a slower machine.
         */
        const val VM_LINE_GRACE_MS = 15_000L

        /**
         * After the process start or the announcement, how long before the VM is first connected
         * to directly: `flutter run` starts DDS within the first seconds, and a direct client
         * then would keep it out (S10). DTD and logcat evidence come first.
         */
        const val DIRECT_PROBE_MIN_AGE_MS = 3_000L

        /** The least time between two direct connections to one app's VM, inside the window or not. */
        const val DIRECT_PROBE_INTERVAL_MS = DtdAppIdentity.DIRECT_PROBE_INTERVAL_MS

        /**
         * How long a Flutter tool's forward to the VM keeps Spock off the VM while DDS is not
         * confirmed. Without a Dart Tooling Daemon (Flutter 3.22) nothing but a probe can confirm
         * DDS, so this is also how long such an attach takes; after it a probe is allowed again —
         * a forward made by hand, with no tool behind it, must not hold Spock off for ever.
         */
        const val FOREIGN_TOOL_HOLD_MS = 60_000L
    }
}
