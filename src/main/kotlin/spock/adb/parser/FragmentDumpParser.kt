package spock.adb.parser

import spock.adb.models.FragmentData

/**
 * Parses the output of `dumpsys activity top` into the tree of currently added fragments.
 *
 * Kept free of any IDE or ddmlib types so the parsing rules can be unit tested against
 * recorded dumpsys output from real devices.
 */
object FragmentDumpParser {

    private const val VISIBLE_HINT = "mUserVisibleHint="

    fun parse(dumpsys: String): List<FragmentData> {
        val bulkTaskDetails = dumpsys.substringAfterLast("TASK", "")

        return if (bulkTaskDetails.contains("NavHostFragment")) {
            parseNavHostLayout(bulkTaskDetails)
        } else {
            val addedFragments = parseAddedFragments(addedFragmentsSection(bulkTaskDetails))
            resolveFragmentTree(addedFragments, bulkTaskDetails)
        }
    }

    /**
     * Layouts driven by a Navigation `NavHostFragment` list their fragments in a third
     * "Added Fragments:" block rather than in the nested per-fragment blocks.
     */
    private fun parseNavHostLayout(bulkTaskDetails: String): List<FragmentData> {
        val parts = bulkTaskDetails.split("Added Fragments:")
        val section = parts.getOrNull(2) ?: return emptyList()
        return section
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("#") && !it.contains("BackStackEntry") }
            .map { FragmentData(it.split("{").first().split(" ").last()) }
            .distinct()
    }

    private fun addedFragmentsSection(bulkTaskDetails: String): String =
        bulkTaskDetails
            .substringAfterLast("Added Fragments:", "")
            .substringBeforeLast("FragmentManager misc state:", "")

    private fun parseAddedFragments(section: String): MutableList<FragmentData> =
        section
            .lines()
            .filter { it.isNotBlank() }
            .mapTo(mutableListOf()) { line ->
                FragmentData(
                    fragment = line.substringAfter(": ", "").substringBefore("{", ""),
                    fragmentIdentifier = line.substringAfter("{", "").substringBefore("}", ""),
                )
            }

    /**
     * Fills in visibility/parent metadata for each fragment and recurses into nested
     * child fragment managers, then drops everything that is not currently visible.
     */
    private fun resolveFragmentTree(
        addedFragments: MutableList<FragmentData>,
        bulkTaskDetails: String,
    ): List<FragmentData> {
        addedFragments.forEach { fragment ->
            val initDelimiter = "${fragment.fragment}{${fragment.fragmentIdentifier}}"
            val endDelimiter = "mParent=$initDelimiter"

            val fragmentStr = bulkTaskDetails
                .substringAfter(initDelimiter, "")
                .substringBefore(endDelimiter, "")

            if (fragmentStr.contains("{parent=null}")) {
                fragment.isNullParent = true
            }

            val visibleIndex = fragmentStr.indexOf(VISIBLE_HINT)
            if (visibleIndex >= 0) {
                fragment.isVisible = fragmentStr
                    .substring(visibleIndex + VISIBLE_HINT.length)
                    .startsWith("true")
            }

            fragment.innerFragments = parseAddedFragments(addedFragmentsSection(fragmentStr))
            if (fragment.innerFragments.isNotEmpty()) {
                resolveFragmentTree(fragment.innerFragments, bulkTaskDetails)
            }
        }

        addedFragments.removeAll { !it.isVisible || it.isNullParent }
        return addedFragments
    }
}
