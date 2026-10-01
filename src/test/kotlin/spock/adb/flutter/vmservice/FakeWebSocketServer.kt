package spock.adb.flutter.vmservice

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * A minimal RFC 6455 server for tests. The JDK has a WebSocket client and no server, and the
 * plugin adds no dependency for one, so this speaks just enough of the protocol: the HTTP
 * upgrade with `Sec-WebSocket-Accept`, masked client frames, text messages in one frame or
 * several, ping and close. One connection at a time; [onText] runs on the server's reader
 * thread, so it must not block.
 */
class FakeWebSocketServer(private val onText: (String) -> Unit) : AutoCloseable {

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: OutputStream? = null

    val port: Int get() = server.localPort

    /** The request path of every handshake: the auth code is in it. */
    val handshakePaths = CopyOnWriteArrayList<String>()

    /** False if a client frame ever arrived unmasked, which RFC 6455 forbids. */
    @Volatile
    var clientFramesMasked = true
        private set

    val closeFrameReceived = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "fake-websocket-server") { acceptLoop() }
    }

    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8), fin = true)

    /** [text] as [parts] frames: a text frame then continuation frames, FIN only on the last. */
    fun sendFragmented(text: String, parts: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val size = (bytes.size + parts - 1) / parts
        val chunks = bytes.toList().chunked(size).map { it.toByteArray() }
        synchronized(this) {
            chunks.forEachIndexed { index, chunk ->
                val opcode = if (index == 0) OP_TEXT else OP_CONTINUATION
                sendFrame(opcode, chunk, fin = index == chunks.lastIndex)
            }
        }
    }

    /** A clean close from the server's side. */
    fun sendClose(code: Int = NORMAL_CLOSURE) {
        sendFrame(OP_CLOSE, byteArrayOf((code shr BYTE_BITS).toByte(), code.toByte()), fin = true)
    }

    /** The connection vanishes with no close frame, as when the app is killed. */
    fun drop() {
        socket?.close()
    }

    override fun close() {
        server.close()
        socket?.close()
    }

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray, fin: Boolean) {
        val out = output ?: error("no client connected")
        val header = ByteArrayOutputStream()
        header.write((if (fin) FIN else 0) or opcode)
        when {
            payload.size < LENGTH_16 -> header.write(payload.size)
            payload.size <= MAX_16 -> {
                header.write(LENGTH_16)
                header.write(payload.size shr BYTE_BITS)
                header.write(payload.size and BYTE_MASK)
            }
            else -> {
                header.write(LENGTH_64)
                for (shift in LONG_SHIFTS) header.write(((payload.size.toLong() shr shift) and 0xFFL).toInt())
            }
        }
        out.write(header.toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val accepted = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            socket = accepted
            try {
                serve(accepted)
            } catch (_: IOException) {
                // The client went away; wait for the next one.
            } finally {
                accepted.close()
            }
        }
    }

    private fun serve(connection: Socket) {
        val input = DataInputStream(BufferedInputStream(connection.getInputStream()))
        val requestLines = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
        handshakePaths += requestLines.first().split(' ')[1]
        val key = requestLines.drop(1)
            .map { it.split(':', limit = 2) }
            .first { it[0].trim().equals("Sec-WebSocket-Key", ignoreCase = true) }[1].trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII)),
        )
        val out = connection.getOutputStream()
        // Set before the 101 goes out, and under the frame lock: the client may send its first
        // request, and a test push a reply, the moment the handshake is answered.
        synchronized(this) {
            output = out
            out.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            out.flush()
        }
        readFrames(input)
    }

    private fun readFrames(input: DataInputStream) {
        val message = ByteArrayOutputStream()
        while (true) {
            val first = input.read()
            if (first < 0) return
            val payload = readPayload(input)
            when (first and OPCODE_MASK) {
                OP_TEXT, OP_CONTINUATION -> {
                    message.write(payload)
                    if ((first and FIN) != 0) {
                        onText(message.toString(Charsets.UTF_8))
                        message.reset()
                    }
                }
                OP_CLOSE -> {
                    closeFrameReceived.countDown()
                    sendFrame(OP_CLOSE, payload.copyOf(minOf(payload.size, 2)), fin = true)
                    return
                }
                OP_PING -> sendFrame(OP_PONG, payload, fin = true)
            }
        }
    }

    /** The rest of a frame after its first byte: length, mask, and the payload unmasked. */
    private fun readPayload(input: DataInputStream): ByteArray {
        val second = input.readUnsignedByte()
        val masked = (second and FIN) != 0
        if (!masked) clientFramesMasked = false
        val length = when (val short = second and LENGTH_MASK) {
            LENGTH_16 -> input.readUnsignedShort().toLong()
            LENGTH_64 -> input.readLong()
            else -> short.toLong()
        }
        val mask = ByteArray(MASK_BYTES).also { if (masked) input.readFully(it) }
        val payload = ByteArray(length.toInt()).also { input.readFully(it) }
        if (masked) unmask(payload, mask)
        return payload
    }

    private fun unmask(payload: ByteArray, mask: ByteArray) {
        payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % MASK_BYTES].toInt()).toByte() }
    }

    private fun readLine(input: DataInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = try {
                input.readUnsignedByte()
            } catch (_: EOFException) {
                return null
            }
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val BACKLOG = 4
        const val FIN = 0x80
        const val OPCODE_MASK = 0x0F
        const val LENGTH_MASK = 0x7F
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val LENGTH_16 = 126
        const val LENGTH_64 = 127
        const val MAX_16 = 0xFFFF
        const val MASK_BYTES = 4
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFF
        const val NORMAL_CLOSURE = 1000
        val LONG_SHIFTS = listOf(56, 48, 40, 32, 24, 16, 8, 0)
    }
}
