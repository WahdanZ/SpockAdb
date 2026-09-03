package spock.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.jetbrains.android.sdk.AndroidSdkUtils
import spock.adb.command.*
import spock.adb.models.ActivityData
import spock.adb.models.BackStackData
import spock.adb.models.FragmentData
import spock.adb.notification.CommonNotifier
import spock.adb.premission.ListItem


class AdbControllerImp(
    private val project: Project,
    /**
     * Resolves the ADB bridge. Invoked on a pooled thread, never on the EDT.
     *
     * `AndroidSdkUtils.getDebugBridge` blocks while ADB starts, which can take seconds on a
     * cold start. It was previously called during `createToolWindowContent` and inside
     * `AnAction.actionPerformed`, both of which run on the EDT, freezing the IDE.
     */
    private val debugBridgeProvider: () -> AndroidDebugBridge? = {
        AndroidSdkUtils.getDebugBridge(project)
    },
) : AdbController, AndroidDebugBridge.IDeviceChangeListener, com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(AdbControllerImp::class.java)

    @Volatile
    private var updateDeviceList: ((List<IDevice>) -> Unit)? = null

    init {
        AndroidDebugBridge.addDeviceChangeListener(this)
    }

    private fun devices(): List<IDevice> =
        runCatching { debugBridgeProvider()?.devices?.toList() }
            .onFailure { log.warn("Could not read the connected device list from ADB", it) }
            .getOrNull()
            .orEmpty()

    /** Device-change callbacks arrive on ddmlib threads; the listener updates Swing. */
    private fun publishDeviceList() {
        val block = updateDeviceList ?: return
        val devices = devices()
        onEdt { block(devices) }
    }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block) { project.isDisposed }

    /**
     * The previous implementation called `.toString()` on a nullable result, so a project
     * with no resolvable application ID produced the literal string "null" and every
     * downstream command failed with `Application null not installed`. Fail with an
     * actionable message instead.
     */
    private fun getApplicationID(device: IDevice): String =
        GetApplicationIDCommand().execute(Any(), project, device)
            ?: throw IllegalStateException(
                "Could not determine the application ID for this project. " +
                    "Open an Android project and make sure its Gradle sync has finished.",
            )

    override fun refresh() {
        AndroidDebugBridge.removeDeviceChangeListener(this)
        AndroidDebugBridge.addDeviceChangeListener(this)
    }

    /**
     * Reads the device list on a pooled thread and delivers it on the EDT.
     *
     * This used to run entirely on the caller's thread, which meant the bridge was resolved
     * on the EDT and — for the connect/disconnect callbacks below — that Swing models were
     * mutated from a ddmlib thread.
     */
    override fun connectedDevices(block: (devices: List<IDevice>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = devices()
            onEdt { block(devices) }
        }
    }

    override fun observeDevices(block: (devices: List<IDevice>) -> Unit) {
        updateDeviceList = block
        connectedDevices(block)
    }

    override fun deviceConnected(iDevice: IDevice) = publishDeviceList()

    override fun deviceDisconnected(iDevice: IDevice) = publishDeviceList()

    override fun deviceChanged(iDevice: IDevice, i: Int) {
        // Only react to state transitions (online/offline), not to every property change.
        if (i and IDevice.CHANGE_STATE != 0) publishDeviceList()
    }

    override fun currentBackStack(
        device: IDevice

    ) {
        // ADB must run on a background thread — wrap everything in execute {}
        execute {
            val activitiesList = mutableListOf<String>()
            val activitiesClass: List<BackStackData> = GetBackStackCommand().execute(Any(), project, device)

            activitiesClass.forEachIndexed { index, activityData ->
                activitiesList.add("\t$index-${activityData.appPackage}")
                activityData.activitiesList.forEachIndexed { activityIndex, activity ->
                    activitiesList.add("\t\t\t\t$activityIndex-${activity}")
                }
            }

            // PSI lookups require a ReadAction when called from a background thread
            val classes = com.intellij.openapi.application.ReadAction.compute<List<PsiClass?>, RuntimeException> {
                activitiesList.map { it.trim().substringAfter("-").psiClassByNameFromProjct(project) }
            }

            // Popup creation and display must happen on the EDT
            ApplicationManager.getApplication().invokeLater {
                showClassPopup(title = "Activities", items = activitiesList, classes = classes)
            }
        }
    }

    override fun currentApplicationBackStack(device: IDevice) {
        // ADB must run on a background thread — wrap everything in execute {}
        execute {
            val applicationID = getApplicationID(device)
            val activitiesClass: List<ActivityData> =
                GetApplicationBackStackCommand().execute(applicationID, project, device)
            val activitiesList = activitiesClass.map { listOf(it.activity) + it.fragment }.flatten().toMutableList()

            // Popup creation and display must happen on the EDT
            ApplicationManager.getApplication().invokeLater {
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(activitiesList)
                    .setTitle("Activities")
                    .setRenderer(javax.swing.ListCellRenderer<String> { _, value, _, _, _ ->
                        var title = value.toString()
                        title = if (!value.toString().contains('.'))
                            "  |--$title (Fragment)"
                        else
                            (title.split('.').lastOrNull() ?: "") + "(Activity)"
                        val label = JBLabel(title)
                        label.border = JBUI.Borders.empty(5, 10, 5, 20)
                        label
                    })
                    .setItemChosenCallback { current ->
                        // Item chosen callback runs on EDT; dispatch PSI lookup to background
                        execute {
                            val psiClass = com.intellij.openapi.application.ReadAction.compute<PsiClass?, RuntimeException> {
                                if (current.contains('.'))
                                    current.psiClassByNameFromProjct(project)
                                else
                                    current.psiClassByNameFromCache(project)
                            }
                            ApplicationManager.getApplication().invokeLater {
                                psiClass?.openIn(project)
                                    ?: showError("class $current Not Found")
                            }
                        }
                    }
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }
        }
    }

    override fun currentActivity(
        device: IDevice

    ) {
        execute {
            val activity =
                GetActivityCommand().execute(Any(), project, device) ?: throw Exception("No activities found")
            // Resolve PSI on background thread inside ReadAction, then open on EDT
            val psiClass = com.intellij.openapi.application.ReadAction.compute<PsiClass?, RuntimeException> {
                activity.psiClassByNameFromProjct(project)
            }
            ApplicationManager.getApplication().invokeLater {
                psiClass?.openIn(project) ?: showError("class $activity Not Found")
            }
        }
    }

    override fun currentFragment(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            val fragmentsClass = GetFragmentsCommand().execute(applicationID, project, device)

            if (getSize(fragmentsClass) > 1) {
                val fragmentsList = mutableListOf<String>()

                fragmentsClass.forEachIndexed { index, fragmentData ->
                    fragmentsList.add(fragmentData.getListStr(index))
                    addInnerFragmentsToList(fragmentData, fragmentsList)
                }

                fragmentsList.reverse()

                ApplicationManager.getApplication().invokeLater {
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(fragmentsList)
                        .setTitle("Fragments")
                        .setItemChosenCallback { selected ->

                            execute {
                                val psiClass =
                                    com.intellij.openapi.application.ReadAction.compute<PsiClass?, RuntimeException> {
                                        selected.psiClassByNameFromCache(project)
                                    }

                                ApplicationManager.getApplication().invokeLater {
                                    psiClass?.openIn(project)
                                        ?: CommonNotifier.showNotifier(
                                            project = project,
                                            content = "Class $selected Not Found",
                                            type = NotificationType.ERROR
                                        )
                                }
                            }
                        }
                        .createPopup()
                        .showCenteredInCurrentWindow(project)
                }

            } else {
                val fragment = fragmentsClass.firstOrNull()?.fragment
                    ?: throw Exception("No fragments found")

                val psiClass = com.intellij.openapi.application.ReadAction.compute<PsiClass?, RuntimeException> {
                    fragment.psiClassByNameFromCache(project)
                }

                ApplicationManager.getApplication().invokeLater {
                    psiClass?.openIn(project)
                        ?: CommonNotifier.showNotifier(
                            project = project,
                            content = "fragment $fragment Not Found",
                            type = NotificationType.ERROR
                        )
                }
            }
        }
    }

    private fun getSize(list: List<FragmentData>): Int {
        return list.sumOf {
            1 + if (it.innerFragments.isNotEmpty()) getSize(it.innerFragments) else 0
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
            if (permissions.isEmpty()) {
                error("This application does not declare any runtime permissions.")
            }
            // The caller opens a dialog with this list, so it has to arrive on the EDT.
            onEdt { block(permissions) }
        }
    }

    /**
     * Runs entirely on a pooled thread.
     *
     * This previously reused [getApplicationPermissions], whose callback now delivers on the
     * EDT because its other caller opens a dialog. Issuing one `pm grant`/`pm revoke` per
     * permission from there would block the UI thread for the whole batch.
     */
    override fun grantOrRevokeAllPermissions(
        device: IDevice,
        permissionOperation: GetApplicationPermission.PermissionOperation,
    ) {
        execute {
            val applicationID = getApplicationID(device)
            val permissions = GetApplicationPermission().execute(applicationID, project, device)
            if (permissions.isEmpty()) {
                error("This application does not declare any runtime permissions.")
            }

            val operation: (ListItem) -> Unit = when (permissionOperation) {
                GetApplicationPermission.PermissionOperation.GRANT ->
                    { permission -> GrantPermissionCommand().execute(applicationID, permission, project, device) }

                GetApplicationPermission.PermissionOperation.REVOKE ->
                    { permission -> RevokePermissionCommand().execute(applicationID, permission, project, device) }
            }

            permissions.forEach(operation)
            showSuccess("All permissions ${permissionOperation.operationResult}")
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

    /**
     * Not implemented. `ConnectDeviceOverIPCommand` has always been a stub that returns an
     * empty string, while this method reported "connected to $ip" regardless — claiming
     * success for something that never happened. The button driving it is hidden in the
     * tool window, so the path is currently unreachable; it fails honestly rather than
     * lying if anything reaches it.
     */
    override fun connectDeviceOverIp(ip: String) {
        showError("Connecting to a device over IP is not implemented yet.")
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

    private fun showError(message: String) = onEdt {
        CommonNotifier.showNotifier(project = project, content = message, type = NotificationType.ERROR)
    }

    private fun showSuccess(message: String) = onEdt {
        CommonNotifier.showNotifier(project = project, content = message, type = NotificationType.INFORMATION)
    }

    /**
     * Runs [block] on a pooled thread, reporting failures to the user *and* the IDE log.
     *
     * The previous version discarded the exception entirely and showed `e.message ?: "not
     * found"`, so a null-message exception surfaced to the user as the word "not found"
     * with no stack trace recorded anywhere.
     */
    private fun execute(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                block()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("Spock ADB command failed", e)
                showError(e.message?.takeIf { it.isNotBlank() } ?: "${e.javaClass.simpleName} — see idea.log")
            }
        }
    }

    private fun showClassPopup(
        title: String,
        items: List<String>,
        classes: List<PsiClass?>
    ) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(title)
            .setItemChosenCallback { item ->
                classes.getOrNull(items.indexOf(item))?.openIn(project)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun addInnerFragmentsToList(
        fragmentData: FragmentData,
        fragmentsList: MutableList<String>,
        indent: String = ""
    ) {
        fragmentData.innerFragments.forEachIndexed { fragmentIndex, innerFragmentData ->
            fragmentsList.add("$indent${innerFragmentData.getListStr(fragmentIndex)}")
            addInnerFragmentsToList(innerFragmentData, fragmentsList, "\t$indent")
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

    override fun dispose() {
        AndroidDebugBridge.removeDeviceChangeListener(this)
        updateDeviceList = null
    }
}
