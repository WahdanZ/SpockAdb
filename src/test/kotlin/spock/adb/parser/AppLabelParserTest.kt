package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether an app's name can be proven from what a device says about it.
 *
 * The fixtures are real output from an API 34 emulator, trimmed to the lines that matter. The
 * case worth guarding is the last one: `string/app_name` is a guess at which resource holds the
 * label, and a guess that lands on the wrong resource shows the developer another app's name.
 */
class AppLabelParserTest {

    private val resolveOutput = """
        ##com.example.myapplicationccc
        priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
        ActivityInfo:
          name=com.example.myapplicationccc.MainActivity
          packageName=com.example.myapplicationccc
          labelRes=0x7f110022 nonLocalizedLabel=null icon=0x0 banner=0x0
          enabled=true exported=true directBootAware=false
          ApplicationInfo:
            name=com.example.myapplicationccc.SampleApp
            packageName=com.example.myapplicationccc
            labelRes=0x7f110022 nonLocalizedLabel=null icon=0x7f0e0001 banner=0x0
            className=com.example.myapplicationccc.SampleApp
        ##com.wahdanz.kanban_board
        ActivityInfo:
          name=com.wahdanz.kanban_board.MainActivity
          ApplicationInfo:
            name=android.app.Application
            labelRes=0x0 nonLocalizedLabel=kanban_board icon=0x7f080000 banner=0x0
        ##com.android.settings
        ActivityInfo:
          labelRes=0x7f041226 nonLocalizedLabel=null icon=0x0 banner=0x0
          ApplicationInfo:
            labelRes=0x7f041225 nonLocalizedLabel=null icon=0x7f020245 banner=0x0
        ##com.google.android.apps.nexuslauncher
        No activity found
    """.trimIndent()

    private val lookupOutput = """
        ##com.example.myapplicationccc
        Resolution for 0x7f110022 com.example.myapplicationccc:string/app_name
        ${'\t'}For config - mcc310-mnc260-en-rUS-ldltr-sw393dp-port-440dpi-v34
        ${'\t'}Found initial: <empty> and /data/app/~~iIQ/base.apk
        Best matching is from default configuration of com.example.myapplicationccc
        My Application
        ##com.android.settings
        Error: failed to get the resource com.android.settings:string/app_name
    """.trimIndent()

    private val sources = AppLabelParser.parseLabelSources(resolveOutput)
    private val lookups = AppLabelParser.parseResourceLookups(lookupOutput)

    @Test
    fun `a label written into the manifest is read straight out of the dump`() {
        assertEquals(
            AppLabelParser.LabelSource.Literal("kanban_board"),
            sources["com.wahdanz.kanban_board"],
        )
    }

    @Test
    fun `a label held in a resource is reported as the resource it is held in`() {
        // 0x7f110022 — the ApplicationInfo one. The ActivityInfo label a few lines above it is
        // the activity's own, which is not what the popup names the task.
        assertEquals(
            AppLabelParser.LabelSource.Resource(0x7f110022),
            sources["com.example.myapplicationccc"],
        )
        assertEquals(
            AppLabelParser.LabelSource.Resource(0x7f041225),
            sources["com.android.settings"],
        )
    }

    @Test
    fun `a package the device could not resolve has no label source at all`() {
        assertTrue("com.google.android.apps.nexuslauncher" !in sources)
    }

    @Test
    fun `a lookup reports both the value and the resource it actually resolved`() {
        assertEquals(
            AppLabelParser.ResolvedResource(0x7f110022, "My Application"),
            lookups["com.example.myapplicationccc"],
        )
    }

    @Test
    fun `a lookup that failed is not a value`() {
        assertTrue("com.android.settings" !in lookups)
    }

    @Test
    fun `only the labels that were proven come out`() {
        assertEquals(
            mapOf(
                "com.example.myapplicationccc" to "My Application",
                "com.wahdanz.kanban_board" to "kanban_board",
            ),
            AppLabelParser.labels(sources, lookups),
        )
    }

    @Test
    fun `a resource that resolved is rejected when it is not the label resource`() {
        // com.google.android.apps.nexuslauncher really does answer this way: its `app_name` is
        // "Launcher3" while the name on screen is "Pixel Launcher". Taking the value without
        // checking the id would put the wrong app name on the task.
        val labelIsElsewhere = mapOf(
            "com.google.android.apps.nexuslauncher" to AppLabelParser.LabelSource.Resource(0x7f130067),
        )
        val resolved = mapOf(
            "com.google.android.apps.nexuslauncher" to AppLabelParser.ResolvedResource(0x7f130066, "Launcher3"),
        )

        assertTrue(AppLabelParser.labels(labelIsElsewhere, resolved).isEmpty())
    }

    @Test
    fun `output from a device that understood none of it yields nothing`() {
        assertTrue(AppLabelParser.parseLabelSources("").isEmpty())
        assertTrue(AppLabelParser.parseResourceLookups("").isEmpty())
        assertTrue(AppLabelParser.parseLabelSources("/system/bin/sh: cmd: not found").isEmpty())
    }
}
