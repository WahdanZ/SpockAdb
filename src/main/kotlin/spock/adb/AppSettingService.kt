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

    /** Remembers the proxy the user last set, so it survives a restart. */
    fun saveHttpProxy(value: String) {
        localData = localData.copy(httpProxy = value)
    }

    fun lastHttpProxy(): String = localData.httpProxy

    companion object {
        @JvmStatic
        fun getInstance(): AppSettingService =
            // Non-inline lookup, for the same reason as SpockAdbService.getInstance.
            ApplicationManager.getApplication().getService(AppSettingService::class.java)
    }
}

/**
 * @param httpProxy the last proxy the user set, as `host:port`, so it does not have to be
 *   retyped every session. Application-scoped rather than per-project because the proxy runs
 *   on this machine, not in the project.
 */
data class AppSetting(
    val selectedDevice: String? = "",
    val list: List<ListItem>,
    val httpProxy: String = "",
)
enum class SpockAction {
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
}
