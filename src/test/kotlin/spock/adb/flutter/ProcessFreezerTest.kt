package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Against the shape `dumpsys activity processes <package>` printed on API 34 (emulator-5554, 2026-10-03). */
class ProcessFreezerTest {

    private val dump = """
        ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
          All known processes:
          *APP* UID 10193 ProcessRecord{abaa866 8972:com.example.app/u0a193}
            user #0 uid=10193 gids={50193, 20193, 9997}
            mRequiredAbi=arm64-v8a instructionSet=null
            class=io.flutter.app.FlutterApplication
            dir=/data/app/~~x/com.example.app-y/base.apk publicDir=/data/app/~~x/com.example.app-y/base.apk
            curProcState=19 mRepProcState=19 pid=8972 starting=false
            lastActivityTime=-9h12m3s211ms lastPssTime=-1h0m33s lruIndex=6
            mCurSchedGroup=0 setSchedGroup=0 systemNoUi=false
            curAdj=955 setAdj=955 verifiedAdj=955 mCurCapability=----- mSetCapability=-----
            lastCompactTime=12345 lastCompactProfile=SOME
            isFreezeExempt=false isPendingFreeze=false isFrozen=true
          *APP* UID 10194 ProcessRecord{1c2d3e4 9001:com.example.other/u0a194}
            isFreezeExempt=false isPendingFreeze=false isFrozen=false
    """.trimIndent()

    @Test
    fun `a frozen process reads as frozen`() {
        assertEquals(true, ProcessFreezer.frozen(dump, setOf(8972)))
    }

    @Test
    fun `another process's state is not this one's`() {
        assertEquals(false, ProcessFreezer.frozen(dump, setOf(9001)))
        assertNull(ProcessFreezer.frozen(dump, setOf(4242)))
    }

    @Test
    fun `a dump with no freezer state does not say`() {
        val older = dump.replace(Regex("""\s*isFreezeExempt.*"""), "")

        assertNull(ProcessFreezer.frozen(older, setOf(8972)))
        assertNull(ProcessFreezer.frozen("", setOf(8972)))
    }

    @Test
    fun `the command asks for the app's processes only`() {
        assertEquals("dumpsys activity processes 'com.example.app'", ProcessFreezer.command("com.example.app"))
    }
}
