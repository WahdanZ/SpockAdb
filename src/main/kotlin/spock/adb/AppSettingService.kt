package spock.adb

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
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
        localData = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): PersistentStateComponent<AppSetting> {
            return ServiceManager.getService(AppSettingService::class.java)
        }
    }
}

data class AppSetting(val selectedDevice: String? = "", val list: List<ListItem>)
enum class SpockAction {
    CURRENT_ACTIVITY,
    CURRENT_FRAGMENT,
    CURRENT_APP_STACK,
    BACK_STACK,
    CLEAR_APP_DATA,
    CLEAR_APP_DATA_RESTART,
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
}
