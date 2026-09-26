package spock.adb.uitree

/**
 * How many times each composable ran during a recording, read from a Perfetto trace.
 *
 * The counts are Compose's own. With `androidx.compose.runtime:runtime-tracing` in an app, every
 * composable that composes or recomposes emits a slice named after it, such as
 * `com.example.Greeting (Greeting.kt:42)`, while tracing is on. Nothing here estimates or infers
 * a count: a composable that ran 12 times left 12 slices.
 *
 * The accessibility tree the Inspector shows carries no composable names, so these counts cannot
 * be pinned to its nodes. They are a list of functions with their source lines instead.
 */
data class RecompositionCounts(
    /** Most frequently run first. */
    val composables: List<ComposableCount>,
    /** How long the recording ran for, as asked of Perfetto. */
    val durationMs: Long,
) {
    val total: Int get() = composables.sumOf { it.count }

    /** The app's own composables, without the Compose, Material and Kotlin libraries it calls. */
    fun appOnly(): List<ComposableCount> = composables.filterNot { it.isLibrary }
}

data class ComposableCount(
    /** Fully qualified, as the Compose compiler wrote it: `com.example.ui.Greeting`. */
    val name: String,
    /** The source file's name only; Compose does not record its directory. */
    val file: String,
    val line: Int,
    /** Times composed or recomposed during the recording. */
    val count: Int,
) {
    /** `Greeting`, or `Row.<anonymous>` for a lambda: the part after the package. */
    val simpleName: String
        get() = name.split('.').dropWhile { it.firstOrNull()?.isLowerCase() == true }
            .joinToString(".")
            .ifEmpty { name.substringAfterLast('.') }

    val location: String get() = "$file:$line"

    /** The package the name starts with, used to tell two files of the same name apart. */
    val packageName: String
        get() = name.split('.').takeWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")

    val isLibrary: Boolean get() = LIBRARY_PREFIXES.any { name.startsWith(it) }

    private companion object {
        val LIBRARY_PREFIXES = listOf("androidx.", "kotlin.", "kotlinx.")
    }
}

/**
 * Reads a Perfetto trace's track events and counts composition slices.
 *
 * A hand-written reader rather than a protobuf dependency: it needs five fields of four messages,
 * and the plugin should not ship a protobuf runtime for that. Field numbers are Perfetto's
 * `trace_packet.proto`, `track_event.proto` and `interned_data.proto`, which are stable by
 * protobuf's own rules.
 */
object RecompositionTraceParser {

    /**
     * @param pid the traced app's process. Other processes that use the Perfetto SDK (WebView, for
     *   one) write track events into the same trace; with [pid] given, only this process's count.
     *   Packets older Android versions write without a trusted pid are kept.
     * @throws IllegalArgumentException when [trace] is not a readable Perfetto trace.
     */
    fun parse(trace: ByteArray, durationMs: Long, pid: Int? = null): RecompositionCounts {
        val counts = HashMap<String, Int>()
        // Event names are interned per sequence: a packet may name an event by number only, having
        // said which name that number stands for in an earlier packet of the same sequence.
        val interned = HashMap<Long, HashMap<Long, String>>()

        ProtoReader(trace).forEachField { field, value ->
            if (field == TRACE_PACKET && value is ByteArray) {
                readPacket(value, pid, interned)?.let { counts.merge(it, 1, Int::plus) }
            }
        }

        val composables = counts.mapNotNull { (slice, count) -> composable(slice, count) }
            .sortedWith(compareByDescending<ComposableCount> { it.count }.thenBy { it.name })
        return RecompositionCounts(composables, durationMs)
    }

    /** The slice name a packet begins, or null when it begins none that counts. */
    private fun readPacket(packet: ByteArray, pid: Int?, interned: HashMap<Long, HashMap<Long, String>>): String? {
        var sequence = 0L
        var trustedPid: Long? = null
        var flags = 0L
        var cleared = false
        var internedData: ByteArray? = null
        var event: ByteArray? = null
        ProtoReader(packet).forEachField { field, value ->
            when (field) {
                SEQUENCE_ID -> sequence = value as Long
                TRUSTED_PID -> trustedPid = value as Long
                SEQUENCE_FLAGS -> flags = value as Long
                INCREMENTAL_STATE_CLEARED -> cleared = (value as Long) != 0L
                INTERNED_DATA -> internedData = value as? ByteArray
                TRACK_EVENT -> event = value as? ByteArray
            }
        }

        val names = interned.getOrPut(sequence) { HashMap() }
        if (cleared || flags and SEQ_INCREMENTAL_STATE_CLEARED != 0L) names.clear()
        internedData?.let { readInternedNames(it, names) }

        if (pid != null && trustedPid != null && trustedPid != pid.toLong()) return null
        return event?.let { beginSliceName(it, names) }
    }

