package spock.adb.mcp

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.command.FakeStorageDevice
import spock.adb.mcp.tools.DeleteAppPreferenceTool
import spock.adb.mcp.tools.ListAppStorageTool
import spock.adb.mcp.tools.ReadAppStorageTool
import spock.adb.mcp.tools.SetAppPreferenceTool
import spock.adb.storage.PrefItem
import spock.adb.storage.PrefValue
import spock.adb.storage.SharedPrefsXml

/**
 * The storage tools against a scripted device: reads need no approval, and an edit is asked
 * about only once it is known to be possible — and does nothing at all when declined.
 */
class AppStorageToolsTest {

    private val storage = FakeStorageDevice().apply {
        files[PREFS] = """<map><boolean name="onboarding_seen" value="false" /></map>""".toByteArray()
    }

    private fun context(approve: Boolean = true) = FakeToolContext(
        available = listOf(FakeToolContext.device("emulator-5554").copy(device = storage.device)),
        confirmationAnswer = approve,
    )

    private fun args(vararg pairs: Pair<String, String>) = JsonObject().apply {
        pairs.forEach { (name, value) -> addProperty(name, value) }
    }

    @Test
    fun `lists files without asking`() {
        val context = context()

        val result = ListAppStorageTool().execute(JsonObject(), context)

        assertFalse(result.isError, result.text())
        assertEquals("$PREFS  (SharedPreferences)", result.text())
        assertTrue(context.confirmations.isEmpty())
    }

    @Test
    fun `reads typed entries`() {
        val result = ReadAppStorageTool().execute(args("file" to PREFS), context())

        assertFalse(result.isError, result.text())
        assertEquals("$PREFS (SharedPreferences, 1 entry)\nonboarding_seen (boolean) = false", result.text())
    }

    @Test
    fun `a missing file is reported as not found`() {
        val result = ReadAppStorageTool().execute(args("file" to DATASTORE), context())

        assertTrue(result.isError)
        assertTrue(result.text().contains("not found"), result.text())
    }

    @Test
    fun `a custom-schema DataStore file is named as unsupported, not decoded`() {
        val result = ReadAppStorageTool().execute(args("file" to "files/datastore/user.pb"), context())

        assertTrue(result.text().contains("cannot be decoded"), result.text())
        assertTrue(storage.commands.none { it.contains("base64") }, "nothing should be read: ${storage.commands}")
    }

    @Test
    fun `an approved change is confirmed with before and after, then written`() {
        val context = context(approve = true)

        val result = SetAppPreferenceTool().execute(
            args("file" to PREFS, "key" to "onboarding_seen", "type" to "boolean", "value" to "true"),
            context,
        )

        assertFalse(result.isError, result.text())
        assertEquals(listOf("android_set_app_preference"), context.confirmations)
        val summary = context.confirmationSummaries.single()
        assertTrue(summary.contains("from false (boolean) to true (boolean)"), summary)
        assertTrue(summary.contains("stops the app"), summary)
        assertEquals(
            listOf(PrefItem.Typed("onboarding_seen", PrefValue.BooleanValue(true))),
            SharedPrefsXml.read(storage.files.getValue(PREFS)),
        )
    }

    @Test
    fun `an empty key is a key, and can be set and deleted`() {
        val context = context(approve = true)

        val set = SetAppPreferenceTool().execute(
            args("file" to PREFS, "key" to "", "type" to "string", "value" to "under the empty key"),
            context,
        )

        assertFalse(set.isError, set.text())
        assertEquals(
            PrefValue.StringValue("under the empty key"),
            SharedPrefsXml.read(storage.files.getValue(PREFS)).filterIsInstance<PrefItem.Typed>()
                .single { it.key == "" }.value,
        )

        val deleted = DeleteAppPreferenceTool().execute(args("file" to PREFS, "key" to ""), context)

        assertFalse(deleted.isError, deleted.text())
        assertTrue(SharedPrefsXml.read(storage.files.getValue(PREFS)).none { it.key == "" })
    }

    @Test
    fun `a declined change stops nothing and writes nothing`() {
        val before = storage.files.getValue(PREFS)

        val result = SetAppPreferenceTool().execute(
            args("file" to PREFS, "key" to "onboarding_seen", "type" to "boolean", "value" to "true"),
            context(approve = false),
        )

        assertTrue(result.isError)
        assertTrue(storage.commands.none { it.startsWith("am force-stop") }, "${storage.commands}")
        assertTrue(storage.pushed.isEmpty())
        assertTrue(before.contentEquals(storage.files.getValue(PREFS)))
    }

    @Test
    fun `a write that landed but did not read back is reported as written, not as failed`() {
        storage.corruptWrites = true

        val result = SetAppPreferenceTool().execute(
            args("file" to PREFS, "key" to "onboarding_seen", "type" to "boolean", "value" to "true"),
            context(approve = true),
        )

        assertTrue(result.isError)
        assertTrue(result.text().contains("was written but not verified"), result.text())
        assertFalse(result.text().contains("could not change", ignoreCase = true), result.text())
    }

    @Test
    fun `a key and value full of newlines cannot push the warning out of the confirmation`() {
        val context = context(approve = false)
        val flood = "\n\r".repeat(200)

        SetAppPreferenceTool().execute(
            args("file" to PREFS, "key" to "injected$flood", "type" to "string", "value" to "value$flood\tend"),
            context,
        )

        val summary = context.confirmationSummaries.single()
        assertFalse(summary.contains('\n') || summary.contains('\r'), summary)
        assertTrue(summary.contains("injected\\n\\r"), summary)
        assertTrue(summary.endsWith("stops the app."), summary)
        assertTrue(summary.length < 400, "a flood must be cut, not shown whole: ${summary.length} chars")
    }

    @Test
    fun `an impossible change is refused before the developer is asked`() {
        val context = context()

        val missingKey = DeleteAppPreferenceTool().execute(args("file" to PREFS, "key" to "no_such_key"), context)
        val wrongType = SetAppPreferenceTool().execute(
            args("file" to PREFS, "key" to "ratio", "type" to "double", "value" to "0.5"),
            context,
        )

        assertTrue(missingKey.isError && missingKey.text().contains("not in this file"), missingKey.text())
        assertTrue(wrongType.isError && wrongType.text().contains("cannot store a double"), wrongType.text())
        assertTrue(context.confirmations.isEmpty(), "nothing impossible should reach the dialog")
    }

    @Test
    fun `encrypted preferences are never offered for editing`() {
        storage.files[PREFS] = """
            <map>
                <string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">x</string>
                <string name="AZk2">AUZ9</string>
            </map>
        """.trimIndent().toByteArray()
        val context = context()

        val result = SetAppPreferenceTool().execute(
            args("file" to PREFS, "key" to "AZk2", "type" to "string", "value" to "plain"),
            context,
        )

        assertTrue(result.isError && result.text().contains("read-only"), result.text())
        assertTrue(context.confirmations.isEmpty())
    }

    private companion object {
        const val PREFS = "shared_prefs/settings.xml"
        const val DATASTORE = "files/datastore/settings.preferences_pb"
    }
}
