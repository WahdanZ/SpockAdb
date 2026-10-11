package spock.adb.flutter.dtd

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import spock.adb.ShellQuote
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.ProcessTiming
import spock.adb.flutter.Pubspec
import spock.adb.flutter.SessionState
import java.util.concurrent.TimeUnit

/**
 * Not a unit test: the P4b discovery check, against this machine's real Dart Tooling Daemons.
 * Skipped unless `SPOCK_DTD_PROJECT` names a Flutter project that `flutter run` (an SDK with the
 * DTD `ConnectedApp` service, e.g. 3.47.5) is running from:
 *
 * ```
 * SPOCK_DTD_PROJECT=/path/to/flutter/project \
 *   ./gradlew test --tests spock.adb.flutter.dtd.DtdLiveCheck --rerun -i
 * ```
 *
 * It prints every live registry entry and the candidates found for the project (filtered by
 * `SPOCK_DTD_DEVICE_MODEL` when set, e.g. `sdk_gphone64_arm64`, and by the project's pubspec
 * name). With `SPOCK_DTD_APP_ID=<applicationId>` and `SPOCK_DTD_SERIAL=<adb serial>` it also
 * checks identity (plan H2): it runs `pidof <applicationId>` and reads each pid's start from
 * `/proc` with `adb -s <serial> shell`, prints each candidate's VM pid and start, and what that
 * confirms (or that several pass). With `SPOCK_DTD_CONNECT=1` it connects a
 * [FlutterSession] to the confirmed candidate — the first one when no identity check ran —
 * prints its state, UI isolate and build mode, and closes it. Nothing printed carries a DTD
 * secret or a VM Service token.
 */
@EnabledIfEnvironmentVariable(named = DtdLiveCheck.PROJECT_ENV, matches = ".+")
class DtdLiveCheck {

    @Test
    fun `finds the project's running app through the Dart Tooling Daemon`() {
        val project = System.getenv(PROJECT_ENV)
        val model = System.getenv(MODEL_ENV)?.takeIf { it.isNotBlank() }
        val registry = DtdRegistry()
        println("registry dirs: ${DtdRegistry.defaultDirs()}")
        registry.entries().forEach { println("  live: $it (Dart ${it.dartVersion})") }

        println("project $project, pubspec name ${Pubspec.name(project)}, device model ${model ?: "any"}")
        val candidates = DtdDiscovery(project, model, registry = registry).candidates()
        candidates.forEach {
            println("  candidate: $it [${it.workspaceMatch}, ${it.workspace.distance} apart, ${it.service.name}]")
        }
        assertTrue(candidates.isNotEmpty(), "no candidate: is flutter run running in $project?")

        val chosen = verify(candidates) ?: candidates.first()
        if (System.getenv(CONNECT_ENV) != "1") return
        val session = FlutterSession()
        try {
            session.connect(chosen)
            println("state: ${session.state}")
            println("UI isolate ${session.uiIsolateId}, build ${session.buildMode}, read-only ${session.readOnly}")
            println("structuredErrors ${session.structuredErrorsEnabled}")
            assertTrue(session.state !is SessionState.Disconnected, "state ${session.state}")
        } finally {
            session.close()
        }
        println("closed: ${session.state}")
    }

    /** The candidate running as the app's pid on the device, when both are named; null otherwise. */
    private fun verify(candidates: List<DtdCandidate>): DtdCandidate? {
        val appId = System.getenv(APP_ID_ENV)?.takeIf { it.isNotBlank() } ?: return null
        val serial = System.getenv(SERIAL_ENV)?.takeIf { it.isNotBlank() } ?: return null
        val pids = adbShell(serial, "pidof ${ShellQuote.quote(appId)}")
            .trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }.toSet()
        println("pidof $appId on $serial: ${pids.ifEmpty { "not running" }}")
        val starts = pids.associateWith { pid -> ProcessTiming.parse(adbShell(serial, ProcessTiming.command(pid))) }
        starts.forEach { (pid, timing) -> println("  process $pid on the device: $timing") }
        candidates.forEach { candidate ->
            val vm = runCatching { DtdAppIdentity.readVmProcess(candidate.open()) }
            println("  VM of $candidate: ${vm.getOrElse { "unreadable (${it.message})" }}")
        }
        val result = DtdAppIdentity().confirm(candidates, starts.mapValues { it.value?.startEpochMs })
        println("verified: $result")
        val found = pids.isEmpty() || result !is DtdAppIdentity.Result.None
        assertTrue(found, "$appId runs on $serial, but no DTD app is it")
        return (result as? DtdAppIdentity.Result.Confirmed)?.candidate
    }

    /** A plain argv on the host; the device's shell gets [command] as written. */
    private fun adbShell(serial: String, command: String): String {
        val process = ProcessBuilder("adb", "-s", serial, "shell", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(ADB_SECONDS, TimeUnit.SECONDS)
        return output
    }

    companion object {
        const val PROJECT_ENV = "SPOCK_DTD_PROJECT"
        private const val MODEL_ENV = "SPOCK_DTD_DEVICE_MODEL"
        private const val CONNECT_ENV = "SPOCK_DTD_CONNECT"
        private const val APP_ID_ENV = "SPOCK_DTD_APP_ID"
        private const val SERIAL_ENV = "SPOCK_DTD_SERIAL"
        private const val ADB_SECONDS = 10L
        private val WHITESPACE = Regex("""\s+""")
    }
}
