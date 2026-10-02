package spock.adb.flutter.dtd

import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.Pubspec
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.nio.file.Path

/**
 * An app a Dart Tooling Daemon listed for the project: DDS's address, so a shared connection that
 * `flutter run` keeps working through [FR1].
 *
 * Found by its name alone, which cannot tell two flavors of one project or two emulators of one
 * image apart: [DtdAppIdentity] confirms it is the selected app before anything connects.
 */
class DtdCandidate(
    val service: DtdVmService,
    val daemon: DtdRegistryEntry,
    val workspace: WorkspaceRelation,
) : VmServiceCandidate(VmServiceSource.DTD, ddsLikely = true) {

    val workspaceMatch: WorkspaceMatch get() = workspace.match

    override val description: String
        get() = "${service.packageName ?: service.name.ifBlank { "app" }} on ${service.deviceModel ?: "a device"} " +
            "at ${service.uri.redacted()} (from the Dart Tooling Daemon)"

    override fun open(): VmServiceUri = service.uri
}

/**
 * Finds the project's running Flutter apps through the Dart Tooling Daemons on this machine
 * (spike S12): the [registry] lists live daemons; those whose workspace root is the project, is
 * inside it or holds it are asked `ConnectedApp.getVmServices`; the Flutter apps they list are
 * kept when they run on [deviceModel] and are the package [packageFor] names for that daemon.
 *
 * A daemon whose root holds the project counts only when that root is a Dart package (it has a
 * pubspec) and is neither the file system root nor the home directory — a daemon for the whole
 * disk is no clue to which project an app belongs to.
 *
 * A filter applies only when both sides are known: an app whose name does not say its device
 * or package is kept, ranked after those that match; an app whose name says it is not Flutter
 * (`Kind: Dart`) is dropped. Candidates come best first — an exact workspace match, then the
 * nearest workspace, then the fewest unknowns, then the newest daemon — with the same app
 * listed by two daemons kept once.
 *
 * Needs a Flutter SDK whose DTD has the `ConnectedApp` service (3.47.5 confirmed; 3.22 has none).
 * Blocking: call from a pooled thread. Never throws; a daemon that fails is skipped.
 */
class DtdDiscovery(
    private val projectPath: String,
    /** `ro.product.model` of the selected device, or null to accept any device. */
    private val deviceModel: String?,
    /**
     * The pubspec `name` the apps of a daemon so related to the project must have; null accepts
     * any package. Read from the pubspecs by default ([pubspecPackage]).
     */
    private val packageFor: (WorkspaceRelation) -> String? = ::pubspecPackage,
    private val registry: DtdRegistry = DtdRegistry(),
    private val query: (DtdUri) -> List<DtdVmService> = ::queryDaemon,
    private val home: String? = System.getProperty("user.home"),
) : VmServiceDiscovery {

    override fun discover(): List<VmServiceCandidate> = candidates()

    /** [discover], typed. */
    fun candidates(): List<DtdCandidate> {
        val excluded = listOfNotNull(home?.let(Workspace::canonical))
        val daemons = registry.entries().mapNotNull { entry -> bestMatch(entry, excluded)?.let { entry to it } }
        return daemons
            .flatMap { (entry, relation) ->
                val wantedPackage = packageFor(relation)?.takeIf { it.isNotBlank() }
                servicesOf(entry)
                    .filter { isFlutter(it) && matchesDevice(it) && matchesPackage(it, wantedPackage) }
                    .map { DtdCandidate(it, entry, relation) }
            }
            .sortedWith(
                compareBy<DtdCandidate> { it.workspaceMatch.ordinal }
                    .thenBy { it.workspace.distance }
                    .thenBy { unknowns(it.service) }
                    .thenByDescending { it.daemon.epoch ?: 0L },
            )
            .distinctBy { it.service.uri }
    }

    private fun bestMatch(entry: DtdRegistryEntry, excluded: List<Path>): WorkspaceRelation? =
        entry.workspaceRoots
            .mapNotNull { Workspace.relate(projectPath, it) }
            .filter { it.match != WorkspaceMatch.ENCLOSES_PROJECT || isPackageRoot(it.root, excluded) }
            .minWithOrNull(compareBy<WorkspaceRelation> { it.match.ordinal }.thenBy { it.distance })

    private fun isPackageRoot(root: Path, excluded: List<Path>): Boolean =
        root.parent != null && root !in excluded && Pubspec.exists(root)

    private fun servicesOf(entry: DtdRegistryEntry): List<DtdVmService> = try {
        query(entry.uri)
    } catch (e: VmServiceException) {
        // The message is already redacted. A METHOD_NOT_FOUND is an SDK older than ConnectedApp.
        log.info("Skipping $entry: ${e.message}")
        emptyList()
    }

    private fun isFlutter(service: DtdVmService): Boolean =
        service.kind == null || service.kind.equals(FLUTTER_KIND, ignoreCase = true)

    private fun matchesDevice(service: DtdVmService): Boolean {
        val wanted = deviceModel?.let(::normalise)?.takeIf { it.isNotEmpty() } ?: return true
        val listed = service.deviceModel?.let(::normalise) ?: return true
        return listed == wanted
    }

    private fun matchesPackage(service: DtdVmService, wanted: String?): Boolean {
        if (wanted == null) return true
        val listed = service.packageName ?: return true
        return listed == wanted
    }

    private fun unknowns(service: DtdVmService): Int =
        listOf(service.deviceModel, service.packageName).count { it == null }

    companion object {
        private val log = Logger.getInstance(DtdDiscovery::class.java)
        private const val FLUTTER_KIND = "Flutter"
        private val WIRELESS = Regex("""\s*\(wireless\)\s*$""")
        private val NOT_ALPHANUMERIC = Regex("""[^a-z0-9]+""")

        /**
         * A device name with what adb and `flutter` each do to it undone, so both sides compare:
         * adb's `devices -l` turns every character of `ro.product.model` that is not a letter or
         * digit into `_` (`SM-S918B` → `SM_S918B`); `flutter` turns `___` back into `, ` and `_`
         * into a space, and calls a device on an `adb-…._adb-tls-connect` serial
         * `<model> (wireless)`. What is left: lower case, letters and digits, single spaces.
         */
        fun normalise(model: String): String =
            model.lowercase().replace(WIRELESS, "").replace(NOT_ALPHANUMERIC, " ").trim()

        /**
         * The package `flutter run` reports for a daemon so related to the project, from the
         * pubspec of the app it ran: the project's own (an exact match), the daemon's root (an app
         * inside the project), or the nearest one above the project up to the daemon's root (a
         * daemon for an enclosing package). A pub workspace root's pubspec names no app, so it
         * means no package filter. Blocking (file reads).
         */
        fun pubspecPackage(relation: WorkspaceRelation): String? = when (relation.match) {
            WorkspaceMatch.EXACT, WorkspaceMatch.INSIDE_PROJECT -> Pubspec.appName(relation.root.toString())
            WorkspaceMatch.ENCLOSES_PROJECT -> generateSequence(relation.project) { it.parent }
                .takeWhile { it.startsWith(relation.root) }
                .firstOrNull(Pubspec::exists)
                ?.let { Pubspec.appName(it.toString()) }
        }

        private fun queryDaemon(uri: DtdUri): List<DtdVmService> = DtdClient.connect(uri).use { it.getVmServices() }
    }
}
