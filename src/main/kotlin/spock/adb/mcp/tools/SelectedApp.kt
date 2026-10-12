package spock.adb.mcp.tools

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.ControlFlowException
import java.util.concurrent.CancellationException

/**
 * Where the app a tool acts on came from when the agent did not name it, in the order
 * [packageOrNull] tries them.
 *
 * @param label how a result names the source; null for an app the agent named itself.
 */
enum class PackageSource(val label: String?) {
    ARGUMENT(null),
    SELECTED("app selected in Spock"),
    PROJECT("open project's application ID"),
}

/** The app a tool acts on, and where it came from. */
data class ResolvedPackage(val name: String, val source: PackageSource) {

    /**
     * `package: com.example.app (app selected in Spock)`, or null when the agent named the app.
     * An agent that left `packageName` out has to be told which app it got: on a flavor project,
     * or with another app picked in the tool window, it is not always the one it assumed.
     */
    val note: String? get() = source.label?.let { "package: $name ($it)" }

    /**
     * [summary] for a confirmation dialog, saying the app was a default when it was. The [note]
     * only reaches the agent after the action; the developer approving it has to see beforehand
     * that the agent did not pick this app. In front and on the same line: a summary is one line,
     * and ends with its warning.
     */
    fun confirming(summary: String): String =
        source.label?.let { "$name is the $it; the agent did not name one. $summary" } ?: summary
}

/**
 * The app a call is about: the one asked for, else the one selected in Spock's tool window, else
 * the open project's. Null when none of the three names one.
 *
 * The selected app comes before the project's. On a flavor project the project's application ID
 * can name another app than the one the developer follows, and a Flutter project often resolves
 * none at all; the tool window's choice is what the developer is looking at. An attach for the
 * project's app would also close the Flutter session on the followed one.
 *
 * One resolver for every tool. Each used to pick its own default, so with no project app
 * `android_get_package_info` failed, `android_get_logcat` quietly read the whole device and
 * `android_get_debug_context` used the selected app.
 */
internal fun ToolContext.packageOrNull(arguments: JsonObject): ResolvedPackage? {
    arguments.optionalString("packageName")?.let { return ResolvedPackage(it, PackageSource.ARGUMENT) }
    selectedApp()?.let { return ResolvedPackage(it, PackageSource.SELECTED) }
    return projectApplicationId()?.let { ResolvedPackage(it, PackageSource.PROJECT) }
}

/**
 * [packageOrNull], for a tool that cannot act without an app.
 *
 * @throws IllegalStateException naming all three ways to give it one.
 */
internal fun ToolContext.resolvePackage(arguments: JsonObject): ResolvedPackage =
    packageOrNull(arguments) ?: throw IllegalStateException(NO_PACKAGE)

/**
 * Runs [block] on the resolved app and puts the app's [ResolvedPackage.note] first in what it
 * returns, error or not. Not inline, so an early exit has to be `return@withPackage` and cannot
 * skip the note.
 */
internal fun ToolContext.withPackage(arguments: JsonObject, block: (packageName: String) -> ToolResult): ToolResult =
    withResolvedPackage(arguments) { block(it.name) }

/**
 * [withPackage], for a tool that needs to know where the app came from: a destructive one says so
 * in its confirmation ([ResolvedPackage.confirming]).
 *
 * A failure thrown by [block] becomes an error result carrying the note. The dispatcher would have
 * made it an error anyway, just without the note, and an agent told "not installed" still has to
 * learn which app was meant. Interruption, cancellation and the IDE's control-flow exceptions
 * pass through unchanged.
 */
internal fun ToolContext.withResolvedPackage(
    arguments: JsonObject,
    block: (ResolvedPackage) -> ToolResult,
): ToolResult {
    val resolved = resolvePackage(arguments)
    val result = runCatching { block(resolved) }.getOrElse { failure ->
        if (passesThrough(failure)) throw failure
        ToolResult.error(failure.message ?: "${failure.javaClass.simpleName} while acting on ${resolved.name}.")
    }
    return result.noting(resolved.note)
}

/** What [withResolvedPackage] must not turn into a result: anything not an Exception, and stop signals. */
private fun passesThrough(failure: Throwable): Boolean =
    failure !is Exception ||
        failure is InterruptedException ||
        failure is CancellationException ||
        failure is ControlFlowException

/** [this] with [note] as its first line, or unchanged when there is none. */
internal fun ToolResult.noting(note: String?): ToolResult {
    if (note == null) return this
    val first = content.firstOrNull()
    val lead = if (first is ToolContent.Text) ToolContent.Text("$note\n${first.text}") else ToolContent.Text(note)
    return copy(content = listOf(lead) + content.drop(if (first is ToolContent.Text) 1 else 0))
}

/** How a `packageName` parameter says what it defaults to; see [packageOrNull]. */
internal const val PACKAGE_DEFAULT = "Defaults to the app selected in Spock, else the open project's."

/** As [PACKAGE_DEFAULT], for a tool that can also look at the whole device. */
internal const val PACKAGE_DEFAULT_OR_DEVICE =
    "Defaults to the app selected in Spock, else the open project's, else the whole device. " +
        "Pass an empty string for the whole device."

internal const val NO_PACKAGE =
    "No packageName was given, no app is selected in Spock's tool window, and no application ID " +
        "could be resolved from the open project. Pass packageName, select the app in Spock's tool " +
        "window, or open the Android project and let its Gradle sync finish."
