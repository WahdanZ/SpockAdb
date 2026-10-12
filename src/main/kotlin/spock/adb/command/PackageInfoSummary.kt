package spock.adb.command

/**
 * The part of `dumpsys package` an agent asks for, read out of the rest.
 *
 * The dump opens with the package's intent resolver tables, which for an app with a few deep
 * links and WorkManager already run to thousands of characters before its version is printed —
 * 30 000 for a real Flutter app, 190 000 for Settings. `android_get_package_info` returned all of
 * it, capped, so the answer to "which version is installed, and is CAMERA granted" cost an
 * agent the whole dump and could be cut off before it got to either.
 *
 * Version and UID come from [AppInfo.parse] and the runtime permissions from
 * [GetApplicationPermission.parse], so the tool window and an agent cannot read them differently.
 */
data class PackageInfoSummary(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val uid: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val firstInstallTime: String?,
    val lastUpdateTime: String?,
    /** User 0's runtime permissions, sorted by name. */
    val permissions: List<RuntimePermission>,
    val components: Components,
    /** `com.example/.MainActivity`, as `am start -n` takes it; null when nothing has a launcher filter. */
    val launcherActivity: String?,
) {

    /**
     * How many of each component the dump names.
     *
     * Activities, services and receivers appear only in the resolver tables, so only those with
     * an intent filter are counted; providers are listed whether or not they have one.
     */
    data class Components(val activities: Int, val services: Int, val receivers: Int, val providers: Int)

    /** One fact per line, the permissions last since they are the longest. */
    fun describe(): String = buildList {
        add(packageName)
        add("Version: ${version() ?: "unknown"}")
        add("UID: ${uid ?: "unknown"}")
        add("SDK: min ${minSdk ?: "unknown"}, target ${targetSdk ?: "unknown"}")
        add("First installed: ${firstInstallTime ?: "unknown"}; last updated: ${lastUpdateTime ?: "unknown"}")
        add("Launcher activity: ${launcherActivity ?: "none (no activity has a MAIN/LAUNCHER filter)"}")
        add(
            "Components: ${components.activities.counted("activity", "activities")}, " +
                "${components.services.counted("service", "services")} and " +
                "${components.receivers.counted("receiver", "receivers")} with an intent filter; " +
                components.providers.counted("provider", "providers"),
        )
        val (granted, denied) = permissions.partition { it.granted }
        add("Runtime permissions granted: ${granted.joinToString { it.name }.ifEmpty { "none" }}")
        add(
            "Runtime permissions denied: " +
                denied.joinToString { if (it.wontAskAgain) "${it.name} (won't ask again)" else it.name }
                    .ifEmpty { "none" },
        )
        add(
            "Pass raw: true for the full dumpsys output: install and declared permissions, intent " +
                "filters, flags and paths.",
        )
    }.joinToString("\n")

    private fun version(): String? = AppInfo(packageName, versionName, versionCode, uid, pid = null).version()

    companion object {

        /** `85f4401 spock.adb.sample/.MainActivity filter 10e72a6`, with a `permission …` after it on some. */
        private val COMPONENT = Regex("""^\s*[0-9a-f]+\s+([^\s/]+/\S+)(?:\s+filter\b.*)?$""")

        /** `  spock.adb.sample/androidx.startup.InitializationProvider:` under Registered ContentProviders. */
        private val PROVIDER = Regex("""^\s+([^\s/]+/\S+):$""")
        private val ACTION = Regex("""^\s*Action: "([^"]+)"""")
        private val CATEGORY = Regex("""^\s*Category: "([^"]+)"""")
        private val MIN_SDK = Regex("""\bminSdk=(\d+)""")
        private val TARGET_SDK = Regex("""\btargetSdk=(\d+)""")

        private const val MAIN = "android.intent.action.MAIN"
        private const val LAUNCHER = "android.intent.category.LAUNCHER"

        fun parse(packageName: String, dumpsys: String): PackageInfoSummary {
            val info = AppInfo.parse(packageName, dumpsys, pidof = "")
            val sections = sections(dumpsys)
            val activities = filters(sections["Activity Resolver Table"])
            return PackageInfoSummary(
                packageName = packageName,
                versionName = info.versionName,
                versionCode = info.versionCode,
                uid = info.uid ?: legacyUserId(sections["Packages"]),
                minSdk = MIN_SDK.find(dumpsys)?.groupValues?.get(1),
                targetSdk = TARGET_SDK.find(dumpsys)?.groupValues?.get(1),
                firstInstallTime = dumpsys.lineValue("firstInstallTime"),
                lastUpdateTime = dumpsys.lineValue("lastUpdateTime"),
                permissions = GetApplicationPermission.parse(dumpsys),
                components = Components(
                    activities = activities.map { it.component }.distinct().size,
                    services = filters(sections["Service Resolver Table"]).map { it.component }.distinct().size,
                    receivers = filters(sections["Receiver Resolver Table"]).map { it.component }.distinct().size,
                    providers = (
                        filters(sections["Provider Resolver Table"]).map { it.component } +
                            sections["Registered ContentProviders"].orEmpty()
                                .mapNotNull { PROVIDER.find(it)?.groupValues?.get(1) }
                        ).distinct().size,
                ),
                launcherActivity = activities.firstOrNull { MAIN in it.actions && LAUNCHER in it.categories }
                    ?.component,
            )
        }

        /**
         * The package's UID as Android 9 and older print it: `userId=10085`, where later releases
         * print `appId=`. Read only inside the `Packages:` block, since a shared user elsewhere in
         * the dump has a `userId=` of its own.
         */
        private fun legacyUserId(packages: List<String>?): String? = packages.orEmpty()
            .map { it.trim() }
            .firstOrNull { it.startsWith("userId=") }
            ?.substringAfter('=')
            ?.takeWhile { !it.isWhitespace() }
            ?.takeIf { it.isNotEmpty() }

        /**
         * The dump's top-level sections by heading — `Activity Resolver Table:` and the rest start
         * in column 0, and everything up to the next such line belongs to them. A heading that
         * repeats (`Permissions:` does, once per declared permission) keeps its first block.
         */
        private fun sections(dumpsys: String): Map<String, List<String>> {
            val sections = linkedMapOf<String, MutableList<String>>()
            var current: MutableList<String>? = null
            dumpsys.lineSequence().forEach { line ->
                val heading = line.takeIf { it.isNotEmpty() && !it[0].isWhitespace() }?.trimEnd()
                when {
                    heading != null && heading.endsWith(":") -> {
                        val name = heading.removeSuffix(":")
                        current = if (name in sections) null else mutableListOf<String>().also { sections[name] = it }
                    }
                    else -> current?.add(line)
                }
            }
            return sections
        }

        /** One entry per component line in a resolver table, with the actions and categories under it. */
        private fun filters(table: List<String>?): List<Filter> {
            val filters = mutableListOf<Filter>()
            table.orEmpty().forEach { line ->
                val component = COMPONENT.find(line)?.groupValues?.get(1)
                val action = ACTION.find(line)?.groupValues?.get(1)
                val category = CATEGORY.find(line)?.groupValues?.get(1)
                when {
                    component != null -> filters += Filter(component)
                    action != null -> filters.lastOrNull()?.actions?.add(action)
                    category != null -> filters.lastOrNull()?.categories?.add(category)
                }
            }
            return filters
        }

        /**
         * The rest of the line after `name=`. Unlike the fields [AppInfo] reads, a time has a space
         * in it (`2026-09-30 14:29:45`), so it does not end at the first whitespace.
         */
        private fun String.lineValue(name: String): String? = lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        private fun Int.counted(one: String, many: String) = "$this ${if (this == 1) one else many}"
    }

    private class Filter(val component: String) {
        val actions = mutableSetOf<String>()
        val categories = mutableSetOf<String>()
    }
}
