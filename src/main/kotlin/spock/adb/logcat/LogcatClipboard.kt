package spock.adb.logcat

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/**
 * Getting log lines out of the panel and into somewhere else.
 *
 * Its own file because "what text does this produce" is worth reading without the Swing wiring
 * around it, and because copy has two honest answers — the raw record, and the message a human
 * actually wants in a bug report — which is a decision, not plumbing.
 */
object LogcatClipboard {

    /** Exactly what the device sent, for a bug report or a `grep`. */
    fun rawText(entries: List<LogcatEntry>): String = entries.joinToString("\n") { it.raw }

    /**
     * The messages alone.
     *
     * Pasting a stack trace into an issue is the common case, and every line of it arriving
     * behind `10-04 12:34:56.789 3189 3189 E AndroidRuntime:` makes it unreadable and unusable
     * as a trace — IDEs stop linking the frames.
     */
    fun messageText(entries: List<LogcatEntry>): String = entries.joinToString("\n") { it.message }

    fun copy(text: String): Int {
        if (text.isEmpty()) return 0
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        return text.lines().size
    }

    /** @return what to show in the status bar: this is the only report the developer gets. */
    fun export(project: Project, entries: List<LogcatEntry>): String {
        // The two-argument constructor does not exist before 2025.1 — Plugin Verifier caught
        // it as a NoSuchMethodError risk on AI-231, AI-242 and IC-231. The vararg overload is
        // deprecated on newer platforms but present on all supported ones, and a deprecation
        // warning is strictly better than a crash. See docs/COMPATIBILITY.md.
        @Suppress("DEPRECATION")
        val descriptor = FileSaverDescriptor("Export Logcat", "Save the visible log lines", "txt", "log")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        // Disambiguate the overload: save(VirtualFile?, String) vs save(Path?, String).
        val target = dialog.save(null as java.nio.file.Path?, "logcat.txt") ?: return " "

        return runCatching {
            target.file.writeText(rawText(entries))
            "Exported ${entries.size} lines to ${target.file.name}."
        }.getOrElse { "Export failed: ${it.message}" }
    }
}
