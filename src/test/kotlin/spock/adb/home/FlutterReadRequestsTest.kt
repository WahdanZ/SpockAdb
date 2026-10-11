package spock.adb.home

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.navigation.FlutterNavigationState

/**
 * Which Flutter route reads still land on Home: a back stack check survives the refreshes that run
 * while its heap walk does, and answers about another session or isolate never land.
 */
class FlutterReadRequestsTest {

    private val session = Any()
    private val requests = FlutterReadRequests()

    @Test
    fun `a refresh during the check does not retire it`() {
        val check = requests.check(session, "isolates/1")

        // The settle timer, a Flutter.Navigation, HomePanel's re-read after an action: each a keyed read.
        requests.retireKeyed()
        val keyed = requests.keyed(session, "isolates/1")
        requests.keyed(session, "isolates/1")

        assertTrue(requests.isLatest(check))
        assertTrue(requests.current(check, session, "isolates/1"))
        assertFalse(requests.isLatest(keyed))
    }

    @Test
    fun `a newer check retires the older one`() {
        val first = requests.check(session, "isolates/1")
        val second = requests.check(session, "isolates/1")

        assertFalse(requests.current(first, session, "isolates/1"))
        assertTrue(requests.current(second, session, "isolates/1"))
    }

    @Test
    fun `another session or isolate retires a check`() {
        val check = requests.check(session, "isolates/1")

        assertFalse(requests.current(check, Any(), "isolates/1"))
        assertFalse(requests.current(check, null, null))
        // A hot restart: same session, a new UI isolate.
        assertFalse(requests.current(check, session, "isolates/2"))
    }

    @Test
    fun `a navigation retires the keyed read in flight, not the check`() {
        val keyed = requests.keyed(session, "isolates/1")
        val check = requests.check(session, "isolates/1")

        requests.retireKeyed()

        assertFalse(requests.current(keyed, session, "isolates/1"))
        assertTrue(requests.current(check, session, "isolates/1"))
    }

    @Test
    fun `the check's answer goes on Home, and a keyed read started before it does not overwrite it`() {
        val older = requests.keyed(session, "isolates/1")
        val check = requests.check(session, "isolates/1")

        assertTrue(requests.showOnHome(check, session, "isolates/1"))
        assertFalse(requests.showOnHome(older, session, "isolates/1"))
    }

    @Test
    fun `a keyed read started after the check may overwrite Home afterwards`() {
        val check = requests.check(session, "isolates/1")
        val later = requests.keyed(session, "isolates/1")

        assertTrue(requests.showOnHome(check, session, "isolates/1"))
        assertTrue(requests.showOnHome(later, session, "isolates/1"))
    }

    @Test
    fun `a check landing after a newer keyed read still answers, but leaves Home on the newer read`() {
        val check = requests.check(session, "isolates/1")
        val later = requests.keyed(session, "isolates/1")

        assertTrue(requests.showOnHome(later, session, "isolates/1"))
        assertTrue(requests.current(check, session, "isolates/1"))
        assertFalse(requests.showOnHome(check, session, "isolates/1"))
    }

    @Test
    fun `a transition is read again a few times per burst, and a new burst starts over`() {
        val transient = FlutterNavigationState.unavailable("Navigation in progress", transient = true)
        val settled = FlutterNavigationState.unavailable("No navigator")

        assertFalse(requests.retryAfter(settled))
        repeat(FlutterReadRequests.MAX_RETRIES) { assertTrue(requests.retryAfter(transient)) }
        assertFalse(requests.retryAfter(transient))

        requests.newBurst()
        assertTrue(requests.retryAfter(transient))
    }
}
