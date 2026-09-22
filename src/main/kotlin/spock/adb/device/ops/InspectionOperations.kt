package spock.adb.device.ops

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.apiLevel
import spock.adb.models.ActivityData
import spock.adb.models.BackStackData
import spock.adb.models.FragmentData
import spock.adb.parser.ActivityParser
import spock.adb.parser.AppLabelParser
import spock.adb.parser.ApplicationBackStackParser
import spock.adb.parser.BackStackParser
import spock.adb.parser.FragmentDumpParser
import java.util.concurrent.TimeUnit

/**
 * Reading what is on screen: the resumed activity, the back stack, and an app's fragments.
 *
 * The sibling of [AppOperations] for the read-only half of Phase 2. Same rule — Android
 * behaviour only. What it returns is parsed data, not text: the tool window opens a popup
 * that navigates to a `PsiClass`, an agent gets an indented list, and neither shape belongs
 * in the layer that reads the device.
 *
 * These already had one implementation, in the `Command` classes both paths call, so there
 * was no behavioural drift to remove. What there was instead is a dead dependency:
 * `Command.execute` takes a `Project` that not one of these reads, so `android_get_current_
 * activity` had to call `requireProject()` and carry a failure mode belonging to a parameter
 * it never used. Reading the screen does not need an open project, and now does not ask for
 * one. Testing it no longer needs an IDE either.
 *
 * One instance is one read, on one thread.
 */
class InspectionOperations(
    private val device: IDevice,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    /**
     * The fully qualified name of the activity on screen, or null when nothing is resumed —
     * a locked screen or the launcher.
     */
    fun currentActivity(): String? {
        // `mResumedActivity` was removed in Android 13; fall back to `topResumedActivity`.
        val resumed = shell("dumpsys activity activities | grep mResumedActivity")
        val output = resumed.ifEmpty {
            shell("dumpsys activity activities | grep topResumedActivity")
        }
        return ActivityParser.parseResumedActivity(output)
    }

    /** The activity back stack across every app, most recent task first. */
    fun activityStack(): List<BackStackData> {
        val apiLevel = device.apiLevel()
        return when {
            apiLevel != null && apiLevel < API_LEVEL_HONEYCOMB ->
                BackStackParser.parseLegacy(
                    shell("dumpsys activity activities | sed -En -e '/Running activities/,/Run #0/p'"),
                )
            else ->
                // `mResumedActivity` comes along with the stack so the popup can mark the task
                // the user is actually looking at; without it the top of the dump is only a
                // good guess, and it is wrong whenever a second display is involved.
                BackStackParser.parseHistory(
                    shell("dumpsys activity activities | grep -E 'Hist|mResumedActivity'"),
                )
        }
    }

    /** One app's own activities, each with the fragments it is holding. */
    fun applicationBackStack(packageName: String): List<ActivityData> =
        ApplicationBackStackParser.parse(shell("dumpsys activity ${ShellQuote.quote(packageName)}"))

    /**
     * The fragments [packageName] currently has added, nested as they are on screen.
     *
     * Dumps the app by name rather than `dumpsys activity top`, for two reasons. On Android 13
     * and later `top` reports no fragment state at all — the activity is there, its
     * FragmentManager is not — so Current fragment answered "no fragments" for every app that
     * had them. And `top` is whatever is in the foreground, which need not be the app chosen in
     * the tool window: with another app in front it reported that app's fragments, or nothing,
     * without saying so.
     */
    fun fragments(packageName: String): List<FragmentData> {
        ShellQuote.requireValidComponent(packageName, "Package name")
        return FragmentDumpParser.parse(shell("dumpsys activity ${ShellQuote.quote(packageName)}"))
    }

    /**
     * The display label of each given package, for the ones the device can prove one for.
     *
     * Best effort by design: a package with no provable label is simply absent from the
     * result, and the caller shows its package name. See [AppLabelParser] for why proving it
     * takes two steps.
     *
     * Both steps run as one batched shell loop rather than a call per package, because the
     * popup this feeds opens on a click and a round trip per app in the back stack is felt.
     */
    fun appLabels(packages: List<String>): Map<String, String> {
        val valid = packages.distinct()
            .filter { runCatching { ShellQuote.requireValidComponent(it, "Package") }.isSuccess }
        if (valid.isEmpty()) return emptyMap()

        val sources = AppLabelParser.parseLabelSources(shell(loop(valid, RESOLVE)))
        val unresolved = sources
            .filterValues { it is AppLabelParser.LabelSource.Resource }
            .keys
            .toList()
        val lookups = when {
            unresolved.isEmpty() -> emptyMap()
            else -> AppLabelParser.parseResourceLookups(shell(loop(unresolved, LOOKUP)))
        }
        return AppLabelParser.labels(sources, lookups)
    }

    private fun shell(command: String): String {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(command, receiver, timeoutSeconds, TimeUnit.SECONDS)
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

    companion object {
        /** Seconds to wait for one shell round trip. */
        const val DEFAULT_TIMEOUT_SECONDS = 15L

        /** `* Hist #n` lines were introduced in Honeycomb (API 11). */
        const val API_LEVEL_HONEYCOMB = 11

        /** Dumps the app's `ApplicationInfo`, which names the label or the resource holding it. */
        private const val RESOLVE = "pm resolve-activity \"\$p\""

        /**
         * Resolves the app's own `string/app_name`, and — because `--verbose` names the id it
         * resolved — lets the caller check that it really is the label resource.
         */
        private const val LOOKUP = "cmd overlay lookup --verbose \"\$p\" \"\$p:string/app_name\""
    }
}
