package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PrefsEditSessionTest {

    private val prefs = AppStoragePaths.parse("shared_prefs/settings.xml")
    private val xml = """
        <map>
            <boolean name="onboarding_seen" value="false" />
            <int name="launches" value="3" />
            <null name="cleared" />
        </map>
    """.trimIndent().toByteArray()

    private fun session() = PrefsEditSession(prefs, xml)

    private fun PrefsEditSession.row(key: String) = rows.single { it.key == key }

    @Test
    fun `an untouched file has no changes`() {
        val session = session()

        assertEquals(emptyList<PrefChange>(), session.changes())
        assertFalse(session.isDirty)
        assertArrayEquals(SharedPrefsXml.write(xml, emptyList()), session.encode())
    }

    @Test
    fun `a value changed and changed back writes nothing`() {
        val session = session()
        session.row("launches").text = "4"
        session.row("launches").text = "3"

        assertEquals(emptyList<PrefChange>(), session.changes())
    }

    @Test
    fun `edits, renames, deletions and additions become changes against the file`() {
        val session = session()
        session.row("onboarding_seen").text = "true"
        session.row("launches").key = "launch_count"
        session.addRow().apply {
            key = "flag"
            type = PrefType.BOOLEAN
            text = "true"
        }

        assertEquals(
            setOf(
                PrefChange.Remove("launches"),
                PrefChange.Put("onboarding_seen", PrefValue.BooleanValue(true)),
                PrefChange.Put("launch_count", PrefValue.IntValue(3)),
                PrefChange.Put("flag", PrefValue.BooleanValue(true)),
            ),
            session.changes().toSet(),
        )
        assertEquals(
            listOf("onboarding_seen", "cleared", "launch_count", "flag"),
            SharedPrefsXml.read(session.encode()).map { it.key },
        )
    }

    @Test
    fun `a deleted row removes its key`() {
        val session = session()
        session.rows.remove(session.row("launches"))

        assertEquals(listOf(PrefChange.Remove("launches")), session.changes())
    }

    @Test
    fun `a row that does not parse names itself and blocks the write`() {
        val session = session()
        session.row("launches").text = "many"

        assertNotNull(session.problemWith(session.row("launches")))
        assertTrue(session.isDirty)
        val thrown = assertThrows<IllegalArgumentException> { session.encode() }
        assertTrue(thrown.message!!.contains("many"), thrown.message)
    }

    @Test
    fun `a key used twice, or taken by an unreadable entry, is refused`() {
        val twice = session().apply { row("launches").key = "onboarding_seen" }
        val locked = session().apply { row("launches").key = "cleared" }

        assertThrows<IllegalArgumentException> { twice.changes() }
        assertThrows<IllegalArgumentException> { locked.changes() }
    }

    @Test
    fun `unreadable entries are shown but not editable`() {
        val cleared = session().row("cleared")

        assertFalse(cleared.editable)
        assertNull(cleared.type)
    }

    @Test
    fun `new rows get a key the file does not have`() {
        val session = session()

        assertEquals(listOf("new_key", "new_key_2"), listOf(session.addRow().key, session.addRow().key))
    }

    @Test
    fun `encrypted, unsupported and unreadable files are read-only`() {
        val keyset = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        val encrypted = PrefsEditSession(prefs, """<map><string name="$keyset">x</string></map>""".toByteArray())
        val proto = PrefsEditSession(AppStoragePaths.parse("files/datastore/user.pb"), byteArrayOf(1, 2))
        val broken = PrefsEditSession(prefs, "<map>".toByteArray())

        listOf(encrypted, proto, broken).forEach { session ->
            assertNotNull(session.readOnlyReason, session.file.path)
            assertFalse(session.isDirty)
            assertTrue(session.rows.none { it.editable })
            assertThrows<IllegalStateException> { session.changes() }
            assertThrows<IllegalStateException> { session.addRow() }
        }
        assertEquals(1, encrypted.rows.size)
    }

    @Test
    fun `a key held twice in a DataStore file shows the value that is read`() {
        val file = AppStoragePaths.parse("files/datastore/settings.preferences_pb")
        val bytes = PreferencesProto.write(ByteArray(0), listOf(PrefChange.Put("a", PrefValue.IntValue(1)))) +
            PreferencesProto.write(ByteArray(0), listOf(PrefChange.Put("a", PrefValue.IntValue(2))))

        val session = PrefsEditSession(file, bytes)

        assertEquals(listOf("2"), session.rows.map { it.text })
        assertEquals(PrefType.entries.toList(), session.types)
    }
}
