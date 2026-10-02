package spock.adb.flutter

import spock.adb.flutter.vmservice.VmServiceCandidate

/** How Spock knows a VM Service is the app selected in Spock (plan H2). */
enum class IdentityCheck(val verified: Boolean, val label: String) {
    /** Listed by a Dart Tooling Daemon, and its VM's `getVM().pid` is the app's pid on the selected device. */
    DTD_PID(true, "dtd+pid"),

    /** Announced in logcat by one of the app's own pids on the selected device. */
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
 * missing — "the app is not running", "no app has its pid" — fit to show.
 */
class FlutterDiscoveryResult(val candidates: List<IdentifiedCandidate>, val notes: List<String>) {
    val isEmpty: Boolean get() = candidates.isEmpty()
}
