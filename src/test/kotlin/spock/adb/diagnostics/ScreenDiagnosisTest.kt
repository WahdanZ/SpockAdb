package spock.adb.diagnostics

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.premission.ListItem

/**
 * What the Diagnose tab shows is read back from the report, so these pin the reading: a person
 * sees the problems in the collector's order, one line per section, and every failure.
 */
class ScreenDiagnosisTest {

    private val report = JsonParser.parseString(
        """
        {
          "schemaVersion": 2,
          "likelyProblems": [
            {"type": "crash", "severity": "error", "summary": "FATAL EXCEPTION in com.example.app"},
            {"type": "log", "severity": "warning", "summary": "Price cache miss", "count": 25}
          ],
          "moreProblems": 3,
          "screen": {
            "activity": "CheckoutActivity",
            "component": "com.example.app/com.example.app.CheckoutActivity",
            "appInForeground": true,
            "activityStack": ["CheckoutActivity", "MainActivity"],
            "fragments": ["PaymentFragment", "  CouponFragment"]
          },
          "app": {"packageName": "com.example.app", "running": true, "pids": ["1234"]},
          "permissions": {"runtime": 3, "granted": 2, "denied": ["CAMERA"]},
          "deviceConditions": {"dozing": false, "standbyBucket": "active", "batteryLevel": 80, "charging": true},
          "sectionErrors": {"backgroundWork": "No app is known"}
        }
        """.trimIndent(),
    ).asJsonObject

    @Test
    fun `problems keep the collector's order and their counts`() {
        val diagnosis = ScreenDiagnosis(report)

        assertEquals(listOf("error", "warning"), diagnosis.problems.map { it.severity })
        assertEquals(25, diagnosis.problems[1].count)
        assertEquals(3, diagnosis.moreProblems)
    }

    @Test
    fun `the diagnosed activity can be opened by its full class name`() {
        val diagnosis = ScreenDiagnosis(report)

        assertEquals("com.example.app.CheckoutActivity", diagnosis.activityClass)
        assertTrue(diagnosis.hasFragments)
    }

    @Test
    fun `one line per section that came back, in report order`() {
        val lines = ScreenDiagnosis(report).facts
        val facts = lines.toMap()

        assertEquals(listOf("Screen", "Process", "Permissions", "Device"), lines.map { it.first })
        assertTrue(facts.getValue("Screen").contains("PaymentFragment"))
        assertTrue(facts.getValue("Screen").contains("CheckoutActivity, MainActivity"))
        assertEquals("running, pid 1234", facts.getValue("Process"))
        assertEquals("2 of 3 granted; denied: CAMERA", facts.getValue("Permissions"))
        assertEquals("not dozing, bucket active, battery 80%, charging", facts.getValue("Device"))
    }

    @Test
    fun `a failed section is listed, not dropped`() {
        assertEquals(listOf("backgroundWork" to "No app is known"), ScreenDiagnosis(report).sectionErrors)
    }

    @Test
    fun `nothing resumed is said plainly, and there is nothing to open`() {
        val empty = JsonParser.parseString("""{"likelyProblems": [], "screen": {"activity": null}}""").asJsonObject
        val diagnosis = ScreenDiagnosis(empty)

        assertNull(diagnosis.activityClass)
        assertFalse(diagnosis.hasFragments)
        assertEquals("No activity is resumed", diagnosis.facts.toMap().getValue("Screen"))
    }

    @Test
    fun `copy for AI carries the whole report as JSON`() {
        val copied = ScreenDiagnosis(report).forAi()

        assertTrue(copied.contains("```json"))
        assertTrue(copied.contains("\"CheckoutActivity\""))
    }

    @Test
    fun `denied permissions are information, not faults`() {
        val section = PermissionsSection.summarise(
            listOf(ListItem("android.permission.CAMERA", false), ListItem("android.permission.RECORD_AUDIO", true)),
        )

        assertEquals(1, section.data["granted"].asInt)
        assertEquals("CAMERA", section.data["denied"].asJsonArray.single().asString)
        assertEquals(LikelyProblem.Severity.INFO, section.problems.single().severity)
    }

    @Test
    fun `all permissions granted is no problem at all`() {
        val section = PermissionsSection.summarise(listOf(ListItem("android.permission.CAMERA", true)))

        assertTrue(section.problems.isEmpty())
    }
}
