package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.context.SpockSelection

/**
 * The app selected in Spock's tool window, or null when none is. Not created here: a selection
 * nobody has made has nothing to say.
 */
internal fun ToolContext.selectedApp(): String? =
    project?.getServiceIfCreated(SpockSelection::class.java)?.snapshot?.app?.takeIf { it.isNotBlank() }

/**
 * The app a tool that attaches to a Flutter session is about: the one asked for, else [selected]
 * — the app selected in Spock, as Diagnose takes it — else the open project's. On a flavor
 * project the project's application ID can name another app than the one the developer follows,
 * and an attach for that one would close the session on the followed app.
 *
 * @throws IllegalStateException when none of the three names an app.
 */
internal fun ToolContext.resolveFollowedPackage(arguments: JsonObject, selected: String?): String =
    arguments.optionalString("packageName") ?: selected ?: resolvePackage(arguments)
