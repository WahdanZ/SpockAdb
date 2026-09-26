package spock.adb.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.logcat.LogLevel
import spock.adb.logcat.LogcatEntry

class LogcatTimelineClassifierTest {

    private val app = "spock.adb.sample"
    private val appPid = 4242
    private val systemPid = 551

    private fun entry(
        tag: String,
        message: String,
        pid: Int = appPid,
        level: LogLevel = LogLevel.INFO,
        tid: Int = pid,
        stamp: String = "09-26 14:00:01.500",
    ) = LogcatEntry(stamp, pid, tid, level, tag, message, "$stamp $pid $tid ${level.code} $tag: $message")

    private fun classifier(pids: Set<Int> = setOf(appPid)) = LogcatTimelineClassifier(app, pids, "emulator-5554")

    /** Everything [entries] produce, including what is still held at the end. */
    private fun LogcatTimelineClassifier.all(vararg entries: LogcatEntry): List<TimelineEvent> =
        entries.flatMapIndexed { index, entry -> accept(entry, index.toLong()) } + flush()

    @Test
    fun `the app's activity lifecycle, from the event log`() {
        val events = classifier().all(
            entry("wm_on_create_called", "[123,spock.adb.sample.MainActivity,performCreate,12]"),
            entry("wm_on_resume_called", "[123,spock.adb.sample.MainActivity,RESUME_ACTIVITY,3]"),
            entry("am_on_paused_called", "[0,spock.adb.sample.MainActivity,userLeaving]"),
        )

        assertEquals(
            listOf("MainActivity created", "MainActivity resumed", "MainActivity paused"),
            events.map { it.title },
        )
        assertTrue(events.all { it.category == TimelineCategory.ACTIVITY })
        assertEquals("spock.adb.sample.MainActivity", events[0].detail)
        assertEquals("09-26 14:00:01.500", events[0].deviceTime)
    }

    @Test
    fun `another app's activities are not ours`() {
        val events = classifier().all(
            entry(
                "wm_on_resume_called",
                "[9,com.google.android.apps.nexuslauncher.NexusLauncherActivity,RESUME]",
                pid = 900
            ),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a process start is tracked, and its warnings then count`() {
        val classifier = classifier(pids = emptySet())
        val events = classifier.all(
            entry("Sample", "before the app started", pid = 5000, level = LogLevel.WARN),
            entry("ActivityManager", "Start proc 5000:spock.adb.sample/u0a188 for activity", pid = systemPid),
            entry("Sample", "after it started", pid = 5000, level = LogLevel.WARN, stamp = "09-26 14:00:02.000"),
        )

        assertEquals(listOf("Process started (pid 5000)", "Sample: after it started"), events.map { it.title })
        assertEquals(TimelineCategory.APP_LIFECYCLE, events[0].category)
        assertEquals(TimelineSeverity.WARNING, events[1].severity)
    }

    @Test
    fun `a process death is a warning, and only once`() {
        val events = classifier().all(
            entry("ActivityManager", "Process spock.adb.sample (pid 4242) has died: fg TOP", pid = systemPid),
            entry("ActivityManager", "Process spock.adb.sample (pid 4242) has died: fg TOP", pid = systemPid),
        )

        assertEquals(listOf("Process died (pid 4242)"), events.map { it.title })
        assertEquals(TimelineSeverity.WARNING, events.single().severity)
    }

    @Test
    fun `a force-stop is a death too, as an emulator on API 34 logs it`() {
        val events = classifier().all(
            entry(
                "ActivityManager",
                "Killing 4242:spock.adb.sample/u0a182 (adj 900): stop spock.adb.sample due to from pid 16919",
                pid = systemPid,
            ),
            entry("ActivityManager", "Killing 4243:com.other.app/u0a1 (adj 900): stop com.other.app", pid = systemPid),
        )

        assertEquals(listOf("Process died (pid 4242)"), events.map { it.title })
    }

    @Test
    fun `a stack trace is one event with every frame in the detail`() {
        val stamp = "09-26 14:00:03.000"
        val events = classifier().all(
            entry("AndroidRuntime", "FATAL EXCEPTION: main", level = LogLevel.ERROR, stamp = stamp),
            entry("AndroidRuntime", "java.lang.IllegalStateException: boom", level = LogLevel.ERROR, stamp = stamp),
            entry(
                "AndroidRuntime",
                "\tat spock.adb.sample.LogcatActivity.onClick(LogcatActivity.kt:42)",
                level = LogLevel.ERROR,
                stamp = stamp
            ),
            entry("Other", "a different call", level = LogLevel.ERROR, stamp = stamp),
        )

        assertEquals(2, events.size)
        assertEquals("AndroidRuntime: FATAL EXCEPTION: main", events[0].title)
        assertTrue(events[0].detail.contains("IllegalStateException: boom"))
        assertTrue(events[0].detail.contains("LogcatActivity.kt:42"))
        assertEquals(TimelineSeverity.ERROR, events[0].severity)
        assertEquals("Other: a different call", events[1].title)
    }

    @Test
    fun `a log event is held until something else arrives or it is flushed`() {
        val classifier = classifier()

        assertTrue(classifier.accept(entry("Sample", "careful", level = LogLevel.WARN), 0).isEmpty())
        assertTrue(classifier.hasPending)
        assertEquals(listOf("Sample: careful"), classifier.flush().map { it.title })
    }

    @Test
    fun `info lines and other processes' warnings are not events`() {
        val events = classifier().all(
            entry("Sample", "just info", level = LogLevel.INFO),
            entry("SystemUi", "someone else's problem", pid = 900, level = LogLevel.ERROR),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a crash and an ANR from the event log are errors`() {
        val events = classifier().all(
            entry(
                "am_crash",
                "[4242,0,spock.adb.sample,952745542,java.lang.IllegalStateException," +
                    "Sample crash,LogcatActivity.kt,42,0]",
                pid = systemPid,
            ),
            entry("am_anr", "[0,4242,spock.adb.sample,952745542,Input dispatching timed out]", pid = systemPid),
            entry("am_crash", "[77,0,com.other.app,0,java.lang.RuntimeException,nope,A.kt,1,0]", pid = systemPid),
        )

        assertEquals(
            listOf("Crashed: IllegalStateException: Sample crash", "ANR: Input dispatching timed out"),
            events.map { it.title },
        )
        assertTrue(
            events.all { it.severity == TimelineSeverity.ERROR && it.category == TimelineCategory.APP_LIFECYCLE }
        )
    }
}
