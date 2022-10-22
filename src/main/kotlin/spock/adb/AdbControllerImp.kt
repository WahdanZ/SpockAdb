package spock.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import spock.adb.command.*
import spock.adb.models.ActivityData
import spock.adb.models.BackStackData
import spock.adb.models.FragmentData
import spock.adb.notification.CommonNotifier
import spock.adb.premission.ListItem
import javax.swing.border.EmptyBorder


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

    override fun refresh() {
        AndroidDebugBridge.removeDeviceChangeListener(this)
        AndroidDebugBridge.addDeviceChangeListener(this)
    }

    override fun connectedDevices(block: (devices: List<IDevice>) -> Unit) {
        updateDeviceList = block
        updateDeviceList?.invoke(debugBridge?.devices?.toList() ?: listOf())
    }

    override fun deviceConnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() ?: listOf())
    }

    override fun deviceDisconnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() ?: listOf())
    }

    override fun deviceChanged(iDevice: IDevice, i: Int) {}

    override fun currentBackStack(
        device: IDevice

    ) {
        val activitiesList = mutableListOf<String>()
        val activitiesClass: List<BackStackData> = GetBackStackCommand().execute(Any(), project, device)

        activitiesClass.forEachIndexed { index, activityData ->
            activitiesList.add("\t$index-${activityData.appPackage}")

            activityData.activitiesList.forEachIndexed { activityIndex, activity ->
                activitiesList.add("\t\t\t\t$activityIndex-${activity}")
            }
        }

        val list = JBList(activitiesList)
        showClassPopup(
            "Activities",
            list,
            activitiesList.map { it.trim().substringAfter("-").psiClassByNameFromProjct(project) }
        )
    }

    override fun currentApplicationBackStack(device: IDevice) {
        val applicationID = getApplicationID(device)
        val activitiesList: MutableList<String>
        val activitiesClass: List<ActivityData> =
            GetApplicationBackStackCommand().execute(applicationID, project, device)
        activitiesList = activitiesClass.map { listOf(it.activity) + it.fragment }.flatten().toMutableList()
        val list = JBList(activitiesList)
        list.installCellRenderer { o: Any ->
            var title = o.toString()
            title = if (!o.toString().contains('.'))
                "  |--$title (Fragment)"
            else
                (title.split('.').lastOrNull() ?: "") + "(Activity)"
            val label = JBLabel(title)
            label.border = EmptyBorder(5, 10, 5, 20)
            label
        }
        PopupChooserBuilder(list).apply {
            this.setTitle("Activities")
            this.setItemChoosenCallback {
                val current = activitiesList.getOrNull(list.selectedIndex)
                current?.let {
                    if (it.contains('.'))
                        it.psiClassByNameFromProjct(project)?.openIn(project)
                    else
                        it.psiClassByNameFromCache(project)?.openIn(project)
                }
            }
            this.createPopup().showCenteredInCurrentWindow(project)
        }

    }

    override fun currentActivity(
        device: IDevice

    ) {
        execute {
            val activity =
                GetActivityCommand().execute(Any(), project, device) ?: throw Exception("No activities found")
            activity.psiClassByNameFromProjct(project)?.openIn(project)
                ?: throw Exception("class $activity  Not Found")
        }
    }

    override fun currentFragment(
        device: IDevice

    ) {
        execute {
            val applicationID = getApplicationID(device)

            val fragmentsClass = GetFragmentsCommand().execute(applicationID, project, device)

            if (fragmentsClass.size > 1) {
                val fragmentsList = mutableListOf<String>()

                fragmentsClass.forEachIndexed { index, fragmentData ->
                    fragmentsList.add("\t$index-${fragmentData.fragment}")

                    addInnerFragmentsToList(fragmentData, fragmentsList)
                }

                val list = JBList(fragmentsList)
                showClassPopup(
                    "Fragments",
                    list,
                    fragmentsList.map { it.trim().substringAfter("-").psiClassByNameFromCache(project) }
                )
            } else {
                fragmentsClass
                    .firstOrNull()
                    ?.let {
                        it
                            .fragment
                            .psiClassByNameFromCache(project)
                            ?.openIn(project)
                            ?: throw Exception("Class $it Not Found")
                    }
            }
        }
    }

    override fun forceKillApp(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            ForceKillAppCommand().execute(applicationID, project, device)
            showSuccess("application $applicationID force killed")
        }
    }

    override fun testProcessDeath(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            ProcessDeathCommand().execute(applicationID, project, device)
            showSuccess("application $applicationID killed. App launched.")
        }
    }

    override fun restartApp(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            RestartAppCommand().execute(applicationID, project, device)
            showSuccess("application $applicationID Restart")
        }
    }

    override fun restartAppWithDebugger(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            RestartAppWithDebuggerCommand().execute(applicationID, project, device)
            showSuccess("application $applicationID Restarted with debugger")
        }
    }

    override fun clearAppData(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            ClearAppDataCommand().execute(applicationID, project, device)
            showSuccess("application $applicationID data cleared")
        }
    }

    override fun clearAppDataAndRestart(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            ClearAppDataAndRestartCommand().execute(applicationID, project, device)
            showSuccess("application $applicationID data cleared and restarted")
        }
    }

    override fun uninstallApp(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            UninstallAppCommand().execute(applicationID, project, device)
            showSuccess("application $applicationID uninstalled")
        }
    }

    override fun getApplicationPermissions(
        device: IDevice,
        block: (devices: List<ListItem>) -> Unit,
    ) {
        execute {
            val applicationID = getApplicationID(device)
            val permissions = GetApplicationPermission().execute(applicationID, project, device)
            if (permissions.isNotEmpty())
                block(permissions)
            else
                error("Your Application Doesn't Require any of Runtime Permissions ")
        }
    }

    override fun grantOrRevokeAllPermissions(
        device: IDevice,
        permissionOperation: GetApplicationPermission.PermissionOperation,
    ) {
        getApplicationPermissions(
            device,
        ) { permissionsList ->
            val applicationID = getApplicationID(device)

            val operation: (ListItem) -> Unit = when (permissionOperation) {
                GetApplicationPermission.PermissionOperation.GRANT ->
                    { permission -> GrantPermissionCommand().execute(applicationID, permission, project, device) }
                GetApplicationPermission.PermissionOperation.REVOKE ->
                    { permission -> RevokePermissionCommand().execute(applicationID, permission, project, device) }
            }

            permissionsList
                .forEach { permission -> operation(permission) }
                .also { showSuccess("All permissions ${permissionOperation.operationResult}") }
        }
    }

    override fun revokePermission(
        device: IDevice,
        listItem: ListItem,

        ) {
        execute {
            val applicationID = getApplicationID(device)
            RevokePermissionCommand().execute(applicationID, listItem, project, device)
            showSuccess("permission $listItem revoked")
        }
    }

    override fun grantPermission(
        device: IDevice,
        listItem: ListItem,

        ) {
        execute {
            val applicationID = getApplicationID(device)
            GrantPermissionCommand().execute(applicationID, listItem, project, device)
            showSuccess("permission $listItem granted")
        }
    }

    override fun connectDeviceOverIp(ip: String) {
        execute {
            ConnectDeviceOverIPCommand().execute(ip, project)
            showSuccess("connected to $ip")
        }
    }

    override fun enableDisableShowTaps(
        device: IDevice

    ) {
        execute {
            val result = EnableDisableShowTapsCommand().execute(Any(), project, device)
            showSuccess(result)
        }
    }

    override fun enableDisableShowLayoutBounds(
        device: IDevice

    ) {
        execute {
            val result = EnableDisableShowLayoutBoundsCommand().execute(Any(), project, device)
            showSuccess(result)
        }
    }

    override fun setWindowAnimatorScale(
        scale: String,
        device: IDevice

    ) {
        execute {
            val result = WindowAnimatorScaleCommand().execute(scale, project, device)
            showSuccess(result)
        }
    }

    override fun setTransitionAnimatorScale(
        scale: String,
        device: IDevice

    ) {
        execute {
            val result = TransitionAnimatorScaleCommand().execute(scale, project, device)
            showSuccess(result)
        }
    }

    override fun setAnimatorDurationScale(
        scale: String,
        device: IDevice

    ) {
        execute {
            val result = AnimatorDurationScaleCommand().execute(scale, project, device)
            showSuccess(result)
        }
    }

    override fun toggleNetwork(
        device: IDevice,
        network: Network,

        ) {
        execute {
            val result = ToggleNetworkCommand().execute(network, project, device)
            showSuccess(result)
        }
    }

    override fun inputOnDevice(
        input: String,
        device: IDevice
    ) {
        execute {
            val result = InputOnDeviceCommand().execute(input, project, device)
            showSuccess(result)
        }
    }

    private fun showError(message: String) {
        CommonNotifier.showNotifier(project = project, content = message, type = NotificationType.ERROR)

    }

    private fun showSuccess(message: String) {
        CommonNotifier.showNotifier(project = project, content = message, type = NotificationType.INFORMATION)
    }

    private fun execute(execute: () -> Unit) {
        try {
            execute.invoke()
        } catch (e: Exception) {
            showError(e.message ?: "not found")
        }
    }

    private fun showClassPopup(
        title: String,
        list: JBList<String>,
        classes: List<PsiClass?>
    ) {
        PopupChooserBuilder(list).apply {
            this.setTitle(title)
            this.setItemChoosenCallback {
                classes.getOrNull(list.selectedIndex)?.openIn(project)
            }
            this.createPopup().showCenteredInCurrentWindow(project)
        }
    }

    private fun addInnerFragmentsToList(
        fragmentData: FragmentData,
        fragmentsList: MutableList<String>,
        indent: String = "\t\t\t\t"
    ) {
        fragmentData.innerFragments.forEachIndexed { fragmentIndex, innerFragmentData ->
            fragmentsList.add("$indent$fragmentIndex-${innerFragmentData.fragment}")
            addInnerFragmentsToList(innerFragmentData, fragmentsList, "\t\t\t\t$indent")
        }
    }

    override fun openDeveloperOptions(
        device: IDevice
    ) {
        execute {
            showSuccess(OpenDeveloperOptionsCommand().execute(project, device))
        }
    }

    override fun openDeepLink(input: String, device: IDevice) {
        execute {
            val result = OpenDeepLinkCommand().execute(input, project, device)
            showSuccess(result)
        }
    }

    override fun openAccounts(device: IDevice) {
        execute {
            showSuccess(OpenAccountsCommand().execute(project, device))
        }
    }
}
