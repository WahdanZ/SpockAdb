package spock.adb.mcp.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import spock.adb.mcp.McpClientConfig
import spock.adb.mcp.McpConfigInstaller
import spock.adb.mcp.McpServerService
import spock.adb.mcp.clientConfiguration
import spock.adb.mcp.preferredServerEntry
import spock.adb.mcp.stdioClientConfiguration
import spock.adb.notification.CommonNotifier
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/**
 * Reports the outcome of work that finished on a pooled thread.
 *
 * Starting and stopping the server both block — binding sockets, and waiting for live stdio
 * sessions to end — so they no longer run on the EDT and their results arrive on a
 * background thread. See [McpServerService.startAsync].
 */
private fun notifyLater(
    project: Project,
    content: String,
    type: NotificationType = NotificationType.INFORMATION,
) = ApplicationManager.getApplication().invokeLater(
    { CommonNotifier.showNotifier(project = project, content = content, type = type) },
) { project.isDisposed }

/**
 * Starts or stops the MCP server.
 *
 * Off by default and started explicitly: while it is running, any local process holding the
 * session token can drive a connected device.
 */
class ToggleMcpServerAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.text = when {
            McpServerService.getInstance().isRunning -> "Spock: Stop MCP Server"
            else -> "Spock: Start MCP Server for AI Agents"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = McpServerService.getInstance()

        if (service.isRunning) {
            service.stopAsync {
                notifyLater(
                    project = project,
                    content = "MCP server stopped. Connected AI agents can no longer reach your devices.",
                )
            }
            return
        }

        service.startAsync { result ->
            result
                .onSuccess { port ->
                    notifyLater(
                        project = project,
                        content = "MCP server listening on 127.0.0.1:$port. " +
                            "Use 'Spock: Copy MCP Client Configuration (stdio)' — or (HTTP) — to " +
                            "connect a client.",
                    )
                }
                .onFailure { error ->
                    notifyLater(
                        project = project,
                        content = "Could not start the MCP server: ${error.message}",
                        type = NotificationType.ERROR,
                    )
                }
        }
    }
}

/**
 * Copies the HTTP client configuration.
 *
 * Asks first which form: the default references `${McpClientConfig.TOKEN_ENV_VAR}` and carries
 * no credential, and the literal one — the only form this action used to produce — is behind a
 * warning shown *before* anything reaches the clipboard. A message that says "this contained a
 * token" after the copy tells the developer what they have already done.
 */
class CopyMcpConfigurationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = McpServerService.getInstance().isRunning
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = McpServerService.getInstance()

        if (!service.isRunning) {
            CommonNotifier.showNotifier(
                project = project,
                content = "Start the MCP server first.",
                type = NotificationType.WARNING,
            )
            return
        }

        val choice = Messages.showYesNoCancelDialog(
            project,
            "The configuration can read its token from the environment, or carry it in plain " +
                "text.\n\n" +
                "Anything holding that token can drive your connected device and read and write " +
                "files on this machine, so a snippet containing one must never go into a chat, " +
                "an issue or a shared dotfile. Choose the literal form only for a client that " +
                "cannot expand environment variables.",
            "Copy MCP Client Configuration",
            "Reads \$${McpClientConfig.TOKEN_ENV_VAR}",
            "Include Token",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (choice == Messages.CANCEL) return

        val includeToken = choice == Messages.NO
        CopyPasteManager.getInstance()
            .setContents(StringSelection(service.clientConfiguration(includeToken)))
        CommonNotifier.showNotifier(
            project = project,
            content = when {
                includeToken ->
                    "MCP client configuration copied. It contains a live access token for your " +
                        "devices — paste it into your client's config file, and rotate it if it " +
                        "goes anywhere else."
                else ->
                    "MCP client configuration copied. It contains no token: set " +
                        "${McpClientConfig.TOKEN_ENV_VAR} in the client's environment."
            },
            type = if (includeToken) NotificationType.WARNING else NotificationType.INFORMATION,
        )
    }
}

/**
 * Writes the configuration into the project's own client config file.
 *
 * The clipboard is where the accidents happen, and a config pasted into a chat never connected
 * anything anyway — a client only reads its own file. This writes that file, merging into
 * whatever servers are already configured there.
 */
class InstallMcpConfigurationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = McpServerService.getInstance().isRunning
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = McpServerService.getInstance()

        if (!service.isRunning) {
            CommonNotifier.showNotifier(
                project = project,
                content = "Start the MCP server first.",
                type = NotificationType.WARNING,
            )
            return
        }

        val basePath = project.basePath?.let { Path.of(it) } ?: run {
            CommonNotifier.showNotifier(
                project = project,
                content = "This project has no directory on disk to write " +
                    "${McpConfigInstaller.FILE_NAME} into.",
                type = NotificationType.WARNING,
            )
            return
        }

        val transport = if (service.prefersStdio) "stdio" else "HTTP"
        val confirmed = Messages.showYesNoDialog(
            project,
            "Write the $transport configuration to:\n" +
                "${basePath.resolve(McpConfigInstaller.FILE_NAME)}\n\n" +
                "Any other servers already in that file are kept, and the entry contains no " +
                "token.",
            "Install MCP Configuration",
            "Install",
            "Cancel",
            null,
        ) == Messages.YES
        if (!confirmed) return

        val entry = service.preferredServerEntry()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                McpConfigInstaller.install(basePath, entry).also {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it.file)
                }
            }
                .onSuccess {
                    notifyLater(
                        project = project,
                        content = "MCP configuration written to ${it.file}. Restart your MCP " +
                            "client to pick it up.",
                    )
                }
                .onFailure {
                    notifyLater(
                        project = project,
                        content = "Could not write ${McpConfigInstaller.FILE_NAME}: " +
                            "${it.message}. Nothing was changed.",
                        type = NotificationType.ERROR,
                    )
                }
        }
    }
}

/**
 * Invalidates the current token and issues a new one.
 *
 * [McpServerService.regenerateToken] had no caller anywhere in the plugin: the configuration
 * was documented as a credential, and there was no way to retire one that had got out short of
 * quitting the IDE and hand-editing `spock-adb-mcp.xml`.
 */
class RotateMcpTokenAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = McpServerService.getInstance()

        val restartNote = when {
            service.isRunning -> ", and the server restarts to pick the new token up."
            else -> "."
        }
        val confirmed = Messages.showYesNoDialog(
            project,
            "Generate a new session token?\n\n" +
                "Every client holding the current one stops working until it is " +
                "reconfigured$restartNote\n\n" +
                "Clients using the stdio configuration re-read the token file and need no " +
                "change.",
            "Rotate MCP Token",
            "Rotate",
            "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        // Rotation stops and starts the server, which blocks on sockets.
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { service.regenerateToken() }
                .onSuccess {
                    notifyLater(
                        project = project,
                        content = "MCP token rotated. stdio clients need no change; an HTTP " +
                            "client needs ${McpClientConfig.TOKEN_ENV_VAR} updated — the MCP " +
                            "panel's Rotate Token button can copy the new export line.",
                    )
                }
                .onFailure {
                    notifyLater(
                        project = project,
                        content = "Could not rotate the MCP token: ${it.message}",
                        type = NotificationType.ERROR,
                    )
                }
        }
    }
}

/**
 * Copies the stdio client configuration.
 *
 * Separate from the HTTP one because the two are not interchangeable: this one contains no
 * token, so the warning that belongs on the HTTP config would be a lie here, and a client
 * that speaks stdio cannot use a URL.
 */
class CopyMcpStdioConfigurationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = McpServerService.getInstance().isRunning
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = McpServerService.getInstance()

        if (!service.isRunning) {
            CommonNotifier.showNotifier(
                project = project,
                content = "Start the MCP server first.",
                type = NotificationType.WARNING,
            )
            return
        }
        if (service.stdioEndpoint == null) {
            CommonNotifier.showNotifier(
                project = project,
                content = "The stdio bridge could not start on this machine — see idea.log. " +
                    "Use 'Copy MCP Client Configuration (HTTP)' instead.",
                type = NotificationType.WARNING,
            )
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(service.stdioClientConfiguration()))
        CommonNotifier.showNotifier(
            project = project,
            content = "MCP stdio configuration copied. It contains no token — the client " +
                "reads one from a file only you can read.",
        )
    }
}

/** Stops then starts the server, which also re-reads the configured port. */
class RestartMcpServerAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = McpServerService.getInstance().isRunning
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = McpServerService.getInstance()

        // Chained, not issued together: the start must not begin until the stop has released
        // the sockets.
        service.stopAsync {
            service.startAsync { result ->
                result
                    .onSuccess {
                        notifyLater(project = project, content = "MCP server restarted on 127.0.0.1:$it.")
                    }
                    .onFailure {
                        notifyLater(
                            project = project,
                            content = "Could not restart the MCP server: ${it.message}",
                            type = NotificationType.ERROR,
                        )
                    }
            }
        }
    }
}
