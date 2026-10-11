package spock.adb.flutter.dtd

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeWebSocketServer
import spock.adb.flutter.vmservice.VmServiceUri
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted Dart Tooling Daemon on [FakeWebSocketServer]: answers `ConnectedApp.getVmServices`
 * with [vmServices] (the recorded S12 answer by default), or with [error] when set, or not at all
 * when [silent]. Records every request.
 */
class FakeDtd(val secret: String = SECRET) : AutoCloseable {

    val server = FakeWebSocketServer(::onRequest)
    val requests = CopyOnWriteArrayList<JsonObject>()

    @Volatile
    var vmServices: JsonObject = recorded()

    /** JSON-RPC error code and message to answer with instead. */
    @Volatile
    var error: Pair<Int, String>? = null

    @Volatile
    var silent = false

    val wsUri: String get() = "ws://127.0.0.1:${server.port}/$secret"

    val uri: DtdUri get() = DtdUri.parse(wsUri)

    override fun close() = server.close()

    private fun onRequest(text: String) {
        val request = JsonParser.parseString(text).asJsonObject
        requests += request
        if (silent) return
        val response = FakeVmService.response(request.get("id").asString)
        val failure = error
        when {
            failure != null -> response.add(
                "error",
                JsonObject().apply {
                    addProperty("code", failure.first)
                    addProperty("message", failure.second)
                },
            )
            request.get("method").asString == DtdClient.GET_VM_SERVICES -> response.add("result", vmServices)
            else -> response.add(
                "error",
                JsonObject().apply {
                    addProperty("code", FakeVmService.METHOD_NOT_FOUND)
                    addProperty("message", "Method not found")
                },
            )
        }
        server.sendText(response.toString())
    }

    companion object {
        const val SECRET = "DtDsEcReT_xyz="

        /** The token inside the recorded answer's VM Service address. */
        const val VM_TOKEN = "VmToKeN_123="

        fun recorded(): JsonObject =
            JsonParser.parseString(FakeDtd::class.java.getResource("/dtd/getVmServices.json")!!.readText()).asJsonObject

        /** A DTD candidate at [uri], from an exact-workspace daemon; for tests of what comes after discovery. */
        fun candidate(uri: VmServiceUri, name: String = "Kind: Flutter - Device: sdk gphone64 arm64 - Package: app") =
            DtdCandidate(
                DtdVmService(uri, name, "Flutter", "sdk gphone64 arm64", "app"),
                DtdRegistry.parse("""{"wsUri":"ws://127.0.0.1:1/S=","pid":1,"epoch":1}""", Path.of("1"))!!,
                WorkspaceRelation(WorkspaceMatch.EXACT, Path.of("/work/app"), Path.of("/work/app")),
            )

        /** A `VmServicesResponse` listing [services] as (address, name) pairs. */
        fun response(vararg services: Pair<String, String>): JsonObject =
            JsonParser.parseString(
                services.joinToString(",", """{"type":"VmServicesResponse","vmServices":[""", "]}") { (uri, name) ->
                    """{"uri":"$uri","name":"$name"}"""
                },
            ).asJsonObject
    }
}
