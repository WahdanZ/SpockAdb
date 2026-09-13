package spock.adb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The HTTP proxy status line applies a device read only if no newer read has been started,
 * so a slow read from before a Set or Clear cannot overwrite what the device holds now.
 */
class LatestRequestTest {

    @Test
    fun `an older result arriving after a newer one is dropped`() {
        val requests = LatestRequest()
        val older = requests.begin()
        val newer = requests.begin()

        // The newer read finishes first and is applied; the older one lands afterwards.
        assertTrue(requests.isLatest(newer))
        assertFalse(requests.isLatest(older))
    }

    @Test
    fun `a request that reads nothing still retires the one in flight`() {
        // With no device selected the row starts no read, but a pending one must not
        // overwrite the "unknown" it shows instead.
        val requests = LatestRequest()
        val pending = requests.begin()
        requests.begin()

        assertFalse(requests.isLatest(pending))
    }

    @Test
    fun `the only request is the latest`() {
        val requests = LatestRequest()

        assertTrue(requests.isLatest(requests.begin()))
    }
}
