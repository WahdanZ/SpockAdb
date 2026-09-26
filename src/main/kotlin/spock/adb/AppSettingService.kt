package spock.adb

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import spock.adb.premission.ListItem

@State(
    name = "spock-localData",
    storages = [Storage("spock-localData.xml")]
)
class AppSettingService : PersistentStateComponent<AppSetting> {

    private var localData: AppSetting

    init {
        val list = SpockAction.values().map {
            ListItem(it.name.replace("_", " "), true)
        }
        localData = AppSetting(null, list)
    }

    override fun getState(): AppSetting {
        return localData
    }

    override fun loadState(state: AppSetting) {
        localData = state.copy(list = state.list.withActionsAddedSince())
    }

    /**
     * Adds actions the stored list has never seen.
     *
     * The defaults are built from [SpockAction] once, on first run, and [loadState] then
     * replaces them wholesale. An action introduced in a later release was therefore absent
     * from every existing user's settings — and since the settings dialog is built from this
     * same list, there was no entry to switch it on or off with. New actions default to
     * shown, which is what a fresh install would have given them.
     */
    private fun List<ListItem>.withActionsAddedSince(): List<ListItem> {
        val stored = mapTo(mutableSetOf()) { it.name.replace(" ", "_") }
        return this + SpockAction.entries
            .filterNot { it.name in stored }
            .map { ListItem(it.name.replace("_", " "), true) }
    }

    /**
     * The action IDs pinned to the top of the Spock Actions popup, in order.
     *
     * Before any are chosen, the pins made on the old Device tab, and before those the three
     * actions most runs of the debugging loop start with.
     */
    fun pinnedActionIds(): List<String> = localData.pinnedActionIds
        ?: QuickAction.actionIds(localData.pinned).ifEmpty { DEFAULT_PINS }

    fun savePinnedActionIds(ids: List<String>) {
        localData = localData.copy(pinnedActionIds = ids.distinct())
    }

    /** The actions run most recently from the popup, newest first. */
    fun recentActionIds(): List<String> = localData.recentActionIds

    fun recordRecentAction(id: String) {
        localData = localData.copy(
            recentActionIds = (listOf(id) + localData.recentActionIds.filterNot { it == id }).take(MAX_RECENT),
        )
    }

    /**
     * Remembers a proxy that was set, most recent first, so it survives a restart.
     *
     * Recorded when Set is pressed rather than when the device confirms it: a proxy the device
     * refused is exactly the one worth having back in the list to try again.
     */
    fun saveHttpProxy(value: String) {
        localData = localData.copy(
            httpProxy = value,
            httpProxyHistory = proxyHistoryWith(httpProxyHistory(), value),
        )
    }

    fun lastHttpProxy(): String = localData.httpProxy

    /**
     * Every proxy set on this machine, most recent first.
     *
     * Seeded from [AppSetting.httpProxy] when the stored list is empty, so the one proxy a
     * developer already had remembered is the first entry of their history rather than lost to
     * the upgrade that introduced the list.
     */
    fun httpProxyHistory(): List<String> = localData.httpProxyHistory
        .ifEmpty { listOfNotNull(localData.httpProxy.takeIf { it.isNotBlank() }) }

    /** Forgets the proxies offered in the dropdown, keeping whatever the device holds. */
    fun clearHttpProxyHistory() {
        localData = localData.copy(httpProxy = "", httpProxyHistory = emptyList())
    }

    companion object {
        @JvmStatic
        fun getInstance(): AppSettingService =
            // Non-inline lookup, for the same reason as SpockAdbService.getInstance.
            ApplicationManager.getApplication().getService(AppSettingService::class.java)

        private const val MAX_RECENT = 5
        private val DEFAULT_PINS = listOf(
            "spock.adb.actions.RestartAppAction",
            "spock.adb.actions.CopyScreenForAiAction",
            "spock.adb.actions.DiagnoseCurrentScreenAction",
        )
    }
}

/**
 * @param httpProxy the proxy the user set last, as `host:port`. Kept alongside
 *   [httpProxyHistory] because it is what settings written before the history existed hold,
 *   and it seeds the list for anyone upgrading.
 * @param httpProxyHistory every proxy set on this machine, most recent first, so switching
 *   between a local Charles and a device-lab proxy is a pick rather than a retype. Both are
 *   application-scoped rather than per-project because a proxy runs on this machine, not in
 *   the project.
 * @param pinned the [QuickAction] names pinned to the Quick actions row, in the order shown.
 *   Order is the whole point, so this is a list rather than the set it would otherwise be.
 * @param pinnedActionIds the actions pinned in the Spock Actions popup; null until chosen, so the
 *   old [pinned] list can stand in for it.
 * @param recentActionIds the actions last run from that popup, newest first.
 * @param followStudioDevice selects the device chosen in Android Studio's run-target selector
 *   whenever that choice changes, so the device is picked once for running and debugging alike.
 */
data class AppSetting(
    val selectedDevice: String? = "",
    val list: List<ListItem>,
    val httpProxy: String = "",
    val httpProxyHistory: List<String> = emptyList(),
    val pinned: List<String> = emptyList(),
    val followStudioDevice: Boolean = true,
    val pinnedActionIds: List<String>? = null,
    val recentActionIds: List<String> = emptyList(),
)
enum class SpockAction {
    APP_INFO,
    CURRENT_ACTIVITY,
    CURRENT_FRAGMENT,
    CURRENT_APP_STACK,
    BACK_STACK,
    CLEAR_APP_DATA,
    CLEAR_APP_DATA_RESTART,
    CLEAR_APP_CACHE,
    RESTART,
    RESTART_DEBUG,
    TEST_PROCESS_DEATH,
    FORCE_KILL,
    UNINSTALL,
    TOGGLE_NETWORK,
    PERMISSIONS,
    DEVELOPER_OPTIONS,
    INPUT,
    DEEP_LINK,
    HTTP_PROXY,
    APP_STORAGE,
    PUSH_MESSAGE,
}

/**
 * [existing] with [value] at the front: no duplicates, and no longer than [max].
 *
 * Setting the same proxy again moves it up rather than adding it twice, which is what keeps a
 * list of eight useful to somebody who switches between two of them all day.
 */
internal fun proxyHistoryWith(
    existing: List<String>,
    value: String,
    max: Int = MAX_REMEMBERED_PROXIES,
): List<String> {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return existing
    return (listOf(trimmed) + existing.filterNot { it.equals(trimmed, ignoreCase = true) }).take(max)
}

/** Enough to cover the proxies one developer switches between; not a log of everything ever typed. */
internal const val MAX_REMEMBERED_PROXIES = 8
