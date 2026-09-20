package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.parser.AppLabelParser
import java.util.concurrent.TimeUnit

/**
 * The display label of each of the given packages, for the ones a device can prove one for.
 *
 * Best effort by design: a package with no provable label is simply absent from the result, and
 * the caller shows its package name. See [AppLabelParser] for why proving it takes two steps.
 *
 * Both steps run as one batched shell loop rather than a call per package, because the popup
 * this feeds opens on a click and a round trip per app in the back stack is felt.
 */
class GetAppLabelsCommand : Command<List<String>, Map<String, String>> {

    override fun execute(p: List<String>, project: Project, device: IDevice): Map<String, String> {
        val packages = p.distinct()
            .filter { runCatching { ShellQuote.requireValidComponent(it, "Package") }.isSuccess }
        if (packages.isEmpty()) return emptyMap()

        val sources = AppLabelParser.parseLabelSources(device.run(loop(packages, RESOLVE)))
        val unresolved = sources
            .filterValues { it is AppLabelParser.LabelSource.Resource }
            .keys
            .toList()
        val lookups = when {
            unresolved.isEmpty() -> emptyMap()
            else -> AppLabelParser.parseResourceLookups(device.run(loop(unresolved, LOOKUP)))
        }
        return AppLabelParser.labels(sources, lookups)
    }

    private fun IDevice.run(command: String): String {
        val receiver = ShellOutputReceiver()
        executeShellCommand(command, receiver, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return receiver.toString()
    }

    /**
     * `for p in 'com.a' 'com.b'; do echo "##$p"; <body>; done`
     *
     * Each package is single-quoted, so nothing in it can reach the shell as syntax, and the
     * marker line lets the parser tell one package's output from the next.
     */
    private fun loop(packages: List<String>, body: String): String =
        packages.joinToString(" ") { ShellQuote.quote(it) }
            .let { quoted ->
                "for p in $quoted; do echo \"${AppLabelParser.MARKER}\$p\"; $body 2>/dev/null; done"
            }

    private companion object {
        const val TIMEOUT_SECONDS = 15L

        /** Dumps the app's `ApplicationInfo`, which names the label or the resource holding it. */
        const val RESOLVE = "pm resolve-activity \"\$p\""

        /**
         * Resolves the app's own `string/app_name`, and — because `--verbose` names the id it
         * resolved — lets the caller check that it really is the label resource.
         */
        const val LOOKUP = "cmd overlay lookup --verbose \"\$p\" \"\$p:string/app_name\""
    }
}
