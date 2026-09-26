package spock.adb.commandcenter

/** One command, subcommand or flag the Command Center can complete, and what it does. */
data class ShellCommandDoc(
    val name: String,
    val usage: String,
    val summary: String,
    /** Whether a package name comes next, so the installed packages are offered there. */
    val takesPackage: Boolean = false,
    val children: List<ShellCommandDoc> = emptyList(),
) {
    val isFlag: Boolean get() = name.startsWith("-")
}

/**
 * The shell commands a developer reaches for, as a tree of subcommands and flags with a line
 * of documentation each.
 *
 * Kept in `shell-commands.txt` rather than in code: the list is data that grows one line at a
 * time, and a line there is easier to add and review than another builder call here.
 */
object ShellCommandCatalog {

    private const val RESOURCE = "/spock/adb/commandcenter/shell-commands.txt"
    private const val INDENT = 2
    private const val FIELDS = 3
    private const val PACKAGE_MARKER = " @package"

    val roots: List<ShellCommandDoc> by lazy {
        val text = ShellCommandCatalog::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Missing $RESOURCE")
        parse(text)
    }

    /** Parses the catalog format; throws on a malformed line so a bad edit fails the tests, not the IDE. */
    fun parse(text: String): List<ShellCommandDoc> {
        val roots = mutableListOf<Builder>()
        // The most recent entry at each depth: a line's parent is the entry one level up.
        val path = mutableListOf<Builder>()

        text.lines().forEachIndexed { index, raw ->
            if (raw.isBlank() || raw.trimStart().startsWith("#")) return@forEachIndexed
            val lineNumber = index + 1
            val indent = raw.length - raw.trimStart().length
            require(indent % INDENT == 0) { "Line $lineNumber: indent by $INDENT spaces" }
            val depth = indent / INDENT
            require(depth <= path.size) { "Line $lineNumber: indented deeper than its parent" }

            val fields = raw.trim().split(" | ", limit = FIELDS)
            require(fields.size == FIELDS) { "Line $lineNumber: expected `name | usage | summary`" }
            val (rawName, usage, summary) = fields.map(String::trim)
            val takesPackage = rawName.endsWith(PACKAGE_MARKER)
            val name = rawName.removeSuffix(PACKAGE_MARKER)
            require(name.isNotEmpty() && name.none(Char::isWhitespace)) { "Line $lineNumber: bad name '$rawName'" }

            val entry = Builder(name, usage, summary, takesPackage)
            while (path.size > depth) path.removeLast()
            (path.lastOrNull()?.children ?: roots).add(entry)
            path.add(entry)
        }
        return roots.map(Builder::build)
    }

    private class Builder(
        val name: String,
        val usage: String,
        val summary: String,
        val takesPackage: Boolean,
        val children: MutableList<Builder> = mutableListOf(),
    ) {
        fun build(): ShellCommandDoc =
            ShellCommandDoc(name, usage, summary, takesPackage, children.map(Builder::build))
    }
}
