package spock.adb.models

class FragmentData(
    val fragment: String,
    var innerFragments: MutableList<FragmentData> = mutableListOf(),
) {
    /** This fragment and every fragment nested in it, each parent before its children. */
    fun flatten(depth: Int = 0): List<FragmentRow> =
        listOf(FragmentRow(fragment, depth)) + innerFragments.flatMap { it.flatten(depth + 1) }
}

/**
 * One line of the Fragments popup. The class name is kept apart from the indentation that shows
 * nesting: the label used to be the lookup key, so a fragment two levels deep was searched for
 * with a tab in front of its name and never found.
 */
data class FragmentRow(val fragment: String, val depth: Int)
