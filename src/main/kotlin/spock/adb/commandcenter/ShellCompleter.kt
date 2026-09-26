package spock.adb.commandcenter

/**
 * What to offer while an `adb shell` command is being typed: the subcommands, flags and
 * package names that can come next, each with what it does.
 *
 * Pure and free of IDE types, so which suggestion appears where can be tested; the popup that
 * shows them is [CommandCompletionPopup].
 */
class ShellCompleter(
    private val roots: List<ShellCommandDoc> = ShellCommandCatalog.roots,
) {

    enum class Kind { COMMAND, FLAG, PACKAGE }

    data class Suggestion(val text: String, val usage: String, val summary: String, val kind: Kind)

    /**
     * The suggestions for the word at the caret.
     *
     * [replaceFrom] is where that word starts; [context] is the command being typed, whose
     * documentation is worth showing even when nothing is selected.
     */
    data class Result(
        val replaceFrom: Int,
        val prefix: String,
        val suggestions: List<Suggestion>,
        val context: ShellCommandDoc?,
    )

    /** Where the walk over the typed words ended up. */
    private class Position(
        var options: List<ShellCommandDoc>,
        var context: ShellCommandDoc? = null,
        var packageExpected: Boolean = false,
        /** An argument was typed, so a subcommand can no longer follow — only flags. */
        var argumentsStarted: Boolean = false,
        val usedFlags: MutableSet<String> = mutableSetOf(),
    )

    fun complete(line: String, caret: Int = line.length, packages: List<String> = emptyList()): Result {
        val before = line.substring(0, caret.coerceIn(0, line.length))
        // Only the command after the last `;`, `|` or `&` is being typed: `ps -A | grep` completes grep.
        val segmentStart = before.indexOfLast { it in SEPARATORS } + 1
        val wordStart = maxOf(segmentStart, before.indexOfLast(Char::isWhitespace) + 1)
        val prefix = before.substring(wordStart)
        val words = before.substring(segmentStart, wordStart).split(WHITESPACE).filter(String::isNotEmpty)

        val position = walk(words)
        return Result(wordStart, prefix, suggest(position, prefix, packages), position.context)
    }

    /** The line with [suggestion] in place of the word at the caret, and where the caret goes. */
    fun accept(line: String, result: Result, suggestion: Suggestion, caret: Int = line.length): Pair<String, Int> {
        val after = line.substring(caret.coerceIn(result.replaceFrom, line.length))
        // A property prefix such as `log.tag.` is finished by the tag typed next, not by a space.
        val space = if (after.startsWith(" ") || suggestion.text.endsWith(".")) "" else " "
        val inserted = suggestion.text + space
        return line.substring(0, result.replaceFrom) + inserted + after to result.replaceFrom + inserted.length
    }

    private fun walk(words: List<String>): Position {
        val position = Position(roots)
        for (word in words) {
            val match = position.options.firstOrNull { it.name == word }
            when {
                match == null && position.packageExpected -> position.packageExpected = false
                match == null -> position.argumentsStarted = true
                match.isFlag -> {
                    position.usedFlags += word
                    position.packageExpected = match.takesPackage
                }
                position.argumentsStarted -> Unit
                else -> {
                    position.context = match
                    position.options = match.children
                    position.packageExpected = match.takesPackage
                    position.usedFlags.clear()
                }
            }
        }
        return position
    }

    private fun suggest(position: Position, prefix: String, packages: List<String>): List<Suggestion> {
        val flagsOnly = prefix.startsWith("-")
        val packageSuggestions = if (position.packageExpected && !flagsOnly) {
            packages.filter { it.contains(prefix, ignoreCase = true) }
                .sortedBy { !it.startsWith(prefix, ignoreCase = true) }
                .map { Suggestion(it, it, "Installed package.", Kind.PACKAGE) }
        } else {
            emptyList()
        }

        val entries = position.options.filter { entry ->
            entry.name.startsWith(prefix, ignoreCase = true) &&
                if (entry.isFlag) entry.name !in position.usedFlags else !flagsOnly && !position.argumentsStarted
        }
        val (flags, commands) = entries.partition(ShellCommandDoc::isFlag)
        val docs = (commands.map { it to Kind.COMMAND } + flags.map { it to Kind.FLAG })
            .map { (entry, kind) -> Suggestion(entry.name, entry.usage, entry.summary, kind) }

        return (packageSuggestions + docs)
            // Nothing to complete once the word is typed in full; offering it again would make
            // Enter insert a space instead of running the command.
            .filterNot { it.text == prefix }
            .take(MAX_SUGGESTIONS)
    }

    private companion object {
        const val SEPARATORS = ";|&"
        const val MAX_SUGGESTIONS = 200
        val WHITESPACE = Regex("\\s+")
    }
}
