package spock.adb.flutter.navigation

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.home.FlutterRouteSource
import spock.adb.home.FlutterRouteText

/**
 * Not a unit test: the #180 device check of the navigator reader, against a running app, through
 * the same session and budgets Home and App back stack use. Skipped unless `SPOCK_VM_SERVICE_URI`
 * holds the address `flutter run` printed:
 *
 * ```
 * SPOCK_VM_SERVICE_URI='http://127.0.0.1:<port>/<token>=/' \
 *   ./gradlew test --tests spock.adb.flutter.navigation.NavigatorLiveCheck --rerun -i
 * ```
 *
 * It prints Home's read and the check's — the tree, current, the popup's rows and footer — with how
 * many calls and how long each took. Nothing printed carries the token.
 */
@EnabledIfEnvironmentVariable(named = NavigatorLiveCheck.URI_ENV, matches = ".+")
class NavigatorLiveCheck {

    @Test
    fun `reads the running app's navigators as Home and App back stack do`() {
        val session = FlutterSession()
        session.connect(PastedUriDiscovery(System.getenv(URI_ENV)).discover().single(), false)
        try {
            val isolate = session.uiIsolateId ?: error("no UI isolate")
            val memory = NonNavigatorElements()
            repeat(2) { pass ->
                report("home read ${pass + 1}", isolate, session, FlutterRouteSource.HOME_BUDGET_MS, memory) {
                    it.readKeyed()
                }
            }
            report("check", isolate, session, FlutterRouteSource.CHECK_BUDGET_MS, memory) { it.readChecked() }
        } finally {
            session.close()
        }
    }

    private fun report(
        label: String,
        isolate: String,
        session: FlutterSession,
        budget: Long,
        memory: NonNavigatorElements,
        read: (NavigatorReader) -> FlutterNavigationState,
    ) {
        val counting = Counting(SessionObjects(session, isolate, budget))
        val started = System.nanoTime()
        val state = read(NavigatorReader(counting, notNavigators = memory))
        val ms = (System.nanoTime() - started) / 1_000_000
        println("== $label: ${state.confidence}, ${counting.calls} calls, $ms ms (budget $budget ms)")
        state.unavailable?.let { println("   unavailable: $it (transient ${state.transient})") }
        println("   home: ${FlutterRouteText.home(state)}")
        println("   tooltip: ${FlutterRouteText.homeTooltip(state)}")
        FlutterRouteText.rows(state).forEach { row ->
            val text = if (row.header) "[${row.text}]" else row.text
            println("   ${"  ".repeat(row.depth)}$text  ${row.mark.orEmpty()}")
        }
        println("   footer: ${FlutterRouteText.footer(state)}")
    }

    private class Counting(private val inner: VmObjects) : VmObjects {
        var calls = 0

        override fun isolate(): JsonObject = inner.isolate().also { calls++ }

        override fun get(objectId: String): JsonObject = inner.get(objectId).also { calls++ }

        override fun instances(classId: String, limit: Int): JsonObject =
            inner.instances(classId, limit).also { calls++ }
    }

    companion object {
        const val URI_ENV = "SPOCK_VM_SERVICE_URI"
    }
}
