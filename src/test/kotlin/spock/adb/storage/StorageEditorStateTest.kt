package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the storage editor says about itself: how much Apply would write, and which files a
 * search leaves showing.
 *
 * Both used to be unsaid. Apply being enabled meant "something", the file list meant "all of
 * them", and a file replaced on the device after it was read was a sentence in the status line
 * under a panel that still looked ready to apply.
 */
class StorageEditorStateTest {

    private val prefs = AppStoragePaths.parse("shared_prefs/settings.xml")
    private val xml = """
        <map>
            <boolean name="onboarding_seen" value="false" />
            <int name="launches" value="3" />
        </map>
    """.trimIndent().toByteArray()

    private fun session() = PrefsEditSession(prefs, xml)

    @Test
    fun `an untouched file has nothing to say`() {
        assertEquals("", storageChangeSummary(session(), stale = false))
        assertEquals("", storageChangeSummary(null, stale = false))
    }

    @Test
    fun `changes are counted, and the count reads as a sentence`() {
        val one = session().apply { rows.single { it.key == "launches" }.text = "4" }
        assertEquals("1 unsaved change", storageChangeSummary(one, stale = false))

        val two = session().apply {
            rows.single { it.key == "launches" }.text = "4"
            rows.single { it.key == "onboarding_seen" }.text = "true"
        }
        assertEquals("2 unsaved changes", storageChangeSummary(two, stale = false))
    }

    @Test
    fun `a row that cannot be written says so before Apply is pressed`() {
        val broken = session().apply { rows.single { it.key == "launches" }.text = "not a number" }

        val summary = storageChangeSummary(broken, stale = false)

        assertTrue(summary.isNotEmpty(), "a row that cannot be written must not read as no changes")
        assertTrue(summary.contains("not a number"), summary)
    }

    @Test
    fun `a file that changed on the device outranks the change count`() {
        val edited = session().apply { rows.single { it.key == "launches" }.text = "4" }

        assertEquals(STALE, storageChangeSummary(edited, stale = true))
        assertTrue(STALE.contains("reload"), "the state has to name what to do about it")
    }

    @Test
    fun `searching the tree matches an entry's own name`() {
        // The tree shows where a file is by where it sits, so matching the path as well would
        // light up every file under shared_prefs for the word "prefs".
        assertTrue(matchesStorageSearch("feature_flags.xml", "flags"))
        assertTrue(matchesStorageSearch("feature_flags.xml", "FLAGS"), "the search ignores case")
        assertTrue(matchesStorageSearch("anything", "   "), "a blank search hides nothing")
        assertFalse(matchesStorageSearch("feature_flags.xml", "prefs"))
    }
}
