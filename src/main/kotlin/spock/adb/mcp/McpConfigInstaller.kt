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

    private const val GIT_IGNORE = ".gitignore"

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

    // ------------------------------------------------------------------ sharing

    /**
     * Whether the project already keeps [FILE_NAME] out of commits.
     *
     * This file is meant to be shared with a team, which is exactly the problem: stdio names
     * *this* machine's JDK, plugin jar and IDE config by absolute path, and the HTTP URL names
     * the port this IDE happens to be listening on, so a teammate who checks either one out gets
     * a server that cannot start. Knowing whether git already ignores it is the difference
     * between a warning worth showing and one that is noise.
     */
    fun isIgnored(projectPath: Path): Boolean {
        val gitignore = projectPath.resolve(GIT_IGNORE)
        if (!Files.exists(gitignore)) return false
        return runCatching { mentionsConfig(Files.readString(gitignore)) }.getOrDefault(false)
    }

    /**
     * The matching half of [isIgnored], separated so it can be tested without a filesystem.
     *
     * Deliberately literal: it answers "did someone already write this entry", not "would git
     * ignore this path", which only git can answer. A false negative costs an offer the
     * developer declines; pretending to reimplement gitignore semantics would cost more.
     */
    fun mentionsConfig(gitignore: String): Boolean =
        gitignore.lineSequence()
            .map { it.trim() }
            .any { it == FILE_NAME || it == "/$FILE_NAME" }

    /**
     * Adds [FILE_NAME] to the project's `.gitignore`, with a line saying why it is there.
     *
     * Appends rather than rewrites, and creates the file when there is none. The comment matters:
     * an unexplained entry in a shared `.gitignore` is the kind of thing someone deletes a year
     * later because nobody remembers what put it there.
     */
    fun ignoreConfig(projectPath: Path): Path {
        val gitignore = projectPath.resolve(GIT_IGNORE)
        val existing = if (Files.exists(gitignore)) Files.readString(gitignore) else ""
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"

        Files.writeString(gitignore, existing + separator + IGNORE_BLOCK)
        return gitignore
    }

    private const val IGNORE_BLOCK =
        "\n# Written by Spock ADB. The entry in it only works on this machine: stdio names this\n" +
            "# JDK, plugin jar and IDE config by absolute path, and the HTTP URL names the port\n" +
            "# this IDE happens to be listening on.\n" +
            "$FILE_NAME\n"
}