    private fun readInternedNames(data: ByteArray, into: MutableMap<Long, String>) {
        ProtoReader(data).forEachField { field, value ->
            if (field != INTERNED_EVENT_NAMES || value !is ByteArray) return@forEachField
            var iid: Long? = null
            var name: String? = null
            ProtoReader(value).forEachField { f, v ->
                when (f) {
                    EVENT_NAME_IID -> iid = v as Long
                    EVENT_NAME_NAME -> name = (v as? ByteArray)?.toString(Charsets.UTF_8)
                }
            }
            val key = iid ?: return@forEachField
            name?.let { into[key] = it }
        }
    }

    private fun beginSliceName(event: ByteArray, names: Map<Long, String>): String? {
        var type = 0L
        var nameIid: Long? = null
        var name: String? = null
        ProtoReader(event).forEachField { field, value ->
            when (field) {
                EVENT_TYPE -> type = value as Long
                EVENT_NAME_IID_REF -> nameIid = value as Long
                EVENT_NAME -> name = (value as? ByteArray)?.toString(Charsets.UTF_8)
            }
        }
        if (type != TYPE_SLICE_BEGIN) return null
        return name ?: nameIid?.let { names[it] }
    }

    /**
     * A composition slice, or null for any other slice in the trace. Compose names them
     * `<qualified name> (<file>:<line>)`; nothing else in a trace is expected to look like that
     * with a `.kt` file.
     */
    internal fun composable(slice: String, count: Int): ComposableCount? {
        val match = COMPOSITION_SLICE.matchEntire(slice) ?: return null
        val (name, file, line) = match.destructured
        return ComposableCount(name, file, line.toInt(), count)
    }

    private val COMPOSITION_SLICE = Regex("""^(\S.*?) \(([^():/\s]+\.kt):(\d+)\)$""")

    // Trace
    private const val TRACE_PACKET = 1

    // TracePacket
    private const val SEQUENCE_ID = 10
    private const val TRACK_EVENT = 11
    private const val INTERNED_DATA = 12
    private const val SEQUENCE_FLAGS = 13
    private const val INCREMENTAL_STATE_CLEARED = 41
    private const val TRUSTED_PID = 79
    private const val SEQ_INCREMENTAL_STATE_CLEARED = 1L

    // InternedData and EventName
    private const val INTERNED_EVENT_NAMES = 2
    private const val EVENT_NAME_IID = 1
    private const val EVENT_NAME_NAME = 2

    // TrackEvent
    private const val EVENT_TYPE = 9
    private const val EVENT_NAME_IID_REF = 10
    private const val EVENT_NAME = 23
    private const val TYPE_SLICE_BEGIN = 1L
}

/**
 * The protobuf wire format, one message deep: each field's number and its value, a [Long] for a
 * varint or fixed-width field and a [ByteArray] for a length-delimited one.
 */
private class ProtoReader(private val bytes: ByteArray) {
    private var position = 0

    fun forEachField(onField: (field: Int, value: Any) -> Unit) {
        while (position < bytes.size) {
            val key = varint()
            val field = (key ushr WIRE_TYPE_BITS).toInt()
            val value: Any = when ((key and WIRE_TYPE_MASK).toInt()) {
                WIRE_VARINT -> varint()
                WIRE_FIXED64 -> fixed(Long.SIZE_BYTES)
                WIRE_LENGTH_DELIMITED -> {
                    val length = varint()
                    require(length >= 0 && length <= bytes.size - position) { "Truncated Perfetto trace." }
                    bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
                }
                WIRE_FIXED32 -> fixed(Int.SIZE_BYTES)
                else -> throw IllegalArgumentException("Not a Perfetto trace: unknown protobuf wire type.")
            }
            onField(field, value)
        }
    }

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(position < bytes.size && shift < Long.SIZE_BITS) { "Truncated Perfetto trace." }
            val byte = bytes[position++].toInt()
            result = result or ((byte and VARINT_PAYLOAD).toLong() shl shift)
            if (byte and VARINT_CONTINUES == 0) return result
            shift += VARINT_BITS
        }
    }

    private fun fixed(size: Int): Long {
        require(size <= bytes.size - position) { "Truncated Perfetto trace." }
        var result = 0L
        repeat(size) { result = result or ((bytes[position + it].toLong() and BYTE_MASK) shl (it * Byte.SIZE_BITS)) }
        position += size
        return result
    }

    private companion object {
        const val WIRE_TYPE_BITS = 3
        const val WIRE_TYPE_MASK = 7L
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5
        const val VARINT_PAYLOAD = 0x7f
        const val VARINT_CONTINUES = 0x80
        const val VARINT_BITS = 7
        const val BYTE_MASK = 0xffL
    }
}
