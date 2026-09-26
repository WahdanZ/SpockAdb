package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

class RecompositionTraceParserTest {

    /**
     * Ten seconds of the sample app's Recomposition screen on an API 34 emulator, recorded with the
     * same Perfetto config the plugin sends. Only the ticking counter was changing: it ticks about ten
     * times a second, and it and the four composables it calls each ran 95 times.
     */
    private val real = javaClass.getResourceAsStream("/perfetto/composition-api34-sample-10s.pftrace")!!.readBytes()

    @Test
    fun `counts each composable's slices in a real trace`() {
        val counts = RecompositionTraceParser.parse(real, durationMs = 10_000)

        val ticking = counts.composables.single { it.simpleName == "TickingCounter" }
        assertEquals(95, ticking.count)
        assertEquals("spock.adb.sample.compose.TickingCounter", ticking.name)
        assertEquals("RecompositionActivity.kt:73", ticking.location)
        assertEquals(5, counts.composables.size)
        assertEquals(475, counts.total)
    }

    @Test
    fun `the app's own composables leave out the libraries it calls`() {
        val counts = RecompositionTraceParser.parse(real, durationMs = 10_000)

        assertEquals(listOf("TickingCounter"), counts.appOnly().map { it.simpleName })
        assertTrue(counts.composables.single { it.simpleName == "Text" }.isLibrary)
    }

    @Test
    fun `only the traced process counts when its pid is given`() {
        assertEquals(475, RecompositionTraceParser.parse(real, 10_000, pid = 18394).total)
        assertEquals(0, RecompositionTraceParser.parse(real, 10_000, pid = 1).total)
    }

    @Test
    fun `a truncated trace is refused rather than half counted`() {
        assertThrows<IllegalArgumentException> {
            RecompositionTraceParser.parse(real.copyOf(real.size - 3), 10_000)
        }
    }

    @Test
    fun `an empty trace counts nothing`() {
        assertTrue(RecompositionTraceParser.parse(ByteArray(0), 5_000).composables.isEmpty())
    }

    @Test
    fun `an interned name is forgotten when its sequence clears incremental state`() {
        val trace = trace(
            packet(sequence = 1, internedName = 7L to "com.example.Old (Old.kt:1)", event = begin(nameIid = 7)),
            // Cleared: iid 7 now names something else, and the old name must not be counted for it.
            packet(
                sequence = 1,
                flags = 1,
                internedName = 7L to "com.example.New (New.kt:2)",
                event = begin(nameIid = 7),
            ),
            packet(sequence = 1, event = begin(nameIid = 7)),
        )

        val counts = RecompositionTraceParser.parse(trace, 1_000).composables.associate { it.simpleName to it.count }

        assertEquals(mapOf("Old" to 1, "New" to 2), counts)
    }

    @Test
    fun `slice ends and other slices are not counted`() {
        val trace = trace(
            packet(sequence = 1, event = begin(name = "com.example.Row (Row.kt:3)")),
            packet(sequence = 1, event = event(type = 2, name = "com.example.Row (Row.kt:3)")),
            packet(sequence = 1, event = begin(name = "ThreadControllerImpl::RunTask")),
        )

        assertEquals(1, RecompositionTraceParser.parse(trace, 1_000).total)
    }

    @Test
    fun `composition slice names are read into name, file and line`() {
        val lambda = RecompositionTraceParser.composable(
            "com.example.ComposableSingletons\$MainKt.lambda\$-58.<anonymous> (Main.kt:43)",
            1,
        )!!
        assertEquals("Main.kt", lambda.file)
        assertEquals(43, lambda.line)
        assertEquals("ComposableSingletons\$MainKt.lambda\$-58.<anonymous>", lambda.simpleName)
        assertEquals("com.example", lambda.packageName)

        val getter = RecompositionTraceParser.composable("androidx.compose.runtime.<get-x> (Composables.kt:228)", 1)!!
        assertEquals("<get-x>", getter.simpleName)
        assertFalse(RecompositionTraceParser.composable("com.example.Row (Row.kt:3)", 1)!!.isLibrary)

        assertNull(RecompositionTraceParser.composable("Choreographer#doFrame 123", 1))
        assertNull(RecompositionTraceParser.composable("measure (Layout.java:10)", 1))
    }

    // ------------------------------------------------------------ a minimal protobuf writer

    private fun trace(vararg packets: ByteArray) = message { packets.forEach { bytes(1, it) } }

    private fun packet(sequence: Long, flags: Long = 0, internedName: Pair<Long, String>? = null, event: ByteArray) =
        message {
            varint(10, sequence)
            if (flags != 0L) varint(13, flags)
            internedName?.let { (iid, name) ->
                val eventName = message {
                    varint(1, iid)
                    bytes(2, name.toByteArray())
                }
                bytes(12, message { bytes(2, eventName) })
            }
            bytes(11, event)
        }

    private fun begin(name: String? = null, nameIid: Long? = null) = event(type = 1, name = name, nameIid = nameIid)

    private fun event(type: Long, name: String? = null, nameIid: Long? = null) = message {
        varint(9, type)
        nameIid?.let { varint(10, it) }
        name?.let { bytes(23, it.toByteArray()) }
    }

    private class Writer {
        val out = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) {
            raw((field shl 3).toLong())
            raw(value)
        }
        fun bytes(field: Int, value: ByteArray) {
            raw(((field shl 3) or 2).toLong())
            raw(value.size.toLong())
            out.write(value)
        }
        private fun raw(value: Long) {
            var v = value
            while (v >= 0x80) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
    }

    private fun message(build: Writer.() -> Unit): ByteArray = Writer().apply(build).out.toByteArray()
}
