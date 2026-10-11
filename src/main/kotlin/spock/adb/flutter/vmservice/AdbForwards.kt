package spock.adb.flutter.vmservice

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The host's `adb forward --list`, read from the adb server directly (`host:list-forward`):
 * ddmlib can create and remove a forward but not list them. Asks adb only — never the device's
 * VM, so it is safe while a Flutter tool is attaching.
 */
object AdbForwards {

    /** One forward: [local] on the host (`tcp:56789`) to [remote] on the device [serial] (`tcp:38551`). */
    data class Forward(val serial: String, val local: String, val remote: String)

    private const val REQUEST = "host:list-forward"
    private const val TIMEOUT_MS = 2_000
    private const val LENGTH_DIGITS = 4
    private const val HEX = 16

    /** Serial, local, remote. */
    private const val FIELDS = 3
    private const val DEFAULT_ADB_PORT = 5037

    /**
     * Whether a forward on [serial] reaches device port [devicePort] that Spock did not make — a
     * Flutter tool's (`flutter run`, `flutter attach`, an IDE). Null when adb cannot say.
     */
    fun foreignTo(serial: String, devicePort: Int): Boolean? = list()?.let { foreign(it, serial, devicePort) }

    /** [foreignTo], given the list. */
    fun foreign(forwards: List<Forward>, serial: String, devicePort: Int): Boolean = forwards.any {
        it.serial == serial && it.remote == "tcp:$devicePort" && !SpockForwards.owns(serial, it.local)
    }

    /**
     * Every forward the adb server holds; null when it cannot be asked. Blocking, briefly. The
     * server is found as ddmlib finds it — on this host, at `ANDROID_ADB_SERVER_PORT` or 5037 —
     * rather than through ddmlib's deprecated `getSocketAddress`; one elsewhere is not found, and
     * the answer is then "cannot say", as before this existed.
     */
    fun list(server: InetSocketAddress = defaultServer()): List<Forward>? {
        return try {
            Socket().use { socket ->
                socket.connect(server, TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val request = "%04x%s".format(REQUEST.length, REQUEST).toByteArray(StandardCharsets.US_ASCII)
                socket.getOutputStream().apply {
                    write(request)
                    flush()
                }
                val input = DataInputStream(socket.getInputStream())
                if (read(input, LENGTH_DIGITS) != "OKAY") return null
                val length = read(input, LENGTH_DIGITS).toIntOrNull(HEX) ?: return null
                parse(read(input, length))
            }
        } catch (_: IOException) {
            null
        }
    }

    /** `emulator-5554 tcp:56789 tcp:38551` per line; anything else is skipped. */
    fun parse(output: String): List<Forward> = output.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("""\s+"""))
        val (serial, local, remote) = parts.takeIf { it.size == FIELDS } ?: return@mapNotNull null
        Forward(serial, local, remote)
    }

    private fun defaultServer(): InetSocketAddress {
        val port = System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_ADB_PORT
        return InetSocketAddress(InetAddress.getLoopbackAddress(), port)
    }

    private fun read(input: DataInputStream, length: Int): String {
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

/** The forwards Spock made for VM Services, so another tool's forward to the same port is told apart. */
object SpockForwards {
    private val made = mutableSetOf<String>()

    fun made(serial: String, localPort: Int) = synchronized(made) { made += key(serial, "tcp:$localPort") }

    fun released(serial: String, localPort: Int) = synchronized(made) { made -= key(serial, "tcp:$localPort") }

    fun owns(serial: String, local: String): Boolean = synchronized(made) { key(serial, local) in made }

    private fun key(serial: String, local: String) = "$serial $local"
}
