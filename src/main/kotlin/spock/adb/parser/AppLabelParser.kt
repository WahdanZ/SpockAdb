package spock.adb.parser

/**
 * Reads an app's display label — "My Application" rather than `com.example.myapplication` — out
 * of what a device will tell you about a package.
 *
 * There is no shell command that simply prints it. `pm resolve-activity` dumps the app's
 * `ApplicationInfo`, which holds the label one of two ways: as a literal string, when the
 * manifest wrote one (`android:label="Digits Store"`), or as a resource id, when it points at a
 * string resource — which is the usual case and which only the app's own resources resolve.
 *
 * `cmd overlay lookup` resolves a resource for a package, but by name rather than by id, and the
 * name is not in the dump. `string/app_name` is what the Android Studio manifest template points
 * `android:label` at, so it is worth asking for — but it is a guess, and a wrong one shows the
 * developer another app's name: `com.google.android.apps.nexuslauncher` has an `app_name` of
 * "Launcher3" and a label of "Pixel Launcher". So the guess is only accepted when the resource
 * it resolved is the id the dump named. Anything unproven has no label at all, and the popup
 * shows the package on its own.
 */
object AppLabelParser {

    /** Separates one package's output from the next in a batched shell loop. */
    const val MARKER = "##"

    /** Where a package's label lives, according to its `ApplicationInfo`. */
    sealed interface LabelSource {
        /** The manifest wrote the label out, so the dump already holds it. */
        data class Literal(val label: String) : LabelSource

        /** The manifest pointed at a string resource, named here only by [id]. */
        data class Resource(val id: Long) : LabelSource
    }

    /** A resource `cmd overlay lookup` resolved, and the id it turned out to be. */
    data class ResolvedResource(val id: Long, val value: String)

    private const val APPLICATION_INFO = "ApplicationInfo:"
    private const val NO_LABEL_RESOURCE = 0L
    private const val NULL = "null"
    private const val RESOLUTION_PREFIX = "Resolution for "
    private const val BEST_MATCHING_PREFIX = "Best matching"

    private val labelLineRegex =
        Regex("""labelRes=(0x[0-9a-fA-F]+)\s+nonLocalizedLabel=(.*?)\s+icon=""")

    /** Reads `pm resolve-activity` output for each package in a batched run. */
    fun parseLabelSources(output: String): Map<String, LabelSource> =
        output.sections().mapNotNull { (appPackage, lines) ->
            // The activity's own label is dumped above the application's and is not the app's
            // name, so read only what follows the ApplicationInfo heading.
            val applicationInfo = lines.dropWhile { it.trim() != APPLICATION_INFO }
            val match = applicationInfo.firstNotNullOfOrNull { labelLineRegex.find(it) }
                ?: return@mapNotNull null
            val id = match.groupValues[1].removePrefix("0x").toLongOrNull(HEX) ?: return@mapNotNull null
            val literal = match.groupValues[2].trim()
            when {
                literal.isNotEmpty() && literal != NULL -> appPackage to LabelSource.Literal(literal)
                id != NO_LABEL_RESOURCE -> appPackage to LabelSource.Resource(id)
                else -> null
            }
        }.toMap()

    /**
     * Reads `cmd overlay lookup --verbose` output for each package in a batched run.
     *
     * The verbose form is what names the resource id it resolved; the plain form prints only the
     * value, which is exactly the half that cannot be trusted on its own. Between the two comes
     * a block of indented candidate lines and a "Best matching" line, and the value is what is
     * left after them.
     */
    fun parseResourceLookups(output: String): Map<String, ResolvedResource> =
        output.sections().mapNotNull { (appPackage, lines) ->
            val body = lines.filter { it.isNotBlank() }
            val header = body.firstOrNull()?.takeIf { it.startsWith(RESOLUTION_PREFIX) } ?: return@mapNotNull null
            val id = header.removePrefix(RESOLUTION_PREFIX).substringBefore(' ')
                .removePrefix("0x").toLongOrNull(HEX)
                ?: return@mapNotNull null
            val value = body.last()
                .takeIf { it !== header && !it.startsWith(BEST_MATCHING_PREFIX) && it.isNotBlank() }
                ?.trim()
                ?: return@mapNotNull null
            appPackage to ResolvedResource(id, value)
        }.toMap()

    /** The labels that were actually proven, package by package. */
    fun labels(
        sources: Map<String, LabelSource>,
        lookups: Map<String, ResolvedResource>,
    ): Map<String, String> = sources.mapNotNull { (appPackage, source) ->
        when (source) {
            is LabelSource.Literal -> appPackage to source.label
            is LabelSource.Resource ->
                lookups[appPackage]?.takeIf { it.id == source.id }?.let { appPackage to it.value }
        }
    }.toMap()

    /** Splits a batched run's output into the block each `##package` line introduces. */
    private fun String.sections(): List<Pair<String, List<String>>> {
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith(MARKER)) {
                sections.add(trimmed.removePrefix(MARKER).trim() to mutableListOf())
            } else {
                sections.lastOrNull()?.second?.add(line.trimEnd())
            }
        }
        return sections.filter { it.first.isNotBlank() }
    }

    private const val HEX = 16
}
