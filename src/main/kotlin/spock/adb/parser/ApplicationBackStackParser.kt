package spock.adb.parser

import spock.adb.models.ActivityData

/**
 * Parses `dumpsys activity <package>` output into the activity/fragment stack of one app.
 *
 * The dump is read one `ACTIVITY` block at a time, and each block's fragments are read with
 * [FragmentDumpParser]'s rules. The previous version looked for a fragment name on the same line
 * as `Active Fragments:`, which is a heading on a line of its own, so the popup never showed a
 * fragment; and it joined the component's package and class by deleting the `/`, which turned an
 * activity declared outside the app's package into `com.example.appcom.other.Activity`.
 */
object ApplicationBackStackParser {

    private const val ACTIVITY = "ACTIVITY "
    private val trueFlag = Regex("([A-Z])\\w+=true")

    /** The app's activities, most recent first. */
    fun parse(bulkActivitiesData: String): List<ActivityData> {
        val lines = bulkActivitiesData.lines()
        val starts = lines.indices.filter { lines[it].trim().startsWith(ACTIVITY) }
        return starts.mapIndexedNotNull { i, start ->
            val block = lines.subList(start, starts.getOrElse(i + 1) { lines.size })
            val component = block.first().trim().split(' ').firstOrNull { '/' in it }
                ?: return@mapIndexedNotNull null
            ActivityData(
                activity = className(component),
                fragments = FragmentDumpParser.fragmentsOfActivity(block),
                status = status(block),
            )
        }.reversed()
    }

    /**
     * `com.example.app/.Main` → `com.example.app.Main`; `com.example.app/com.other.Main` →
     * `com.other.Main`. The short form is only used when the class is in the package.
     */
    internal fun className(component: String): String {
        val pkg = component.substringBefore('/')
        val cls = component.substringAfter('/')
        return if (cls.startsWith('.')) pkg + cls else cls
    }

    /** The first flag set to true on the block's `mResumed=` line: `Resumed`, `Stopped`… */
    private fun status(block: List<String>): String = block
        .firstOrNull { "mResumed=" in it }
        ?.let { trueFlag.find(it)?.value?.substringBefore('=') }
        .orEmpty()
}
