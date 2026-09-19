package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The part of `run-as` handling every caller depends on: telling a refusal from a script
 * that ran, and a script that ran and failed from one that succeeded.
 */
class RunAsTest {

    @Test
    fun `both the package and the script are quoted`() {
        assertEquals(
            "run-as 'com.evil'\\''; reboot' sh -c 'cat '\\''a b'\\''; echo rc=$?'",
            RunAs.command("com.evil'; reboot", "cat 'a b'; echo rc=$?"),
        )
    }

    @Test
    fun `rc=0 succeeds and hands back what came before it`() {
        assertEquals(
            RunAsOutcome.Succeeded(listOf("shared_prefs/a.xml", "files/datastore/b.preferences_pb")),
            RunAs.classify("shared_prefs/a.xml\r\nfiles/datastore/b.preferences_pb\r\nrc=0\r\n"),
        )
    }

    @Test
    fun `a status line means the script ran, whatever it said`() {
        // Not "run-as could not reach the app": the shell ran and base64 is what is missing.
        assertEquals(
            RunAsOutcome.Failed(127, "sh: base64: not found"),
            RunAs.classify("sh: base64: not found\nrc=127"),
        )
    }

    @Test
    fun `no status line is a refusal, classified when it is recognisable`() {
        assertEquals(
            RunAsOutcome.NotDebuggable("run-as: package not debuggable: com.example"),
            RunAs.classify("run-as: package not debuggable: com.example"),
        )
        assertEquals(
            RunAsOutcome.Unreachable("run-as: unknown package: com.example"),
            RunAs.classify("run-as: unknown package: com.example"),
        )
        assertEquals(
            RunAsOutcome.Failed(null, "run-as: Operation not permitted"),
            RunAs.classify("run-as: Operation not permitted"),
        )
        assertEquals(RunAsOutcome.Failed(null, ""), RunAs.classify(" \n"))
    }

    @Test
    fun `payload lines keep the spaces they came with, and only lose adb's carriage return`() {
        // AppStoragePaths allows a space in a file name, and listAppStorage reads these lines
        // as paths: a trimmed one names a file that does not exist.
        assertEquals(
            RunAsOutcome.Succeeded(listOf("shared_prefs/ spaced .xml", "shared_prefs/plain.xml")),
            RunAs.classify("shared_prefs/ spaced .xml\r\nshared_prefs/plain.xml\r\nrc=0\r\n"),
        )
    }

    @Test
    fun `the status line is recognised even when the device pads it`() {
        assertEquals(RunAsOutcome.Succeeded(listOf("payload")), RunAs.classify("payload\n  rc=0  \n"))
    }

    @Test
    fun `a status line that is not last is not ours`() {
        assertEquals(
            RunAsOutcome.Unreachable("rc=0\nrun-as: unknown package"),
            RunAs.classify("rc=0\nrun-as: unknown package"),
        )
    }
}
