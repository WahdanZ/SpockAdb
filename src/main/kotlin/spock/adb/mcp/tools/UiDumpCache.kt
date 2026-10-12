package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import spock.adb.uitree.UiObservation
import java.util.concurrent.TimeUnit

/**
 * The last screen capture of each device, kept for [TTL_MS], so reads that follow each other at
 * once — a find, then the tap on what it found — pay for one `uiautomator` dump, which takes 2–3 s.
 *
 * Only a plain read takes from it. A wait's polls and an action's after-the-action check always
 * capture anew: "has it changed" cannot be answered from a copy. Input empties it — an element
 * action's dispatch, a scroll's swipe, and every tool call that is not read-only, as the MCP server
 * records it — so a read after an action never sees the screen from before it. Input from outside
 * Spock, a finger on the device, cannot be seen; [TTL_MS] bounds how old that copy can be.
 *
 * A capture is kept per serial and for the [IDevice] it came from, so a device that reconnected
 * under the same serial starts empty.
 */
internal class UiDumpCache(private val clockNanos: () -> Long = System::nanoTime) {

    private class Entry(val device: IDevice, val observation: UiObservation, val storedAtNanos: Long)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var generation = 0L

    /** Taken before a capture starts and given back to [store], which keeps nothing invalidated meanwhile. */
    fun generation(): Long = synchronized(lock) { generation }

    /** [serial]'s capture from [device], if one was stored under [TTL_MS] ago, with its age. */
    fun recent(serial: String, device: IDevice): Pair<UiObservation, Long>? = synchronized(lock) {
        val entry = entries[serial]?.takeIf { it.device === device } ?: return null
        val ageMs = TimeUnit.NANOSECONDS.toMillis(clockNanos() - entry.storedAtNanos)
        if (ageMs >= TTL_MS) {
            entries.remove(serial)
            return null
        }
        entry.observation to ageMs
    }

    /**
     * Keeps [observation] as [serial]'s latest, unless something was invalidated after [since] —
     * input sent while it was being captured makes it older than it looks.
     */
    fun store(serial: String, device: IDevice, observation: UiObservation, since: Long) = synchronized(lock) {
        if (since == generation) entries[serial] = Entry(device, observation, clockNanos())
    }

    /** Input was, or is about to be, sent: no capture taken before now is served again. */
    fun invalidate() = synchronized(lock) {
        generation++
        entries.clear()
    }

    /**
     * Sends input with [send], emptied before — so a capture under way is not kept — and after, so
     * none taken while the input was on its way is either. After, whether or not [send] failed: a
     * failed shell call may still have delivered it.
     */
    fun <T> aroundInput(send: () -> T): T {
        invalidate()
        try {
            return send()
        } finally {
            invalidate()
        }
    }

    companion object {
        const val TTL_MS = 500L

        /** The one every tool reads through. */
        val shared = UiDumpCache()
    }
}
