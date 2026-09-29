package spock.adb.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spock.adb.HIDDEN_ON_FIRST_RUN
import spock.adb.SpockAction

class DeviceReadTest {

    @Test
    fun `a fresh install does not read the hidden developer options`() {
        val wanted = DeviceRead.wanted { it !in HIDDEN_ON_FIRST_RUN }

        assertEquals(setOf(DeviceRead.PROXY, DeviceRead.NETWORK), wanted)
    }

    @Test
    fun `each read follows its own switch`() {
        assertEquals(setOf(DeviceRead.DEVELOPER_OPTIONS), DeviceRead.wanted { it == SpockAction.DEVELOPER_OPTIONS })
        assertEquals(emptySet<DeviceRead>(), DeviceRead.wanted { false })
    }
}
