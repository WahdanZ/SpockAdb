package spock.adb.flutter.vmservice

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.intellij.openapi.diagnostic.Logger
import spock.adb.ShellOutputReceiver
import spock.adb.logcat.LogcatParser
import spock.adb.pidsOf
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import com.android.ddmlib.TimeoutException as AdbTimeoutException

/** Reads the engine's VM Service announcement out of `logcat -v threadtime` output. */
object LogcatVmServiceParser {

    /** The address [pid] announced; its port is the device's, so it needs an `adb forward`. */
    data class Announcement(val pid: Int, val uri: VmServiceUri)

    /**
     * `The Dart VM service is listening on http://…` since Flutter 3.10, `Observatory listening
     * on http://…` before it. Matched case-insensitively: the capital S has come and gone.
     */
    private val ANNOUNCEMENT = Regex("""(?i)(?:Dart VM service is listening on|Observatory listening on)\s+(\S+)""")

    /**
     * The last announcement made by any of [pids] in [logcat], or null. The last one wins
     * because a restarted engine (a new process, or the same pid after a hot restart of the
     * activity) announces again, and only the newest address is live.
     */
    fun latest(logcat: String, pids: Set<Int>): Announcement? {
        var latest: Announcement? = null
        logcat.lineSequence().forEach { line ->
            val entry = LogcatParser.parse(line.trimEnd()) ?: return@forEach
            if (entry.pid !in pids) return@forEach
            val address = ANNOUNCEMENT.find(entry.message)?.groupValues?.get(1) ?: return@forEach
            VmServiceUri.parseOrNull(address)?.let { latest = Announcement(entry.pid, it) }
        }
        return latest
    }
}

/**
 * Finds the VM Service of [packageName] from what the engine logged at startup.
 *
 * The address is the VM's own, not DDS's, so the candidate is [VmServiceCandidate.direct]: a
 * read-only last resort [FR1]. Once DDS owns the VM (`flutter run` attached), the VM answers a
 * connection there with a redirect to DDS, which the session follows — and then it is a DDS
 * connection. If the log buffer has rotated since the app started, nothing is found. Opening the
 * candidate forwards a free host port to the device port; releasing it removes the forward.
 */
class LogcatDiscovery(private val device: IDevice, private val packageName: String) : VmServiceDiscovery {

    /** @throws VmServiceException when adb cannot read the app's pids or logcat. */
    override fun discover(): List<VmServiceCandidate> {
        val pids = adb("list the processes of $packageName") {
            device.pidsOf(packageName, SHELL_SECONDS).mapNotNull { it.toIntOrNull() }.toSet()
        }
        if (pids.isEmpty()) return emptyList()
        val receiver = ShellOutputReceiver()
        adb("read logcat") { device.executeShellCommand(LOGCAT_COMMAND, receiver, SHELL_SECONDS, TimeUnit.SECONDS) }
        val found = LogcatVmServiceParser.latest(receiver.toString(), pids) ?: return emptyList()
        return listOf(ForwardedCandidate(device, found.uri))
    }

    private class ForwardedCandidate(private val device: IDevice, private val deviceUri: VmServiceUri) :
        VmServiceCandidate(VmServiceSource.LOGCAT, ddsLikely = false) {

        private var localPort: Int? = null

        override val description: String
            get() = "port ${deviceUri.port} on ${device.serialNumber} (from logcat, direct VM Service)"

        @Synchronized
        override fun open(): VmServiceUri {
            val port = localPort ?: forward()
            return deviceUri.withPort(port)
        }

        @Synchronized
        override fun release() {
            val port = localPort ?: return
            localPort = null
            try {
                device.removeForward(port)
            } catch (e: IOException) {
                log.warn("Could not remove the VM Service forward on port $port", e)
            } catch (e: AdbCommandRejectedException) {
                log.warn("Could not remove the VM Service forward on port $port", e)
            } catch (e: AdbTimeoutException) {
                log.warn("Could not remove the VM Service forward on port $port", e)
            }
        }

        private fun forward(): Int = adb("forward device port ${deviceUri.port}") {
            val port = freeLocalPort()
            device.createForward(port, deviceUri.port)
            localPort = port
            port
        }

        /** A port nothing on the host listens on now; adb takes it a moment later. */
        private fun freeLocalPort(): Int =
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    }

    private companion object {
        const val SHELL_SECONDS = 10L

        /**
         * Runs an adb step, turning ddmlib's failures into a [VmServiceException] saying [what]
         * failed, so a caller handles one exception type for discovery and connection alike.
         */
        inline fun <T> adb(what: String, step: () -> T): T {
            val problem: Exception = try {
                return step()
            } catch (e: IOException) {
                e
            } catch (e: AdbCommandRejectedException) {
                e
            } catch (e: ShellCommandUnresponsiveException) {
                e
            } catch (e: AdbTimeoutException) {
                e
            }
            throw VmServiceException("Could not $what: ${problem.message ?: problem.javaClass.simpleName}", problem)
        }

        /** Only the `flutter` tag: the engine announces there, and the whole buffer can be megabytes. */
        const val LOGCAT_COMMAND = "logcat -d -v threadtime -s flutter"

        val log = Logger.getInstance(LogcatDiscovery::class.java)
    }
}
