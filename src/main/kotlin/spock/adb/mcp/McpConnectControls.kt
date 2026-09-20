package spock.adb.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * The "connect a client" controls: how the configuration leaves the IDE, and how a token that
 * has got out is retired.
 *
 * Its own component rather than more fields on [McpServerPanel], which is already at the size
 * the project's static analysis complains about — and because these two buttons are the part of
 * the panel with a security consequence, which is easier to reason about in one file.
 *
 * @param say writes to the panel's transient feedback line.
 * @param onServerChanged asks the panel to re-read the server's state after a rotation, which
 *  restarts it.
 */
class McpConnectControls(
    private val project: Project,
    private val service: McpServerService,
    private val say: (String) -> Unit,
    private val onServerChanged: () -> Unit,
) {

    val copyButton = JButton("Copy Config").apply { addActionListener { showConfigMenu() } }

    /**
     * Revocation, which the plugin had no way to do at all.
     *
     * [McpServerService.regenerateToken] had no caller anywhere: the configuration was
     * documented as a credential, and retiring one that had leaked meant quitting the IDE and
     * hand-editing `spock-adb-mcp.xml`. A credential nobody can revoke is one nobody rotates.
     */
    val rotateButton = JButton("Rotate Token").apply { addActionListener { rotateToken() } }

    /** Copying needs a port to point at; rotating does not. */
    fun refresh(running: Boolean) {
        copyButton.isEnabled = running
        // The token exists whether or not anything is listening, and one leaked by a server
        // that has since been stopped still needs revoking.
        rotateButton.isEnabled = true
    }

    /** Both go quiet while the server is starting, stopping or being rotated out from under. */
    fun setBusy(busy: Boolean) {
        copyButton.isEnabled = !busy
        rotateButton.isEnabled = !busy
    }

    // ------------------------------------------------------------------ copying

    /**
     * The ways to connect a client, safest first.
     *
     * This used to be a single button that copied the HTTP configuration with the token
     * embedded in it — the one form of the config that *is* a credential, behind the one control
     * a developer was most likely to press. The menu puts the transport that carries no token at
     * the top and makes the literal one an explicit, warned-about choice.
     */
    private fun showConfigMenu() {
        val menu = JPopupMenu()
        if (service.prefersStdio) {
            menu.add(item("Copy stdio config  —  no token") { copyStdioConfig() })
        }
        menu.add(item("Copy HTTP config  —  reads \$${McpClientConfig.TOKEN_ENV_VAR}") { copyHttpConfig() })
        menu.add(item("Copy HTTP config with token…") { copyHttpConfigWithToken() })
        menu.add(item("Copy the ${McpClientConfig.TOKEN_ENV_VAR} export line…") { copyExportLine() })
        menu.addSeparator()
        menu.add(item("Install into this project (${McpConfigInstaller.FILE_NAME})…") { installIntoProject() })
        menu.show(copyButton, 0, copyButton.height)
    }

    private fun item(text: String, action: () -> Unit) =
        JMenuItem(text).apply { addActionListener { action() } }

    private fun copyStdioConfig() {
        copy(service.stdioClientConfiguration())
        say("Copied — no token in it; the client reads one from a file only you can read.")
    }

    private fun copyHttpConfig() {
        copy(service.clientConfiguration())
        say("Copied — no token in it. Set ${McpClientConfig.TOKEN_ENV_VAR} in the client's environment.")
    }

    /**
     * The literal form, behind a warning that arrives *before* the clipboard is written.
     *
     * The old message said the snippet held a token once it had already been copied, which tells
     * a developer what they have just done rather than letting them decide not to do it.
     */
    private fun copyHttpConfigWithToken() {
        val confirmed = Messages.showYesNoDialog(
            project,
            "This snippet contains your session token in plain text.\n\n" +
                "Anything holding that token can drive your connected device and read and write " +
                "files on this machine. Paste it into your MCP client's configuration file — " +
                "never into a chat, an issue or a shared dotfile.\n\n" +
                "Prefer the stdio configuration, or the HTTP one that reads " +
                "${McpClientConfig.TOKEN_ENV_VAR} from the environment. If this one does leak, " +
                "use Rotate Token.",
            "Copy Configuration With Token",
            "Copy With Token",
            "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        copy(service.clientConfiguration(includeToken = true))
        say("Copied — this one is a live credential. Do not paste it into a chat.")
    }

    /**
     * The shell line that sets the variable the HTTP config reads.
     *
     * Without this the env-var config is a dead end: it names a variable, and nothing in the
     * plugin would say what to set it to — the only other source was [offerExportLine], which
     * runs after a rotation, so completing the setup meant invalidating every client you
     * already had. The line carries the token, so it is warned about exactly like the literal
     * config.
     */
    private fun copyExportLine() {
        val confirmed = Messages.showYesNoDialog(
            project,
            "This line contains your session token in plain text.\n\n" +
                "Anything holding that token can drive your connected device and read and write " +
                "files on this machine. It belongs in a shell profile or your client's " +
                "environment — never in a chat, an issue or a committed file.\n\n" +
                "If it does leak, use Rotate Token.",
            "Copy Token",
            "Copy Export Line",
            "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        copy(service.tokenExportLine())
        say("Copied — that line carries a live token. It belongs in a shell, not a chat.")
    }

    // ------------------------------------------------------------------ installing

    /**
     * Writes the configuration into the project instead of routing it through the clipboard.
     *
     * A config pasted into a chat window never connected anything in the first place: a client
     * reads its own file. Writing that file is both the safer route and the one that works.
     */
    private fun installIntoProject() {
        val basePath = project.basePath?.let { Path.of(it) } ?: run {
            say("This project has no directory on disk to write ${McpConfigInstaller.FILE_NAME} into.")
            return
        }

        val entry = chooseEntry(basePath.resolve(McpConfigInstaller.FILE_NAME)) ?: return

        // A small file, but still filesystem work — and `isIgnored` reads another one. The EDT
        // waits on disk no more happily than it waits on a socket.
        ApplicationManager.getApplication().executeOnPooledThread {
            val alreadyIgnored = runCatching { McpConfigInstaller.isIgnored(basePath) }
                .getOrDefault(true)
            val outcome = runCatching {
                McpConfigInstaller.install(basePath, entry).also {
                    // Without this the file exists on disk but not in the IDE, so the developer
                    // cannot open the thing they were just told was written.
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it.file)
                }
            }
            onEdt {
                reportInstall(outcome)
                // Both entries are machine-local, so the offer is not stdio's alone.
                if (outcome.isSuccess && !alreadyIgnored) offerToIgnore(basePath)
            }
        }
    }

    /**
     * Asks which transport to write, on the axis that actually decides it.
     *
     * An earlier version offered this as shareable-versus-not, with HTTP as the entry a team
     * could commit. That was wrong in three ways: the port is whatever the OS handed this
     * machine on first start, there is no setting anywhere to fix it, and the token the config
     * names lives in this machine's keychain. **Neither entry can leave this machine**, so the
     * only real question is what the client can do — spawn a process, or open a URL.
     *
     * Returns null when the developer cancels.
     */
    private fun chooseEntry(file: Path): JsonObject? {
        val shared = "Both entries only work on this machine: stdio names this JDK, plugin jar " +
            "and IDE config by absolute path, and the HTTP URL names the port this IDE happens " +
            "to be listening on. Keep the file out of a shared commit either way."

        if (!service.prefersStdio) {
            val confirmed = Messages.showYesNoDialog(
                project,
                "Write the HTTP configuration to:\n$file\n\n" +
                    "Any other servers already in that file are kept, and the entry contains no " +
                    "token — it reads ${McpClientConfig.TOKEN_ENV_VAR} from the client's " +
                    "environment, which you can set from Copy Config.\n\n$shared",
                "Install MCP Configuration",
                "Install",
                "Cancel",
                null,
            ) == Messages.YES
            return if (confirmed) service.httpServerEntry() else null
        }

        val choice = Messages.showYesNoCancelDialog(
            project,
            "Write to:\n$file\n\n" +
                "Any other servers already in that file are kept, and neither entry contains a " +
                "token.\n\n" +
                "stdio — for a client that spawns its server. Nothing else to set up.\n\n" +
                "HTTP — for a client that only opens a URL. It reads " +
                "${McpClientConfig.TOKEN_ENV_VAR} from the client's environment, so set that " +
                "too: Copy Config has the export line.\n\n$shared",
            "Install MCP Configuration",
            "stdio",
            "HTTP",
            "Cancel",
            null,
        )
        return when (choice) {
            Messages.YES -> service.stdioServerEntry()
            Messages.NO -> service.httpServerEntry()
            else -> null
        }
    }

    /**
     * Offers to keep a machine-specific config out of the team's commits.
     *
     * Only after a stdio install, and only when `.gitignore` does not already say so — an offer
     * that appears every time is one that gets clicked through.
     */
    private fun offerToIgnore(basePath: Path) {
        val wanted = Messages.showYesNoDialog(
            project,
            "That configuration only works on this machine — the paths and the port are this " +
                "IDE's. Add ${McpConfigInstaller.FILE_NAME} to this project's .gitignore, so it " +
                "is not committed for the team?\n\n" +
                "Teammates install their own from their own IDE.",
            "Install MCP Configuration",
            "Add to .gitignore",
            "Leave It",
            null,
        ) == Messages.YES
        if (!wanted) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                McpConfigInstaller.ignoreConfig(basePath).also {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
                }
            }
            onEdt {
                result
                    .onSuccess { say("Added ${McpConfigInstaller.FILE_NAME} to ${it.fileName}.") }
                    .onFailure { say("Could not update .gitignore: ${it.message}") }
            }
        }
    }

    private fun reportInstall(outcome: Result<McpConfigInstaller.Outcome>) {
        outcome
            .onSuccess {
                val verb = when {
                    it.created -> "Created"
                    it.replaced -> "Updated the ${McpClientConfig.SERVER_NAME} entry in"
                    else -> "Added ${McpClientConfig.SERVER_NAME} to"
                }
                say("$verb ${it.file.fileName} — restart your MCP client to pick it up.")
            }
            .onFailure {
                Messages.showErrorDialog(
                    project,
                    "Could not write ${McpConfigInstaller.FILE_NAME}: ${it.message}\n\n" +
                        "Nothing was changed. If that file is not valid JSON, or its " +
                        "\"mcpServers\" is not an object, fix it first — overwriting it " +
                        "would take your other servers with it.",
                    "Install MCP Configuration",
                )
            }
    }

    // ------------------------------------------------------------------ rotating

    private fun rotateToken() {
        val restartNote = when {
            service.isRunning -> ", and the server restarts to pick the new token up."
            else -> "."
        }
        val confirmed = Messages.showYesNoDialog(
            project,
            "Generate a new session token?\n\n" +
                "Every client holding the current one stops working until it is " +
                "reconfigured$restartNote\n\n" +
                "Clients using the stdio configuration re-read the token file and need no change.",
            "Rotate MCP Token",
            "Rotate",
            "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        // Rotation stops and starts the server, which blocks on sockets.
        setBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.regenerateToken() }
            onEdt {
                setBusy(false)
                onServerChanged()
                result
                    .onSuccess { offerExportLine() }
                    .onFailure {
                        Messages.showErrorDialog(
                            project,
                            "Could not rotate the MCP token: ${it.message}",
                            "Rotate MCP Token",
                        )
                    }
            }
        }
    }

    /**
     * Hands over the new secret once, at the only moment it is legitimately needed.
     *
     * Copying it automatically would put a live token on the clipboard of a developer who had
     * just asked to invalidate one.
     */
    private fun offerExportLine() {
        val wantsLine = Messages.showYesNoDialog(
            project,
            "New token generated. stdio clients need no change.\n\n" +
                "Copy the shell line that sets ${McpClientConfig.TOKEN_ENV_VAR} for an HTTP client?",
            "Rotate MCP Token",
            "Copy Export Line",
            "Done",
            null,
        ) == Messages.YES
        if (!wantsLine) return

        copy(service.tokenExportLine())
        say("Copied — that line carries the new token. It belongs in a shell, not a chat.")
    }

    // ------------------------------------------------------------------ plumbing

    private fun copy(value: String) =
        CopyPasteManager.getInstance().setContents(StringSelection(value))

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ block() }) { project.isDisposed }
}
