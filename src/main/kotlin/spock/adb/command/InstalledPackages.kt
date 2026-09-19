package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.util.concurrent.TimeUnit

/**
 * The apps a developer can pick from, and the order they are offered in.
 *
 * Third-party packages only: system apps are never debuggable builds, so `run-as` would refuse
 * every one of them, and there are hundreds.
 */
internal object InstalledPackages {

    const val LIST_COMMAND = "pm list packages -3"

    /**
     * `package:com.example` lines, as sorted package names.
     *
     * A line without the prefix is not a package: `pm` writes its errors and warnings to the
     * same stream, and one that happened to look like a component name would otherwise be
     * offered as an installed app.
     */
    fun parse(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(PACKAGE_PREFIX) }
        .map { it.removePrefix(PACKAGE_PREFIX).trim() }
        .filter { it.isNotEmpty() && runCatching { ShellQuote.requireValidComponent(it, "Package") }.isSuccess }
        .distinct()
        .sorted()
        .toList()

    private const val PACKAGE_PREFIX = "package:"

    /**
     * The open project's app first, when it is installed, so the app being worked on is the
     * default; everything else after it in name order.
     */
    fun choices(installed: List<String>, projectApp: String?): List<String> =
        if (projectApp != null && projectApp in installed) listOf(projectApp) + (installed - projectApp) else installed
}

private const val LIST_TIMEOUT_SECONDS = 15L

internal fun IDevice.installedPackages(): List<String> {
    val receiver = ShellOutputReceiver()
    executeShellCommand(InstalledPackages.LIST_COMMAND, receiver, LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return InstalledPackages.parse(receiver.toString())
}

/** Third-party packages installed on the device, sorted by name. */
class ListInstalledPackagesCommand : NoInputCommand<List<String>> {
    override fun execute(project: Project, device: IDevice): List<String> = device.installedPackages()
}
