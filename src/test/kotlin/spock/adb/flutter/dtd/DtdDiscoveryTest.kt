package spock.adb.flutter.dtd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import spock.adb.flutter.Pubspec
import spock.adb.flutter.vmservice.VmServiceRpcException
import spock.adb.flutter.vmservice.VmServiceSource
import java.nio.file.Files
import java.nio.file.Path

class DtdDiscoveryTest {

    @TempDir
    lateinit var temp: Path

    private val registryDir: Path by lazy { Files.createDirectories(temp.resolve("registry")) }
    private val repo: Path by lazy { Files.createDirectories(temp.resolve("repo")) }
    private val app: Path by lazy { Files.createDirectories(repo.resolve("sample/flutter_app")) }

    /** Daemon port → what it answers; a port missing here fails like a dead daemon. */
    private val answers = mutableMapOf<Int, List<DtdVmService>>()
    private val asked = mutableListOf<Int>()

    private var nextPid = 100L

    private fun daemon(port: Int, root: Path?, vararg apps: Pair<Int, String>, epoch: Long = 1L) {
        val pid = nextPid++
        val rootField = root?.let { ""","workspaceRoot":"$it"""" }.orEmpty()
        Files.writeString(
            registryDir.resolve("$pid"),
            """{"wsUri":"ws://127.0.0.1:$port/Secret$port=","pid":$pid,"epoch":$epoch$rootField}""",
        )
        val listed = apps.map { (vmPort, name) -> "ws://127.0.0.1:$vmPort/Tok$vmPort=/ws" to name }
        answers[port] = DtdVmService.parse(FakeDtd.response(*listed.toTypedArray()))
    }

    private fun name(device: String, pkg: String) = "Kind: Flutter - Device: $device - Package: $pkg"

    private val emulatorApp = name("sdk gphone64 arm64", "dtd_spike")

    private fun discovery(project: Path, model: String? = "sdk_gphone64_arm64", pkg: String? = "dtd_spike") =
        DtdDiscovery(
            projectPath = project.toString(),
            deviceModel = model,
            packageName = pkg,
            registry = DtdRegistry(listOf(registryDir)) { true },
            query = { uri ->
                asked += uri.port
                answers[uri.port] ?: throw DtdException("Could not connect to ${uri.redacted()}")
            },
        )

    private fun ports(found: List<DtdCandidate>) = found.map { it.service.uri.port }

    @Test
    fun `the recorded app on the emulator is found, as a DDS candidate from DTD`() {
        daemon(5001, app, 6001 to emulatorApp)
        val found = discovery(app).candidates().single()
        assertEquals(VmServiceSource.DTD, found.source)
        assertTrue(found.ddsLikely)
        assertFalse(found.direct)
        assertEquals(WorkspaceMatch.EXACT, found.workspaceMatch)
        assertEquals("ws://127.0.0.1:6001/Tok6001=/ws", found.open().webSocketUri.toString())
    }

    @Test
    fun `only daemons for this project are asked`() {
        daemon(5001, app, 6001 to emulatorApp)
        daemon(5002, Files.createDirectories(temp.resolve("elsewhere")), 6002 to emulatorApp)
        daemon(5003, null, 6003 to emulatorApp)
        assertEquals(listOf(6001), ports(discovery(app).candidates()))
        assertEquals(listOf(5001), asked)
    }

    @Test
    fun `an app in a subdirectory of the opened repository is found, and so is a daemon for a parent`() {
        daemon(5001, app, 6001 to emulatorApp)
        assertEquals(WorkspaceMatch.INSIDE_PROJECT, discovery(repo).candidates().single().workspaceMatch)
        val fromLib = discovery(app.resolve("lib")).candidates().single()
        assertEquals(WorkspaceMatch.ENCLOSES_PROJECT, fromLib.workspaceMatch)
    }

    @Test
    fun `apps on another device or of another package are dropped`() {
        daemon(
            5001,
            app,
            6001 to emulatorApp,
            6002 to name("Pixel 8", "dtd_spike"),
            6003 to name("sdk gphone64 arm64", "other_app"),
        )
        assertEquals(listOf(6001), ports(discovery(app).candidates()))
    }

    @Test
    fun `the device matches across underscores, spaces and case`() {
        daemon(5001, app, 6001 to name("Pixel 7 Pro", "dtd_spike"))
        assertEquals(listOf(6001), ports(discovery(app, model = "pixel_7_pro").candidates()))
        assertEquals(listOf(6001), ports(discovery(app, model = " Pixel  7 Pro ").candidates()))
        assertTrue(discovery(app, model = "Pixel 7").candidates().isEmpty())
    }

    @Test
    fun `with no device model or package known, nothing is filtered`() {
        daemon(5001, app, 6001 to name("Pixel 8", "a"), 6002 to name("sdk gphone64 arm64", "b"))
        assertEquals(listOf(6001, 6002), ports(discovery(app, model = null, pkg = null).candidates()))
        assertEquals(listOf(6001, 6002), ports(discovery(app, model = "", pkg = " ").candidates()))
    }

    @Test
    fun `the package defaults to the project's pubspec name`() {
        Files.writeString(app.resolve(Pubspec.FILE_NAME), "name: dtd_spike\n")
        daemon(5001, app, 6001 to emulatorApp, 6002 to name("sdk gphone64 arm64", "x"))
        val found = DtdDiscovery(
            projectPath = app.toString(),
            deviceModel = null,
            registry = DtdRegistry(listOf(registryDir)) { true },
            query = { answers.getValue(it.port) },
        ).candidates()
        assertEquals(listOf(6001), ports(found))
    }

    @Test
    fun `ranked - exact workspace first, then the fewest unknowns, then the newest daemon`() {
        daemon(5001, repo, 6001 to emulatorApp, epoch = 300L)
        daemon(5002, app, 6002 to "unnamed", epoch = 100L)
        daemon(5003, app, 6003 to emulatorApp, epoch = 200L)
        daemon(5004, app, 6004 to emulatorApp, epoch = 300L)
        assertEquals(listOf(6004, 6003, 6002, 6001), ports(discovery(app).candidates()))
    }

    @Test
    fun `the same app listed by two daemons is offered once, from the better match`() {
        daemon(5001, repo, 6001 to emulatorApp)
        daemon(5002, app, 6001 to emulatorApp)
        val found = discovery(app).candidates().single()
        assertEquals(5002, found.daemon.uri.port)
    }

    @Test
    fun `a daemon that fails or predates ConnectedApp is skipped, the others are still asked`() {
        daemon(5001, app, epoch = 2L)
        answers.remove(5001)
        daemon(5002, app, 6002 to emulatorApp)
        val old = DtdDiscovery(
            projectPath = app.toString(),
            deviceModel = null,
            packageName = null,
            registry = DtdRegistry(listOf(registryDir)) { true },
            query = { uri ->
                if (uri.port == 5001) throw DtdException("no ConnectedApp", VmServiceRpcException.METHOD_NOT_FOUND)
                answers.getValue(uri.port)
            },
        )
        assertEquals(listOf(6002), ports(old.candidates()))
        assertEquals(listOf(6002), ports(discovery(app).candidates()))
    }

    @Test
    fun `no daemons, nothing found`() {
        assertTrue(discovery(app).discover().isEmpty())
    }

    @Test
    fun `candidates never print a secret or a token`() {
        daemon(5001, app, 6001 to emulatorApp)
        val found = discovery(app).candidates().single()
        listOf(found.toString(), found.description, found.daemon.toString(), found.service.toString()).forEach {
            assertFalse(it.contains("Secret5001") || it.contains("Tok6001"), it)
        }
        assertTrue(found.description.contains("dtd_spike"), found.description)
    }

    @Test
    fun `end to end against a fake daemon over a real WebSocket`() {
        FakeDtd().use { dtd ->
            Files.writeString(
                registryDir.resolve("1"),
                """{"wsUri":"${dtd.wsUri}","pid":1,"epoch":1,"workspaceRoot":"$app"}""",
            )
            val found = DtdDiscovery(
                projectPath = app.toString(),
                deviceModel = "sdk_gphone64_arm64",
                packageName = "dtd_spike",
                registry = DtdRegistry(listOf(registryDir)) { true },
            ).candidates().single()
            assertEquals("dtd_spike", found.service.packageName)
            assertEquals("ws://127.0.0.1:53296/${FakeDtd.VM_TOKEN}/ws", found.open().webSocketUri.toString())
        }
    }
}
