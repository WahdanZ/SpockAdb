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
     * documentation is worth showing even when nothing is selected. [exact] is whether the word
     * is already, in full, something valid here: a longer match may still be offered, but the
     * word is finished, so Enter runs the command rather than picking that match.
     */
    data class Result(
        val replaceFrom: Int,
        val prefix: String,
        val suggestions: List<Suggestion>,
        val context: ShellCommandDoc?,
        val exact: Boolean = false,
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

    /** The command being typed: where its last word starts, and the words before that. */
    private class Scan(val wordStart: Int, val words: List<String>)

    fun complete(line: String, caret: Int = line.length, packages: List<String> = emptyList()): Result {
        val before = line.substring(0, caret.coerceIn(0, line.length))
        val scan = scan(before)
        val prefix = before.substring(scan.wordStart)
        val position = walk(scan.words)

        val candidates = candidates(position, prefix, packages)
        val exact = candidates.any { it.text.equals(prefix, ignoreCase = true) }
        val suggestions = candidates
            // Nothing to complete once the word is typed in full; offering it again would make
            // Enter insert a space instead of running the command.
            .filterNot { it.text.equals(prefix, ignoreCase = true) }
            .take(MAX_SUGGESTIONS)
        return Result(scan.wordStart, prefix, suggestions, position.context, exact)
    }

    /** The line with [suggestion] in place of the word at the caret, and where the caret goes. */
    fun accept(line: String, result: Result, suggestion: Suggestion, caret: Int = line.length): Pair<String, Int> {
        // The whole word is replaced, including any of it after the caret, so none of it is left behind.
        val from = caret.coerceIn(result.replaceFrom, line.length)
        val wordEnd = (from until line.length).firstOrNull { line[it].isWhitespace() || line[it] in SEPARATORS }
            ?: line.length
        val after = line.substring(wordEnd)
        // A property prefix such as `log.tag.` is finished by the tag typed next, not by a space.
        val space = if (after.startsWith(" ") || suggestion.text.endsWith(".")) "" else " "
        val inserted = suggestion.text + space
        return line.substring(0, result.replaceFrom) + inserted + after to result.replaceFrom + inserted.length
    }

    /**
     * Splits what is before the caret the way the shell would. Only the command after the last
     * `;`, `|` or `&` is being typed (`ps -A | grep` completes grep), and those, like spaces,
     * count only outside quotes: `input text "a|b" ` is still one command.
     */
    private fun scan(before: String): Scan {
        var quote: Char? = null
        var wordStart = 0
        val words = mutableListOf<String>()
        before.forEachIndexed { index, char ->
            when {
                quote != null -> if (char == quote) quote = null
                char in QUOTES -> quote = char
                char in SEPARATORS -> {
                    words.clear()
                    wordStart = index + 1
                }
                char.isWhitespace() -> {
                    if (index > wordStart) words += before.substring(wordStart, index)
                    wordStart = index + 1
                }
            }
        }
        return Scan(wordStart, words)
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

    /** Everything valid at [position] that starts with [prefix], or for a package contains it. */
    private fun candidates(position: Position, prefix: String, packages: List<String>): List<Suggestion> {
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

        return packageSuggestions + docs
    }

    private companion object {
        const val SEPARATORS = ";|&"
        const val QUOTES = "\"'"
        const val MAX_SUGGESTIONS = 200
    }
}
