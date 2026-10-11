package spock.adb.flutter

import spock.adb.flutter.analysis.FlutterExtensionEvent

/**
 * What the live session's app reported, kept for Diagnose: the last few hundred errors, frames
 * and routes, each kind in its own bounded buffer so a minute of frames cannot push out the
 * error the developer is asking about.
 *
 * One session's at a time. It hears of each session as the service creates it, so the events
 * DDS replays on connect — what happened before Spock connected, marked as history — are kept
 * too; the buffers start over when another session becomes current, and events of a session that
 * never does (one refused, or another app's) are dropped then. Thread-safe: events arrive on the session's event
 * thread, Diagnose reads on a pooled one.
 */
class FlutterEventLog(
    private val errorCapacity: Int = ERRORS,
    private val frameCapacity: Int = FRAMES,
    private val navigationCapacity: Int = NAVIGATION,
) : FlutterSessionServiceListener {

    /** What was kept for one session, oldest first. History stays marked on each event. */
    data class Contents(
        val errors: List<FlutterExtensionEvent>,
        val frames: List<FlutterExtensionEvent>,
        val navigation: List<FlutterExtensionEvent>,
        /** Events of each kind pushed out by newer ones, by kind. */
        val dropped: Map<String, Int>,
    )

    private class Buffers(val session: FlutterSession) {
        val byKind = mapOf(
            FlutterExtensionEvent.ERROR to ArrayDeque<FlutterExtensionEvent>(),
            FlutterExtensionEvent.FRAME to ArrayDeque(),
            FlutterExtensionEvent.NAVIGATION to ArrayDeque(),
        )
        val dropped = mutableMapOf<String, Int>()
    }

    private val lock = Any()

    /** Sessions created and not yet decided; at most a couple, since connects replace each other. */
    private val opening = ArrayList<Buffers>()
    private var current: Buffers? = null

    /** Starts keeping [session]'s events, from before it connects. */
    override fun sessionCreated(session: FlutterSession) {
        synchronized(lock) {
            opening += Buffers(session)
            while (opening.size > MAX_OPENING) opening.removeAt(0)
        }
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = accept(session, event)
            },
        )
    }

    override fun sessionChanged(change: FlutterSessionChange) {
        when (change) {
            is FlutterSessionChange.Connected -> makeCurrent(change.session)
            is FlutterSessionChange.Replaced -> makeCurrent(change.session)
            // Kept: a report made just after the app went away can still say what it reported.
            is FlutterSessionChange.Disconnected -> Unit
        }
    }

    /** What [session] reported, or null when this log is not keeping [session]'s events. */
    fun contents(session: FlutterSession): Contents? = synchronized(lock) {
        val buffers = current?.takeIf { it.session === session } ?: return null
        Contents(
            errors = buffers.byKind.getValue(FlutterExtensionEvent.ERROR).toList(),
            frames = buffers.byKind.getValue(FlutterExtensionEvent.FRAME).toList(),
            navigation = buffers.byKind.getValue(FlutterExtensionEvent.NAVIGATION).toList(),
            dropped = buffers.dropped.toMap(),
        )
    }

    internal fun accept(session: FlutterSession, event: FlutterEvent) {
        val read = FlutterExtensionEvent.from(event) ?: return
        val capacity = capacityOf(read.kind) ?: return
        synchronized(lock) {
            val buffers = current?.takeIf { it.session === session }
                ?: opening.firstOrNull { it.session === session }
                ?: return
            val buffer = buffers.byKind.getValue(read.kind)
            buffer.addLast(read)
            if (buffer.size > capacity) {
                buffer.removeFirst()
                buffers.dropped.merge(read.kind, 1, Int::plus)
            }
        }
    }

    /** The buffers start over for [session], keeping what it reported while it was connecting. */
    private fun makeCurrent(session: FlutterSession) = synchronized(lock) {
        if (current?.session === session) return@synchronized
        // Only it and those created before it: a connect that has started since keeps its buffers.
        val index = opening.indexOfFirst { it.session === session }
        current = if (index >= 0) opening[index] else Buffers(session)
        if (index >= 0) opening.subList(0, index + 1).clear()
    }

    private fun capacityOf(kind: String): Int? = when (kind) {
        FlutterExtensionEvent.ERROR -> errorCapacity
        FlutterExtensionEvent.FRAME -> frameCapacity
        FlutterExtensionEvent.NAVIGATION -> navigationCapacity
        else -> null
    }

    companion object {
        const val ERRORS = 200
        const val FRAMES = 600
        const val NAVIGATION = 100
        private const val MAX_OPENING = 4
    }
}
