package spock.adb.flutter.navigation

import com.google.gson.JsonObject
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceTimeoutException
import java.util.concurrent.TimeUnit

/**
 * [VmObjects] on a live session's UI isolate, through [FlutterSession.callVm] — the session's read
 * path, so `ReadOnlyCalls` still guards a read-only connection and every answer comes scrubbed.
 *
 * One read shares [budgetMs]: each call waits at most what is left of it, and a read that runs
 * out stops with the budget named, so a slow app costs Home at most that long.
 */
internal class SessionObjects(
    private val session: FlutterSession,
    private val isolateId: String,
    private val budgetMs: Long,
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) : VmObjects {

    private val deadline = clock() + budgetMs

    override fun isolate(): JsonObject = call(GET_ISOLATE, params())

    override fun get(objectId: String): JsonObject = call(
        GET_OBJECT,
        params().apply { addProperty("objectId", objectId) }
    )

    override fun instances(classId: String, limit: Int): JsonObject = call(
        GET_INSTANCES,
        params().apply {
            addProperty("objectId", classId)
            addProperty("limit", limit)
            addProperty("includeSubclasses", true)
        },
    )

    private fun params() = JsonObject().apply { addProperty("isolateId", isolateId) }

    private fun call(method: String, params: JsonObject): JsonObject {
        val left = deadline - clock()
        if (left <= 0) throw outOfTime()
        return try {
            session.callVm(method, params, left)
        } catch (_: VmServiceTimeoutException) {
            throw outOfTime()
        }
    }

    private fun outOfTime() = VmServiceException("the app did not answer within $budgetMs ms, the most Spock waits")

    companion object {
        const val GET_ISOLATE = "getIsolate"
        const val GET_OBJECT = "getObject"
        const val GET_INSTANCES = "getInstances"

        /** Every method this sends: object inspection, nothing that runs code or changes the app. */
        val METHODS = setOf(GET_ISOLATE, GET_OBJECT, GET_INSTANCES)
    }
}
