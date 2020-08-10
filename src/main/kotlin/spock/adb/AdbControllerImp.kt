package spock.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBList

import spock.adb.command.*
import spock.adb.premission.PermissionListItem

class AdbControllerImp(
    private val project: Project,
    private val debugBridge: AndroidDebugBridge?
) : AdbController, AndroidDebugBridge.IDeviceChangeListener      {

    private var updateDeviceList: ((List<IDevice>) -> Unit)? = null

    init {
        AndroidDebugBridge.addDeviceChangeListener(this)
    }

    private fun getApplicationID(device: IDevice) =
        GetApplicationIDCommand().execute(Any(), project, device).toString()

    override fun refresh() {
        AndroidDebugBridge.removeDeviceChangeListener(this)
        AndroidDebugBridge.addDeviceChangeListener(this)
    }

    override fun connectedDevices(block: (devices: List<IDevice>) -> Unit, error: (message: String) -> Unit) {
        updateDeviceList = block
        updateDeviceList?.invoke(debugBridge?.devices?.toList() ?: listOf())
    }

    override fun deviceConnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() ?: listOf())
    }

    override fun deviceDisconnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() ?: listOf())
    }

    override fun deviceChanged(iDevice: IDevice, i: Int) {

    }

    override fun currentBackStack(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        val activitiesClass =
            GetBackStackCommand().execute(Any(), project, device)
        val list = JBList(activitiesClass.mapIndexed
        { index, className -> "$index-$className" })
        showClassPopup("Activities", list, activitiesClass.map { it?.psiClassByNameFromProjct(project) })
    }

    override fun currentActivity(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val activity =
                GetActivityCommand().execute(Any(), project, device) ?: throw Exception("No activities found")
            activity.psiClassByNameFromProjct(project)?.openIn(project)
                ?: throw Exception("class $activity  Not Found")
        }, error)
    }

    override fun currentFragment(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val applicationID = getApplicationID(device)

            val fragmentsClass =
                GetFragmentsCommand().execute(applicationID, project, device)
                    ?: throw Exception("Class Not Found")
            if (fragmentsClass.size > 1) {
                val list = JBList(fragmentsClass.map { it1 -> it1.toString().split(":").lastOrNull() ?: "" })
                showClassPopup("Fragments", list, fragmentsClass.map { it?.psiClassByNameFromCache(project) })
            } else {
                fragmentsClass.firstOrNull()?.let {
                    it.psiClassByNameFromCache(project)?.openIn(project)
                        ?: throw Exception("Class $it Not Found")
                }
            }
        }, error)
    }

    override fun forceKillApp(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        execute({
            val applicationID = getApplicationID(device)
            ForceKillAppCommand().execute(applicationID, project, device)
            success("application $applicationID force killed")
        }, error)
    }

    override fun testProcessDeath(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        execute({
            val applicationID = getApplicationID(device)
            ProcessDeathCommand().execute(applicationID, project, device)
            success("application $applicationID killed. App launched.")
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
            val permissions = GetApplicationPermission().execute(applicationID, project, device)
            if (permissions.isNotEmpty())
                block(permissions)
            else
                error("Your Application Doesn't Require any of Runtime Permissions ")
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
            RevokePermissionCommand().execute(applicationID, permissionListItem, project, device)
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
            GrantPermissionCommand().execute(applicationID, permissionListItem, project, device)
            success("application $applicationID data cleared")
        }, error)
    }

    override fun connectDeviceOverIp(ip: String, success: (message: String) -> Unit, error: (message: String) -> Unit) {
       execute({ConnectDeviceOverIPCommand().execute(ip,project)
           success("connected to $ip")
       },error)
    }

    override fun enableDisableDontKeepActivities(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val result = EnableDisableDontKeepActivitiesCommand().execute(Any(), project, device)
            success(result)
        }, error)
    }
    override fun enableDisableShowTaps(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val result = EnableDisableShowTapsCommand().execute(Any(), project, device)
            success(result)
        }, error)
    }

    override fun enableDisableShowLayoutBounds(
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val result = EnableDisableShowLayoutBoundsCommand().execute(Any(), project, device)
            success(result)
        }, error)
    }

    override fun setWindowAnimatorScale(
        scale: String,
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val result = WindowAnimatorScaleCommand().execute(scale, project, device)
            success(result)
        }, error)
    }

    override fun setTransitionAnimatorScale(
        scale: String,
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val result = TransitionAnimatorScaleCommand().execute(scale, project, device)
            success(result)
        }, error)
    }

    override fun setAnimatorDurationScale(
        scale: String,
        device: IDevice,
        success: (message: String) -> Unit,
        error: (message: String) -> Unit
    ) {
        execute({
            val result = AnimatorDurationScaleCommand().execute(scale, project, device)
            success(result)
        }, error)
    }

    private fun execute(execute: () -> Unit, error: (message: String) -> Unit) {
        try {
            execute.invoke()
        } catch (e: Exception) {
            error(e.message ?: "not found")
        }
    }

    private fun showClassPopup(
        title: String,
        list: JBList<String>,
        classes: List<PsiClass?>
    ) {
        PopupChooserBuilder<String>(list).apply {
            this.setTitle(title)
            this.setItemChoosenCallback {
                classes.getOrNull(list.selectedIndex)?.openIn(project)
            }
            this.createPopup().showCenteredInCurrentWindow(project)
        }
    }
}
