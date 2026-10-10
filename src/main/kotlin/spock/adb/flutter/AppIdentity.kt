package spock.adb.flutter

import spock.adb.flutter.vmservice.VmServiceCandidate

/** How Spock knows a VM Service is the app selected in Spock (plan H2). */
enum class IdentityCheck(val verified: Boolean, val label: String) {
    /**
     * Listed by a Dart Tooling Daemon; its VM runs as the app's pid on the selected device and
     * started with that process — a pid alone can repeat on another device.
     */
    DTD_PID_START(true, "dtd+pid+start"),

    /**
     * As [DTD_PID_START], but the selected device would not say when the process started
     * (`/proc` refused), so only the pid was compared — and only one candidate had it. Weaker:
     * the same pid on another device of one snapshot would pass too, were it the only one listed.
     */
    DTD_PID_ONLY(true, "dtd+pid"),

    /** Several Dart Tooling Daemon apps pass as the selected app: offered, not chosen. */
    DTD_PID_AMBIGUOUS(false, "dtd+pid, ambiguous"),

    /** Announced in logcat by one of the app's own pids on the selected device, its VM on that pid. */
    LOGCAT_PID(true, "logcat-pid"),

    /**
     * No applicationId was known: the only app a Dart Tooling Daemon lists for this project and a
     * device of this model. Two flavors or two emulators of one image would look the same.
     */
    DTD_NAME_UNVERIFIED(false, "dtd-name"),

    /** Pasted by the developer. Not probed: the address may be a direct VM's (spike S10). */
    PASTED_UNVERIFIED(false, "pasted"),
}

/**
 * Which app a session is for: [applicationId] on the device with [serial], running as [pid].
 * [pid] is null until known — an unverified candidate's is read from the VM once connected.
 * [serial] is the device whose `pidof` confirmed it, so its clock is the one to read the
 * session's device timestamps against.
 */
data class AppIdentity(
    val serial: String,
    val applicationId: String?,
    val pid: Long?,
    val verifiedBy: IdentityCheck,
) {
    val verified: Boolean get() = verifiedBy.verified

    override fun toString(): String =
        "${applicationId ?: "an app"} on $serial${pid?.let { " (pid $it)" }.orEmpty()}, ${verifiedBy.label}"
}

/** A [candidate] Spock could connect to, and how sure it is that it is the selected app. */
class IdentifiedCandidate(val candidate: VmServiceCandidate, val identity: AppIdentity) {
    override fun toString(): String = "$candidate [${identity.verifiedBy.label}]"
}

/**
 * What [FlutterSessionService.discover] found, best first, and [notes] on why anything is
 * missing — "the app is not running", "no app has its pid" — fit to show. [ambiguous] are the
 * Dart Tooling Daemon apps that all passed as the selected app, for the developer to choose
 * between; they are not in [candidates].
 */
class FlutterDiscoveryResult(
    val candidates: List<IdentifiedCandidate>,
    val notes: List<String>,
    val ambiguous: List<IdentifiedCandidate> = emptyList(),
) {
    val isEmpty: Boolean get() = candidates.isEmpty()
}
