package spock.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBList
import javassist.NotFoundException
import spock.adb.command.*
import spock.adb.premission.PermissionListItem

class AdbControllerImp(
    private val project: Project,
    private val debugBridge: AndroidDebugBridge?
) : AdbController, AndroidDebugBridge.IDeviceChangeListener {

    private var updateDeviceList: ((List<IDevice>) -> Unit)? = null

    init {
        AndroidDebugBridge.addDeviceChangeListener(this)
    }
    private fun getApplicationID(device: IDevice) =
        GetApplicationIDCommand().execute(Any(), project, device).toString()

    override fun connectedDevices(block: (devices: List<IDevice>) -> Unit, error: (message: String) -> Unit) {
        updateDeviceList = block
        updateDeviceList?.invoke(debugBridge?.devices?.toList() as List<IDevice>)
    }

    override fun deviceConnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() as List<IDevice>)
    }

    override fun deviceDisconnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() as List<IDevice>)
    }

    override fun deviceChanged(iDevice: IDevice, i: Int) {

    }

    override fun currentActivity(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {

        execute({
            val activity =
                GetActivityCommand().execute(Any(), project, device) ?: throw NotFoundException("Class Not Found")
            activity.openIn(project)
        }, error)
    }


    override fun currentFragment(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val fragmentsClass =
                GetFragmentsCommand().execute(Any(), project, device) ?: throw NotFoundException("Class Not Found")
            if (fragmentsClass.size > 1) {
                val list = JBList(fragmentsClass.map { it1 -> it1.toString().split(":").lastOrNull() })
                showClassPopup(list, fragmentsClass)
            } else {
                fragmentsClass[0]!!.openIn(project)
            }
        }, error)
    }

    private fun showClassPopup(
        list: JBList<String?>,
        fragmentsClass: List<PsiClass?>
    ) {
        PopupChooserBuilder<String>(list).apply {
            this.setTitle("Chose Fragment")
            this.setItemChoosenCallback {
                fragmentsClass.getOrNull(list.selectedIndex)?.openIn(project)
            }
            this.createPopup().showCenteredInCurrentWindow(project)
        }
    }

    override fun killApp(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        execute({
            val applicationID = getApplicationID(device)
            KillAppCommand().execute(applicationID, project, device)
            success("application $applicationID killed")
        }, error)
    }

    override fun restartApp(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        execute({
            val applicationID = getApplicationID(device)
            RestartAppCommand().execute(applicationID, project, device)
            success("application $applicationID Restart")
        }, error)
    }



    override fun clearAppData(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        execute({
            val applicationID = getApplicationID(device)
            ClearAppDataCommand().execute(applicationID, project, device)
            success("application $applicationID data cleared")
        }, error)
    }


    override fun getApplicationPermissions(
        device: IDevice,
        block: (devices: List<PermissionListItem>) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val applicationID = getApplicationID(device)
           block( GetApplicationPermission().execute(applicationID, project, device))
        }, error)
    }

    override fun revokePermission(
        device: IDevice,
        permissionListItem: PermissionListItem,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val applicationID = getApplicationID(device)
            RevokePermissionCommand().execute(applicationID,permissionListItem, project, device)
            success("application $applicationID data cleared")
        }, error)
    }

    override fun grantPermission(
        device: IDevice,
        permissionListItem: PermissionListItem,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val applicationID = getApplicationID(device)
            GrantPermissionCommand().execute(applicationID,permissionListItem, project, device)
            success("application $applicationID data cleared")
        }, error)
    }

    private fun execute(execute: () -> Unit, error: (message: String) -> Unit) {
        try {
            execute.invoke()
        } catch (e: Exception) {
            error(e.message ?: "not found")
        }
    }


}

