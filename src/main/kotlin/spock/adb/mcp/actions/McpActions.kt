package spock.adb.mcp.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import spock.adb.mcp.McpServerService
import spock.adb.notification.CommonNotifier
import java.awt.datatransfer.StringSelection

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
            service.stop()
            CommonNotifier.showNotifier(
                project = project,
                content = "MCP server stopped. Connected AI agents can no longer reach your devices.",
            )
            return
        }

        service.start()
            .onSuccess { port ->
                CommonNotifier.showNotifier(
                    project = project,
                    content = "MCP server listening on 127.0.0.1:$port. " +
                        "Use 'Spock: Copy MCP Client Configuration' to connect a client.",
                )
            }
            .onFailure { error ->
                CommonNotifier.showNotifier(
                    project = project,
                    content = "Could not start the MCP server: ${error.message}",
                    type = NotificationType.ERROR,
                )
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
