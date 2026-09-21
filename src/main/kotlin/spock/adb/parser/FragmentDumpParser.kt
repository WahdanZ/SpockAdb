package spock.adb.parser

import spock.adb.models.FragmentData

/**
 * Parses `dumpsys activity <package>` into the tree of fragments the app has added.
 *
 * Reads only the androidx `Local FragmentActivity` section of the foreground activity, and walks
 * it by indentation: each FragmentManager lists its "Added Fragments:", each of those has a detail
 * block, and a detail block's "Child FragmentManager" holds the next level down.
 *
 * Earlier versions searched the whole dump for "Added Fragments:" blocks by position, which
 * picked up the framework's `ReportFragment` from the `Local Activity` section, the
 * `AutofillManager` dumpable (its `#0:` line looked like a fragment entry), and the fragments of
 * other activities in the task.
 *
 * Kept free of any IDE or ddmlib types so the parsing rules can be unit tested against
 * recorded dumpsys output from real devices.
 */
object FragmentDumpParser {

    private const val ACTIVITY = "ACTIVITY "
    private const val FRAGMENT_ACTIVITY = "Local FragmentActivity"
    private const val ADDED = "Added Fragments:"
    private const val CHILD_MANAGER = "Child FragmentManager"

    /** Containers that only host the real destinations; their children are reported instead. */
    private val HOSTS = setOf("NavHostFragment", "DynamicNavHostFragment")

    fun parse(dumpsys: String): List<FragmentData> {
        val activity = foregroundActivity(dumpsys.lines()) ?: return emptyList()
        val start = activity.indexOfFirst { it.trim().startsWith(FRAGMENT_ACTIVITY) }
        if (start < 0) return emptyList()
        return fragmentsOf(activity.subList(start + 1, activity.size))
    }

    /**
     * The block of the activity that is resumed, or — when the app is in the background — the
     * last one listed, which is the top of its task.
     */
    private fun foregroundActivity(lines: List<String>): List<String>? {
        val starts = lines.indices.filter { lines[it].trim().startsWith(ACTIVITY) }
        if (starts.isEmpty()) return null
        val blocks = starts.mapIndexed { i, start ->
            lines.subList(start, starts.getOrElse(i + 1) { lines.size })
        }
        return blocks.firstOrNull { block ->
            block.firstOrNull { "mResumed=" in it }?.contains("mResumed=true") == true
        } ?: blocks.last()
    }

    /** The added fragments of the FragmentManager whose dump is [region]. */
    private fun fragmentsOf(region: List<String>): List<FragmentData> {
        val added = region.indices
            .filter { region[it].trim() == ADDED }
            .minByOrNull { indentOf(region[it]) }
            ?: return emptyList()

        return childLines(region, added)
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .flatMap { entry -> resolve(entry.substringAfter(": "), region) }
    }

    /**
     * One "Added Fragments" entry — `DetailFragment{c98d3c1} (…)` on current androidx,
     * `HomeFragment{aaa111 #0 tag}` on older releases — resolved against its detail block.
     */
    private fun resolve(entry: String, region: List<String>): List<FragmentData> {
        val name = entry.substringBefore("{")
        val key = name + "{" + entry.substringAfter("{").takeWhile { it != '}' && it != ' ' }
        if (name.isBlank() || name.contains(' ')) return emptyList()

        val header = region.indexOfFirst { line ->
            val text = line.trim()
            text.startsWith(key) && text.getOrNull(key.length).let { it == '}' || it == ' ' }
        }
        val detail = if (header >= 0) childLines(region, header) else emptyList()
        val childManager = detail.indexOfFirst { it.trim().startsWith(CHILD_MANAGER) }
        val ownState = if (childManager >= 0) detail.subList(0, childManager) else detail
        if (ownState.any { "mHidden=true" in it }) return emptyList()

        val children = if (childManager >= 0) fragmentsOf(childLines(detail, childManager)) else emptyList()

        val simpleName = name.substringAfterLast('.')
        if (simpleName in HOSTS) return children
        return listOf(FragmentData(fragment = name, innerFragments = children.toMutableList()))
    }

    /** The lines after [index] indented deeper than it: the block that line introduces. */
    private fun childLines(lines: List<String>, index: Int): List<String> {
        val indent = indentOf(lines[index])
        val end = (index + 1 until lines.size)
            .firstOrNull { lines[it].isNotBlank() && indentOf(lines[it]) <= indent }
            ?: lines.size
        return lines.subList(index + 1, end)
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length
}
