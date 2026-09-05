package spock.adb.mcp.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import spock.adb.mcp.McpServerService
import spock.adb.notification.CommonNotifier
import java.awt.datatransfer.StringSelection

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

/** Copies a ready-to-paste client configuration, including the session token. */
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

        CopyPasteManager.getInstance().setContents(StringSelection(service.clientConfiguration()))
        CommonNotifier.showNotifier(
            project = project,
            content = "MCP client configuration copied. It contains an access token for your " +
                "devices — paste it into your MCP client config, and do not share it.",
        )
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
