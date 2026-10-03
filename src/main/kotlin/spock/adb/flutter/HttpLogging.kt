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

    /**
     * An earlier session switched it on, and its connection ended before it could switch it off
     * (`flutter run` stopped, DDS with it). Spock still owns it: this session switches it off on close.
     */
    data object AdoptedBySpock : HttpRecording

    /** It was on before Spock looked (DevTools, the IDE): left as it is, on close too. */
    data object AlreadyOn : HttpRecording

    /** A debug or profile build whose isolate has not registered dart:io's extension yet. */
    data object Pending : HttpRecording

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
         * it off, in case the app applied it. An unknown answer is asked again at the isolate's
         * next extension or resume.
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
internal class HttpLogging(
    /** Where Spock's switching on is remembered across sessions; null keeps it to this session. */
    private val owners: () -> HttpOwners? = { null },
    /** An isolate's key in [owners] — `serial|pid|isolate` — or null when the session cannot say. */
    private val keyOf: (String) -> String? = { null },
) {

    /** Orders switching on against switching back off. */
    private val writeLock = ReentrantLock()

    /** UI isolates where Spock switched logging on. Kept across a lost connection: the app still has it on. */
    private val enabledBySpock = mutableSetOf<String>()

    /** Each of [enabledBySpock]'s keys in [owners], taken when it was switched on or adopted. */
    private val keys = mutableMapOf<String, String>()

    /** What [enable] found; [retry] when the answer is not known (a read failed, a write got none). */
    class Outcome(val recording: HttpRecording, val retry: Boolean)

    /**
     * Reads the flag on [isolateId] — every time, also where Spock switched it on before: a write
     * that got no answer may not have been applied — and switches it on if off. [stillAllowed] is
     * asked right before the write, under the write lock; when it says no, nothing is written and
     * null comes back.
     */
    fun enable(connected: VmServiceClient, isolateId: String, stillAllowed: () -> Boolean): Outcome? {
        val ours = synchronized(this) { isolateId in enabledBySpock }
        return when (readBool(connected, HTTP_LOGGING, isolateId)) {
            true -> Outcome(
                when {
                    ours -> HttpRecording.EnabledBySpock
                    adopt(isolateId) -> HttpRecording.AdoptedBySpock
                    else -> HttpRecording.AlreadyOn
                },
                retry = false,
            )
            null -> Outcome(failed, retry = true)
            false -> switchOn(connected, isolateId, stillAllowed)
        }
    }

    /**
     * The isolate is recorded before the write: a write that times out may still be applied, and
     * switching it off on close is harmless if it was not. Only a refusal un-records it.
     */
    private fun switchOn(connected: VmServiceClient, isolateId: String, stillAllowed: () -> Boolean): Outcome? =
        writeLock.withLock {
            if (!stillAllowed()) return@withLock null
            own(isolateId)
            try {
                connected.callServiceExtension(HTTP_LOGGING, isolateId, mapOf("enabled" to "true"))
                Outcome(HttpRecording.EnabledBySpock, retry = false)
            } catch (e: VmServiceRpcException) {
                forget(isolateId)
                log.warn("Could not enable HTTP timeline logging: ${e.message}")
                Outcome(failed, retry = false)
            } catch (e: VmServiceException) {
                log.warn("HTTP timeline logging may be on without an answer; close() switches it off: ${e.message}")
                Outcome(failed, retry = true)
            }
        }

    /** Whether [restore] has anything to switch off. */
    fun owesRestore(): Boolean = synchronized(this) { enabledBySpock.isNotEmpty() }

    /** An isolate that exited takes its flag with it. */
    fun forget(isolateId: String) = synchronized(this) {
        enabledBySpock -= isolateId
        keys.remove(isolateId)?.let { owners()?.forget(it) }
    }

    /** Records that Spock switched [isolateId] on, here and across sessions. */
    private fun own(isolateId: String) = synchronized(this) {
        enabledBySpock += isolateId
        keyOf(isolateId)?.let { key ->
            keys[isolateId] = key
            owners()?.record(key)
        }
    }

    /** Whether an earlier session's switching on of [isolateId] is still owed; this one owes it now. */
    private fun adopt(isolateId: String): Boolean {
        val key = keyOf(isolateId) ?: return false
        if (owners()?.owns(key) != true) return false
        synchronized(this) {
            enabledBySpock += isolateId
            keys[isolateId] = key
        }
        return true
    }

    /**
     * Every isolate Spock switched on that is still alive; a paused one is left, as it would not
     * answer. True when each was switched off or is gone; false when one was left on.
     */
    fun restore(connected: VmServiceClient): Boolean = writeLock.withLock {
        val isolateIds = synchronized(this) { enabledBySpock.toList().also { enabledBySpock.clear() } }
        isolateIds.map { isolateId ->
            val done = try {
                val params = JsonObject().apply { addProperty("isolateId", isolateId) }
                val isolate = FlutterIsolate.from(connected.call("getIsolate", params, RESTORE_TIMEOUT_MS))
                when {
                    isolate == null -> true
                    isolate.paused -> false
                    else -> {
                        val off = mapOf("enabled" to "false")
                        connected.callServiceExtension(HTTP_LOGGING, isolateId, off, RESTORE_TIMEOUT_MS)
                        true
                    }
                }
            } catch (e: VmServiceException) {
                log.warn("Could not switch HTTP timeline logging back off: ${e.message}")
                false
            }
            // Owed no more once off; still owed — for a later session to adopt — when not.
            val key = synchronized(this) { keys.remove(isolateId) }
            if (done) key?.let { owners()?.forget(it) }
            done
        }.all { it }
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

/**
 * The isolates where Spock switched HTTP logging on, across the sessions of one project, keyed
 * `serial|pid|isolate`: a session whose connection ended before it could switch logging back off
 * leaves it here, and the next session on the same isolate adopts it, so whatever Spock switched
 * on, Spock switches off. Bounded; a new process drops the old one's keys.
 */
internal class HttpOwners(private val capacity: Int = DEFAULT_CAPACITY) {
    private val keys = LinkedHashSet<String>()

    @Synchronized
    fun record(key: String) {
        keys += key
        while (keys.size > capacity) keys.remove(keys.first())
    }

    @Synchronized
    fun owns(key: String): Boolean = key in keys

    @Synchronized
    fun forget(key: String) {
        keys -= key
    }

    /** Drops what was owed in [serial]'s processes other than [pids]: those isolates are gone. */
    @Synchronized
    fun retainPids(serial: String, pids: Set<Long>) {
        keys.removeAll { key ->
            val (keySerial, pid) = key.split("|").let { it.getOrNull(0) to it.getOrNull(1)?.toLongOrNull() }
            keySerial == serial && pid !in pids
        }
    }

    companion object {
        private const val DEFAULT_CAPACITY = 64

        fun key(serial: String, pid: Int, isolateId: String): String = "$serial|$pid|$isolateId"
    }
}
