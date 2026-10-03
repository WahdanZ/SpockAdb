package spock.adb.mcp

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.FlutterDiagnosticSource
import spock.adb.flutter.AppIdentity
import spock.adb.flutter.DeviceTime
import spock.adb.flutter.FlutterAttachOutcome
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterEventLog
import spock.adb.flutter.FlutterIsolate
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionSnapshot
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.IdentityCheck
import spock.adb.flutter.SessionState
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.mcp.tools.FlutterAppStatusTool
import java.time.ZoneOffset

class FlutterAppStatusToolTest {

    private val identity = AppIdentity(SERIAL, APP, 4242, IdentityCheck.DTD_PID_START)
    private val tool = FlutterAppStatusTool { _, _, _ -> null }

    @Test
    fun `every attach outcome is rendered in words`() {
        val outcomes = listOf(
            FlutterAttachOutcome.NotRunning(SERIAL, APP) to "not running",
            FlutterAttachOutcome.ReleaseBuild(SERIAL, APP) to "release build",
            FlutterAttachOutcome.NotReady("waiting for DDS", 1_000) to "not ready",
            FlutterAttachOutcome.NoDdsSession(identity, "running without a debugger session") to "without a debugger",
            FlutterAttachOutcome.NotFound("nothing named the VM") to "No Flutter session found",
            FlutterAttachOutcome.Failed("adb failed") to "Could not connect",
        )

        outcomes.forEach { (outcome, expected) ->
            val text = tool.render(FlutterDiagnosticSource(APP, FlutterBuild.DEBUG, outcome))
            assertTrue(text.contains(expected, ignoreCase = true), "$outcome -> $text")
            assertTrue(text.contains("connected: no"), text)
        }
    }

    @Test
    fun `a connected session reports identity isolate recording and clock`() {
        val session = FlutterSession()
        val isolate = FlutterIsolate(
            id = "isolates/1111",
            name = "main",
            extensionRpcs = setOf("ext.flutter.inspector.structuredErrors"),
            pauseKind = "Resume",
        )
        val snapshot = FlutterSessionSnapshot(
            state = SessionState.Connected(isolate.id),
            uiIsolate = isolate,
            connectionKind = ConnectionKind.DDS,
            vmPid = 4242,
            httpRecording = HttpRecording.EnabledBySpock,
        )
        val reads = object : FlutterDiagnosticSource.Reads {
            override fun refreshRate(): Double? = null
            override fun httpProfile(): JsonObject? = null
        }
        val source = FlutterDiagnosticSource(
            APP,
            FlutterBuild.DEBUG,
            FlutterAttachOutcome.Connected(session, identity, reused = true),
            live = FlutterDiagnosticSource.Live(
                identity,
                snapshot,
                FlutterBuild.DEBUG,
                FlutterEventLog.Contents(emptyList(), emptyList(), emptyList(), emptyMap()),
                DeviceTime(0, 25, ZoneOffset.ofHours(2)),
                reads,
            ),
        )

        val text = tool.render(source)

        assertTrue(text.contains("connected: yes"), text)
        assertTrue(text.contains("dtd+pid+start (pid 4242)"), text)
        assertTrue(text.contains("connectionKind: dds"), text)
        assertTrue(text.contains("build: debug"), text)
        assertTrue(text.contains("isolates/1111 (main)"), text)
        assertTrue(text.contains("httpRecording: on:"), text)
        assertTrue(text.contains("uncertainty ±25 ms"), text)
        assertTrue(text.contains("dartVersion: unknown"), text)
        assertTrue(text.contains("flutterVersion: unknown"), text)
    }

    @Test
    fun `a token or VM Service address in an outcome never reaches the answer`() {
        val secret = "SeCrEtToKeN1="
        val source = FlutterDiagnosticSource(
            APP,
            FlutterBuild.DEBUG,
            FlutterAttachOutcome.Failed("ws://127.0.0.1:12345/$secret/ws refused"),
        )

        val text = tool.render(source)

        assertFalse(text.contains(secret), text)
        assertFalse(text.contains("127.0.0.1:12345"), text)
    }

    private companion object {
        const val SERIAL = "emulator-5554"
        const val APP = "spock.adb.spock_flutter_sample"
    }
}
