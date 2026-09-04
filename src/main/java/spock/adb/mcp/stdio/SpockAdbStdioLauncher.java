package spock.adb.mcp.stdio;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The process an MCP client spawns for the stdio transport.
 *
 * It is a byte relay and nothing else. It does not parse JSON-RPC, does not know what a tool
 * is, and has no opinion about MCP: it copies bytes between its own stdin/stdout and the
 * running IDE's stdio bridge, which serves the session with the same {@code McpProtocol} and
 * the same {@code ToolRegistry} the HTTP transport uses. Keeping it ignorant is the point —
 * a relay that understood the protocol would be a second implementation of it, and the two
 * would drift.
 *
 * <p>Written in Java, with no dependencies, so it runs on nothing but the JDK the IDE already
 * ships: the plugin does not bundle the Kotlin standard library (it uses the one inside the
 * IDE), so a Kotlin launcher could not be started from a plain {@code java -cp} command.
 *
 * <p><b>Nothing may be printed to stdout.</b> Stdout is the protocol stream; one stray line
 * corrupts the session. {@link System#out} is therefore redirected to stderr on the first
 * line of {@link #main}, and the real stdout is held privately by the relay.
 *
 * <p>Usage: {@code java -cp <plugin.jar> spock.adb.mcp.stdio.SpockAdbStdioLauncher <descriptor>}
 *
 * <p>The descriptor path is required rather than defaulted. The IDE writes it under its own
 * configuration directory, which differs per IDE, per version and per platform, so there is no
 * single location a default could name that would be right — and a default naming the wrong
 * place is worse than none. Copy the ready-made configuration from
 * <em>Tools &gt; SpockAdb &gt; Copy MCP Client Configuration (stdio)</em>, which fills in the
 * real path for the IDE that generated it.
 */
public final class SpockAdbStdioLauncher {

    private static final int EXIT_NO_ENDPOINT = 2;
    private static final int EXIT_CONNECTION_LOST = 3;
    private static final int BUFFER_BYTES = 8192;

    private SpockAdbStdioLauncher() {
    }

    public static void main(String[] args) {
        // Claim the real stdout before anything can print to it, then point System.out at
        // stderr so that a future println here, or in any library, cannot corrupt the stream.
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));

        if (args.length < 1) {
            fail("Spock ADB: usage: java -cp <plugin jar> "
                    + SpockAdbStdioLauncher.class.getName() + " <descriptor>."
                    + " Copy the ready-made configuration from Tools > SpockAdb >"
                    + " Copy MCP Client Configuration (stdio), which fills in the path.");
            System.exit(EXIT_NO_ENDPOINT);
            return;
        }
        Path descriptorFile = Path.of(args[0]);

        Properties endpoint;
        try {
            endpoint = readDescriptor(descriptorFile);
        } catch (IOException e) {
            fail("Spock ADB: no MCP endpoint at " + descriptorFile + "."
                    + " Start the server in the IDE: Tools > SpockAdb >"
                    + " Spock: Start MCP Server for AI Agents.");
            System.exit(EXIT_NO_ENDPOINT);
            return;
        }

        try (SocketChannel connection = connect(endpoint)) {
            handshake(connection, endpoint.getProperty("token", ""));
            relay(connection, protocolOut);
        } catch (IOException e) {
            fail("Spock ADB: lost the connection to the IDE (" + e.getMessage() + ")."
                    + " The MCP server may have been stopped.");
            System.exit(EXIT_CONNECTION_LOST);
        }
    }

    private static Properties readDescriptor(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private static SocketChannel connect(Properties endpoint) throws IOException {
        String transport = endpoint.getProperty("transport", "unix");
        if ("unix".equals(transport)) {
            Path socket = Path.of(endpoint.getProperty("socket", ""));
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            return channel;
        }
        int port = Integer.parseInt(endpoint.getProperty("port", "0"));
        return SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    /** One line, before the session: the bridge closes the connection if it does not match. */
    private static void handshake(SocketChannel connection, String token) throws IOException {
        OutputStream out = Channels.newOutputStream(connection);
        out.write((token + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Copies bytes in both directions until either end closes.
     *
     * The IDE-to-client direction runs on this thread so the process stays alive exactly as
     * long as the session does; the client-to-IDE direction runs on a daemon thread, which
     * lets the process exit the moment the IDE closes the connection rather than waiting on
     * a stdin read that will never return.
     */
    private static void relay(SocketChannel connection, OutputStream protocolOut) throws IOException {
        InputStream fromIde = Channels.newInputStream(connection);
        OutputStream toIde = Channels.newOutputStream(connection);

        Thread pump = new Thread(() -> {
            try {
                copy(System.in, toIde);
            } catch (IOException ignored) {
                // The client exited and closed the pipe. That is how a session ends.
            } finally {
                // Half-close: tell the IDE the client has gone without tearing down the read
                // side. Closing the whole channel here aborts the main thread's read mid-call,
                // which surfaces as AsynchronousCloseException and gets reported as a lost
                // connection — on the ordinary path where the client just closed stdin. After
                // the half-close the IDE sees end of stream, ends its session and closes its
                // end, and the read below finishes cleanly.
                shutdownOutputQuietly(connection);
            }
        }, "spock-adb-stdio-in");
        pump.setDaemon(true);
        pump.start();

        copy(fromIde, protocolOut);
    }

    /**
     * Flushes after every read rather than at the end.
     *
     * A JSON-RPC message the client is waiting on must not sit in an 8 KB buffer until the
     * next one arrives, which is exactly what a plain transferTo would do.
     */
    private static void copy(InputStream source, OutputStream destination) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = source.read(buffer)) != -1) {
            destination.write(buffer, 0, read);
            destination.flush();
        }
    }

    private static void shutdownOutputQuietly(SocketChannel connection) {
        try {
            connection.shutdownOutput();
        } catch (IOException ignored) {
            // The connection is already gone, which is the state this was aiming for.
        }
    }

    /** Diagnostics go to stderr, which MCP clients surface in their logs. */
    private static void fail(String message) {
        System.err.println(message);
    }
}
