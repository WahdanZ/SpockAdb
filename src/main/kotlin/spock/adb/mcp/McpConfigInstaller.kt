package spock.adb.mcp

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the client configuration into a project's own `.mcp.json`.
 *
 * The copy-and-paste route is where the credential accidents happen: the snippet goes to the
 * clipboard, the clipboard goes wherever the developer's next paste goes, and a config pasted
 * into a chat never connects anything anyway — a client only ever reads its own config file.
 * Writing that file directly removes the step rather than warning about it.
 */
object McpConfigInstaller {

    /** What Claude Code and several other clients read from a project directory. */
    const val FILE_NAME = ".mcp.json"

    /** What an install did, so the caller can say it without re-reading the file. */
    data class Outcome(
        val file: Path,
        /** True when the file did not exist before, so the message can mention creating it. */
        val created: Boolean,
        /** True when a previous `spock-adb` entry was updated rather than added. */
        val replaced: Boolean,
    )

    /**
     * Merges [server] into `<projectPath>/.mcp.json`, preserving every other server in it.
     *
     * Throws rather than overwriting when the file exists and is not JSON: a config a developer
     * hand-wrote and mistyped is still theirs, and replacing it wholesale would take their other
     * servers with it.
     */
    fun install(projectPath: Path, server: JsonObject): Outcome {
        val file = projectPath.resolve(FILE_NAME)
        val existing = if (Files.exists(file)) Files.readString(file) else null
        val merged = McpClientConfig.merge(existing, server)

        // A trailing newline: this file is very often committed, and a diff of one line with no
        // newline at the end is noise in every review that touches it afterwards.
        Files.writeString(file, merged + "\n")

        return Outcome(
            file = file,
            created = existing == null,
            replaced = McpClientConfig.contains(existing),
        )
    }
}
