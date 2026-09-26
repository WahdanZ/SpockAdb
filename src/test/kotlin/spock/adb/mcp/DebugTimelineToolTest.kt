package spock.adb.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.GetDebugTimelineTool
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolResult
import spock.adb.timeline.DebugTimeline
import spock.adb.timeline.TimelineCategory
import spock.adb.timeline.TimelineEvent
import spock.adb.timeline.TimelineSeverity

/** `android_get_debug_timeline`: what the IDE recorded, filtered, and honest about what it was recording. */
class DebugTimelineToolTest {

    private val now = 1_000_000L
    private val tool = GetDebugTimelineTool()

    private val timeline = DebugTimeline().apply {
        record(TimelineEvent(now - 600_000, TimelineCategory.SPOCK_ACTION, TimelineSeverity.INFO, "Long ago"))
        record(TimelineEvent(now - 20_000, TimelineCategory.SPOCK_ACTION, TimelineSeverity.INFO, "Restarted app"))
        record(
            TimelineEvent(
                now - 10_000,
                TimelineCategory.LOG,
                TimelineSeverity.ERROR,
                "AndroidRuntime: FATAL EXCEPTION: main",
                detail = "java.lang.IllegalStateException: boom",
            ),
        )
    }

    private fun ToolResult.text() = (content.single() as ToolContent.Text).text

    @Test
    fun `recent events come back oldest first with their detail`() {
        val result = tool.answer(timeline, "spock.adb.sample on Pixel 7", JsonObject(), now)
        val text = result.text()

        assertFalse(result.isError)
        assertTrue(text.startsWith("Device recording: spock.adb.sample on Pixel 7."))
        assertTrue(text.indexOf("Restarted app") < text.indexOf("FATAL EXCEPTION"))
        assertTrue(text.contains("IllegalStateException: boom"))
        assertFalse(text.contains("Long ago"))
    }

    @Test
    fun `category and severity filter, and details can be left out`() {
        val arguments = JsonObject().apply {
            add("categories", JsonArray().apply { add("log") })
            addProperty("minSeverity", "error")
            addProperty("includeDetails", false)
        }
        val text = tool.answer(timeline, "off — Record device events is switched off", arguments, now).text()

        assertTrue(text.contains("FATAL EXCEPTION"))
        assertFalse(text.contains("Restarted app"))
        assertFalse(text.contains("boom"))
        assertTrue(text.startsWith("Device recording: off"))
    }

    @Test
    fun `an unknown category is an error that lists the real ones`() {
        val arguments = JsonObject().apply { add("categories", JsonArray().apply { add("network") }) }
        val result = tool.answer(timeline, "off — Record device events is switched off", arguments, now)

        assertTrue(result.isError)
        assertTrue(result.text().contains("app_lifecycle"))
    }

    @Test
    fun `the limit keeps the most recent`() {
        val arguments = JsonObject().apply { addProperty("limit", 1) }
        val text = tool.answer(timeline, "off — Record device events is switched off", arguments, now).text()

        assertTrue(text.contains("FATAL EXCEPTION"))
        assertFalse(text.contains("Restarted app"))
        assertTrue(text.contains("the latest 1 shown"))
    }
}
