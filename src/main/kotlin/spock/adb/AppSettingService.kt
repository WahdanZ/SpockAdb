//package spock.adb
//
//import com.intellij.openapi.components.PersistentStateComponent
//import com.intellij.openapi.components.ServiceManager
//import com.intellij.openapi.components.State
//import com.intellij.openapi.components.Storage
//
//@State(
//    name = "localData",
//    storages = [Storage("localData.xml")]
//)
//class AppSettingService : PersistentStateComponent<AppSetting> {
//
//    private var localData: AppSetting = AppSetting()
//
//    override fun getState(): AppSetting {
//        return localData
//    }
//
//    override fun loadState(state: AppSetting) {
//        localData = state
//    }
//
//    companion object {
//        @JvmStatic
//        fun getInstance(): PersistentStateComponent<AppSetting> {
//            return ServiceManager.getService(AppSettingService::class.java)
//        }
//    }
//}
//
//data class AppSetting(val selectedDevice: String? = "")
//data class SpockActionItem(val spockAction: SpockAction,val enable:Boolean)
//enum class  SpockAction{
//    Test
//}
