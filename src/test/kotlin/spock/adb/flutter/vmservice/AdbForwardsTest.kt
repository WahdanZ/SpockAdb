package spock.adb.flutter.vmservice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class AdbForwardsTest {

    private val listing = """
        emulator-5554 tcp:51234 tcp:38551
        emulator-5554 tcp:51999 tcp:38551
        emulator-5556 tcp:52000 tcp:38551
        emulator-5554 tcp:8080 tcp:9090
    """.trimIndent()

    @AfterEach
    fun tearDown() = SpockForwards.released("emulator-5554", 51999)

    @Test
    fun `adb's forward list is read line by line`() {
        val forwards = AdbForwards.parse(listing + "\ngarbage\n")

        assertEquals(4, forwards.size)
        assertEquals(AdbForwards.Forward("emulator-5554", "tcp:51234", "tcp:38551"), forwards.first())
    }

    @Test
    fun `a forward to the VM's port that Spock did not make is a Flutter tool's`() {
        val forwards = AdbForwards.parse(listing)
        SpockForwards.made("emulator-5554", 51999)

        assertTrue(AdbForwards.foreign(forwards, "emulator-5554", 38551))
        assertFalse(AdbForwards.foreign(forwards, "emulator-5554", 40000))
        assertFalse(AdbForwards.foreign(forwards, "emulator-5558", 38551))
    }

    @Test
    fun `Spock's own forward is not a tool's`() {
        val forwards = AdbForwards.parse("emulator-5554 tcp:51999 tcp:38551")
        SpockForwards.made("emulator-5554", 51999)

        assertFalse(AdbForwards.foreign(forwards, "emulator-5554", 38551))
    }

    @Test
    fun `the adb server is asked with host list-forward`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var asked = ""
            val adb = thread {
                server.accept().use { client ->
                    val request = ByteArray(4 + "host:list-forward".length)
                    client.getInputStream().read(request)
                    asked = String(request)
                    val body = listing.toByteArray()
                    client.getOutputStream().write("OKAY%04x".format(body.size).toByteArray() + body)
                }
            }
            val forwards = AdbForwards.list(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
            adb.join()

            assertEquals("0011host:list-forward", asked)
            assertEquals(4, forwards?.size)
        }
    }

    @Test
    fun `no adb server is cannot say`() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        assertNull(AdbForwards.list(InetSocketAddress(InetAddress.getLoopbackAddress(), closed)))
    }
}
