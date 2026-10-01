package spock.adb.flutter.dtd

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.Pubspec
import spock.adb.flutter.SessionState

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
 * name). With `SPOCK_DTD_CONNECT=1` it also connects a [FlutterSession] to the first candidate,
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
        candidates.forEach { println("  candidate: $it [${it.workspaceMatch}, ${it.service.name}]") }
        assertTrue(candidates.isNotEmpty(), "no candidate: is flutter run running in $project?")

        if (System.getenv(CONNECT_ENV) != "1") return
        val session = FlutterSession()
        try {
            session.connect(candidates.first())
            println("state: ${session.state}")
            println("UI isolate ${session.uiIsolateId}, build ${session.buildMode}, read-only ${session.readOnly}")
            println("structuredErrors ${session.structuredErrorsEnabled}")
            assertTrue(session.state !is SessionState.Disconnected, "state ${session.state}")
        } finally {
            session.close()
        }
        println("closed: ${session.state}")
    }

    companion object {
        const val PROJECT_ENV = "SPOCK_DTD_PROJECT"
        private const val MODEL_ENV = "SPOCK_DTD_DEVICE_MODEL"
        private const val CONNECT_ENV = "SPOCK_DTD_CONNECT"
    }
}
