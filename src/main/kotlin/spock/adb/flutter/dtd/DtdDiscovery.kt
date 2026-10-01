package spock.adb.flutter.dtd

import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.Pubspec
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri

/**
 * An app a Dart Tooling Daemon listed for the project: DDS's address, so a shared connection that
 * `flutter run` keeps working through [FR1].
 */
class DtdCandidate(
    val service: DtdVmService,
    val daemon: DtdRegistryEntry,
    val workspaceMatch: WorkspaceMatch,
) : VmServiceCandidate(VmServiceSource.DTD, ddsLikely = true) {

    override val description: String
        get() = "${service.packageName ?: service.name.ifBlank { "app" }} on ${service.deviceModel ?: "a device"} " +
            "at ${service.uri.redacted()} (from the Dart Tooling Daemon)"

    override fun open(): VmServiceUri = service.uri
}

/**
 * Finds the project's running Flutter apps through the Dart Tooling Daemons on this machine
 * (spike S12): the [registry] lists live daemons; those whose workspace root is the project, is
 * inside it or holds it are asked `ConnectedApp.getVmServices`; the apps they list are kept when
 * they run on [deviceModel] and are the project's [packageName].
 *
 * A filter applies only when both sides are known: an app whose name does not say its device
 * or package is kept, ranked after those that match. Candidates come best first — an exact
 * workspace match, then the fewest unknowns, then the newest daemon — with the same app listed
 * by two daemons kept once.
 *
 * Needs a Flutter SDK whose DTD has the `ConnectedApp` service (3.47.5 confirmed; 3.22 has none).
 * Blocking: call from a pooled thread. Never throws; a daemon that fails is skipped.
 */
class DtdDiscovery(
    private val projectPath: String,
    /** `ro.product.model` of the selected device, or null to accept any device. */
    private val deviceModel: String?,
    /** The pubspec `name`; read from the project root by default. Null accepts any package. */
    private val packageName: String? = Pubspec.name(projectPath),
    private val registry: DtdRegistry = DtdRegistry(),
    private val query: (DtdUri) -> List<DtdVmService> = ::queryDaemon,
) : VmServiceDiscovery {

    override fun discover(): List<VmServiceCandidate> = candidates()

    /** [discover], typed. */
    fun candidates(): List<DtdCandidate> {
        val daemons = registry.entries().mapNotNull { entry -> bestMatch(entry)?.let { entry to it } }
        return daemons
            .flatMap { (entry, match) -> servicesOf(entry).map { DtdCandidate(it, entry, match) } }
            .filter { matchesDevice(it.service) && matchesPackage(it.service) }
            .sortedWith(
                compareBy<DtdCandidate> { it.workspaceMatch.ordinal }
                    .thenBy { unknowns(it.service) }
                    .thenByDescending { it.daemon.epoch ?: 0L },
            )
            .distinctBy { it.service.uri }
    }

    private fun bestMatch(entry: DtdRegistryEntry): WorkspaceMatch? =
        entry.workspaceRoots.mapNotNull { Workspace.match(projectPath, it) }.minOrNull()

    private fun servicesOf(entry: DtdRegistryEntry): List<DtdVmService> = try {
        query(entry.uri)
    } catch (e: VmServiceException) {
        // The message is already redacted. A METHOD_NOT_FOUND is an SDK older than ConnectedApp.
        log.info("Skipping $entry: ${e.message}")
        emptyList()
    }

    private fun matchesDevice(service: DtdVmService): Boolean {
        val wanted = deviceModel?.let(::normalise)?.takeIf { it.isNotEmpty() } ?: return true
        val listed = service.deviceModel?.let(::normalise) ?: return true
        return listed == wanted
    }

    private fun matchesPackage(service: DtdVmService): Boolean {
        val wanted = packageName?.takeIf { it.isNotBlank() } ?: return true
        val listed = service.packageName ?: return true
        return listed == wanted
    }

    private fun unknowns(service: DtdVmService): Int =
        listOf(service.deviceModel, service.packageName).count { it == null }

    companion object {
        private val log = Logger.getInstance(DtdDiscovery::class.java)
        private val WHITESPACE = Regex("""\s+""")

        /**
         * A device name as `flutter` and ddmlib both spell it: `flutter devices` turns the
         * underscores of `adb devices -l`'s model into spaces (`sdk_gphone64_arm64` →
         * `sdk gphone64 arm64`), so both sides lose them, and case.
         */
        fun normalise(model: String): String = model.replace('_', ' ').trim().replace(WHITESPACE, " ").lowercase()

        private fun queryDaemon(uri: DtdUri): List<DtdVmService> = DtdClient.connect(uri).use { it.getVmServices() }
    }
}
