package spock.adb.flutter

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceRpcException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Whether dart:io records the UI isolate's HTTP requests for Spock, and why not when it does not. */
sealed interface HttpRecording {

    /** Spock switched it on; closing the session switches it back off. */
    data object EnabledBySpock : HttpRecording

    /** It was on before Spock looked (DevTools, the IDE): left as it is, on close too. */
    data object AlreadyOn : HttpRecording

    data class Off(val reason: Reason) : HttpRecording

    enum class Reason {
        /** The caller asked for no HTTP recording (the Settings switch). */
        SETTING_OFF,

        /** Not a DDS connection: Spock writes nothing to the app. */
        NOT_DDS,

        /** Neither a debug nor a profile build, or not known yet: decided again as extensions register. */
        RELEASE_OR_UNKNOWN_MODE,

        /**
         * The read or the switch failed. After a switch that got no answer, close still switches
         * it off, in case the app applied it.
         */
        FAILED,
    }
}

/**
 * dart:io's HTTP timeline logging for P5's HTTP failures: off by default (spike S4), a per-isolate
 * flag a hot restart resets. Switched on where it is off, and back off on [restore] only where
 * Spock was the one that switched it on [FR10]. Which isolates are worth asking about is the
 * session's call; this keeps the ownership.
 */
internal class HttpLogging {

    /** Orders switching on against switching back off. */
    private val writeLock = ReentrantLock()

    /** UI isolates where Spock switched logging on. Kept across a lost connection: the app still has it on. */
    private val enabledBySpock = mutableSetOf<String>()

    /**
     * Reads the flag on [isolateId] and switches it on if off. [stillAllowed] is asked right
     * before the write, under the write lock; when it says no, nothing is written and null comes
     * back. The isolate is recorded before the write: a write that times out may still be applied,
     * and switching it off on close is harmless if it was not. Only a refusal un-records it.
     */
    fun enable(connected: VmServiceClient, isolateId: String, stillAllowed: () -> Boolean): HttpRecording? {
        if (synchronized(this) { isolateId in enabledBySpock }) return HttpRecording.EnabledBySpock
        val enabled = readBool(connected, HTTP_LOGGING, isolateId)
        if (enabled != false) return if (enabled == true) HttpRecording.AlreadyOn else failed
        return writeLock.withLock {
            if (!stillAllowed()) return@withLock null
            synchronized(this) { enabledBySpock += isolateId }
            try {
                connected.callServiceExtension(HTTP_LOGGING, isolateId, mapOf("enabled" to "true"))
                HttpRecording.EnabledBySpock
            } catch (e: VmServiceRpcException) {
                synchronized(this) { enabledBySpock -= isolateId }
                log.warn("Could not enable HTTP timeline logging: ${e.message}")
                failed
            } catch (e: VmServiceException) {
                log.warn("HTTP timeline logging may be on without an answer; close() switches it off: ${e.message}")
                failed
            }
        }
    }

    /** An isolate that exited takes its flag with it. */
    fun forget(isolateId: String) = synchronized(this) { enabledBySpock -= isolateId }

    /** Every isolate Spock switched on that is still alive; a paused one is left, as it would not answer. */
    fun restore(connected: VmServiceClient) = writeLock.withLock {
        val isolateIds = synchronized(this) { enabledBySpock.toList().also { enabledBySpock.clear() } }
        isolateIds.forEach { isolateId ->
            try {
                val params = JsonObject().apply { addProperty("isolateId", isolateId) }
                val isolate = FlutterIsolate.from(connected.call("getIsolate", params, RESTORE_TIMEOUT_MS))
                if (isolate != null && !isolate.paused) {
                    val off = mapOf("enabled" to "false")
                    connected.callServiceExtension(HTTP_LOGGING, isolateId, off, RESTORE_TIMEOUT_MS)
                }
            } catch (e: VmServiceException) {
                log.warn("Could not switch HTTP timeline logging back off: ${e.message}")
            }
        }
    }

    companion object {
        const val HTTP_LOGGING = "ext.dart.io.httpEnableTimelineLogging"

        /** Closing should be quick: an isolate that does not answer in this keeps its logging. */
        private const val RESTORE_TIMEOUT_MS = 2_000L

        private val failed = HttpRecording.Off(HttpRecording.Reason.FAILED)
        private val log = Logger.getInstance(HttpLogging::class.java)
    }
}

/** Called with no `enabled` argument, a bool extension only reports its value. */
internal fun readBool(connected: VmServiceClient, extension: String, isolateId: String): Boolean? = try {
    ExtensionResults.bool(connected.callServiceExtension(extension, isolateId))
} catch (e: VmServiceException) {
    Logger.getInstance(HttpLogging::class.java).warn("Could not read $extension: ${e.message}")
    null
}
