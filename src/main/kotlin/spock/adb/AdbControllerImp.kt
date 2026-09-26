package spock.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.*
import spock.adb.device.ConnectedDevice
import spock.adb.device.DebugBridgeProvider
import spock.adb.device.DeviceLister
import spock.adb.models.ActivityData
import spock.adb.models.BackStackData
import spock.adb.models.FragmentData
import spock.adb.models.FragmentRow
import spock.adb.notification.CommonNotifier
import spock.adb.premission.ListItem
import spock.adb.ui.ActivityStackList
import spock.adb.ui.ActivityStackRow
import spock.adb.ui.className
import spock.adb.ui.toActivityStackRows


class AdbControllerImp(
    private val project: Project,
    /**
     * Resolves the ADB bridge. Safe to call from any thread — see [DebugBridgeProvider],
     * which handles the fact that `AndroidSdkUtils.getDebugBridge` asserts the EDT.
     */
    private val debugBridgeProvider: () -> AndroidDebugBridge? = DebugBridgeProvider(project),
) : AdbController, AndroidDebugBridge.IDeviceChangeListener, com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(AdbControllerImp::class.java)

    /** When the action running on this thread began, for the duration reported with its result. */
    private val startedAt = ThreadLocal<Long?>()

    private val resultListeners = java.util.concurrent.CopyOnWriteArrayList<(ActionResult) -> Unit>()

    override fun onResult(listener: (ActionResult) -> Unit) {
        resultListeners.addIfAbsent(listener)
    }

    /**
     * The app every action acts on, which is the one chosen in the tool window's header.
     *
     * Null until something chooses, and then it is the answer rather than the project's app
     * module: the header offers every installed app, and an action that quietly used a
     * different one than the header shows would be worse than having no header at all.
     */
    override var selectedApp: String? = null

    /**
     * Device-list observers.
     *
     * A single slot was enough when only the tool window listened, but the project service
     * now also observes to keep a cheap snapshot for action `update()`. With one slot the
     * second subscriber silently replaced the first.
     */
    private val deviceObservers = java.util.concurrent.CopyOnWriteArrayList<(List<ConnectedDevice>) -> Unit>()

    init {
        AndroidDebugBridge.addDeviceChangeListener(this)
    }

    /**
     * Reads the device list and resolves each device's metadata.
     *
     * Runs on a pooled thread: `IDevice.getProperty` blocks, so the UI must never do this
     * itself while rendering the device dropdown. Never throws — see [DeviceLister].
     */
    private val deviceLister = DeviceLister(
        devicesSupplier = { debugBridgeProvider()?.devices?.toList() },
        onError = { message, error -> log.warn(message, error) },
    )

    private fun devices(): List<ConnectedDevice> = deviceLister.list()

    /** Device-change callbacks arrive on ddmlib threads; observers update Swing. */
    private fun publishDeviceList() {
        if (deviceObservers.isEmpty()) return
        val devices = devices()
        onEdt { deviceObservers.forEach { it(devices) } }
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
        selectedApp?.takeIf { it.isNotBlank() }
            ?: GetApplicationIDCommand().execute(Any(), project, device)
            ?: throw IllegalStateException(
                "No app is selected, and none could be resolved for this project. " +
                    "Choose one at the top of the tool window, or open an Android project and " +
                    "let its Gradle sync finish.",
            )

    /**
     * Re-registers the device-change listener and re-reads the device list.
     *
     * This previously only swapped the listener registration, so it could not recover a
     * dropdown that had come up empty — there was no way to ask ADB again short of
     * reopening the project.
     */
    override fun refresh() {
        AndroidDebugBridge.removeDeviceChangeListener(this)
        AndroidDebugBridge.addDeviceChangeListener(this)
        ApplicationManager.getApplication().executeOnPooledThread { publishDeviceList() }
    }

    /**
     * Reads the device list on a pooled thread and delivers it on the EDT.
     *
     * This used to run entirely on the caller's thread, which meant the bridge was resolved
     * on the EDT and — for the connect/disconnect callbacks below — that Swing models were
     * mutated from a ddmlib thread.
     */
    override fun connectedDevices(block: (devices: List<ConnectedDevice>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = devices()
            onEdt { block(devices) }
        }
    }

    override fun observeDevices(block: (devices: List<ConnectedDevice>) -> Unit) {
        deviceObservers.addIfAbsent(block)
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
            val stack: List<BackStackData> = GetBackStackCommand().execute(Any(), project, device)

            // Best effort, and deliberately not fatal: the stack is the point, and an app whose
            // name the device will not give up is shown as its package, exactly as before.
            val labels = runCatching {
                GetAppLabelsCommand().execute(stack.map { it.appPackage }, project, device)
            }.onFailure { log.warn("Could not read app labels for the activity stack", it) }
                .getOrDefault(emptyMap())

            val rows = stack.toActivityStackRows(labels)

            // PSI lookups require a ReadAction when called from a background thread.
            //
            // Keyed by class name rather than by row position: the previous version looked the
            // class up with `items.indexOf(item)`, so the same activity appearing in two tasks
            // always opened the first one's entry.
            val classes = com.intellij.openapi.application.ReadAction.compute<
                Map<String, PsiClass?>,
                RuntimeException,
                > {
                rows.mapNotNull { it.className() }
                    .distinct()
                    .associateWith { it.psiClassByNameFromProjct(project) }
            }

            // Popup creation and display must happen on the EDT
            ApplicationManager.getApplication().invokeLater { showActivityStackPopup(rows, classes) }
        }
    }

    /**
     * The Activity Stack popup: a task line per app, its activities beneath it, and the task in
     * front badged as current.
     *
     * It used to be a chooser over strings the controller had formatted itself, which put the
     * parser's own indexes (`0-com.example.app`) in front of the developer and rendered an
     * unreadable `dumpsys` line as the bare string `0-`.
     */
    private fun showActivityStackPopup(rows: List<ActivityStackRow>, classes: Map<String, PsiClass?>) {
        if (rows.isEmpty()) {
            showError("No activities found")
            return
        }
        // The builder over the list itself, not createPopupChooserBuilder(rows): that one builds its
        // own JList, and the task headings and badges come from ActivityStackList's renderer.
        PopupChooserBuilder(ActivityStackList(rows))
            .setTitle("Activity Stack")
            .setItemChosenCallback(
                com.intellij.util.Consumer { row: ActivityStackRow ->
                    // A task heading and the "no resumed activity" line name no class to open.
                    val className = row.className() ?: return@Consumer
                    classes[className]?.openIn(project) ?: showError("class $className Not Found")
                },
            )
            .createPopup()
            .showCenteredInCurrentWindow(project)
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
                val fragmentsList = fragmentsClass.flatMap { it.flatten() }

                ApplicationManager.getApplication().invokeLater {
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(fragmentsList)
                        .setTitle("Fragments")
                        .setRenderer(javax.swing.ListCellRenderer<FragmentRow> { _, row, _, selected, _ ->
                            JBLabel(row.fragment).apply {
                                val left = ROW_PADDING + row.depth * INDENT_PER_LEVEL
                                border = JBUI.Borders.empty(ROW_PADDING, left, ROW_PADDING, ROW_PADDING)
                                isOpaque = selected
                                if (selected) {
                                    background = UIUtil.getListSelectionBackground(true)
                                    foreground = UIUtil.getListSelectionForeground(true)
                                }
                            }
                        })
                        .setItemChosenCallback { selected ->

                            execute {
                                val psiClass =
                                    com.intellij.openapi.application.ReadAction.compute<PsiClass?, RuntimeException> {
                                        selected.fragment.psiClassByNameFromCache(project)
                                    }

                                ApplicationManager.getApplication().invokeLater {
                                    psiClass?.openIn(project)
                                        ?: CommonNotifier.showNotifier(
                                            project = project,
                                            content = "Class ${selected.fragment} Not Found",
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
            val death = ProcessDeathCommand().execute(applicationID, project, device)
            val after = death.pidsAfter.ifEmpty { null }?.joinToString(prefix = ", new pid ") ?: ""
            val before = death.pidsBefore.joinToString()
            showSuccess("application $applicationID killed (pid $before) and relaunched$after")
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

    override fun clearAppCache(device: IDevice) {
        execute {
            val applicationID = getApplicationID(device)
            ClearAppCacheCommand().execute(applicationID, project, device)
            showSuccess("Cleared the cache for $applicationID (cache and code_cache)")
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
        onDone: () -> Unit,
    ) {
        execute(onDone) {
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

            // One permission the platform will not change must not stop the other thirty-one:
            // the failures are collected and named, rather than aborting the batch or — as
            // before, when the output was discarded — being announced as a success.
            val refused = permissions.mapNotNull { permission ->
                runCatching { operation(permission) }.exceptionOrNull()?.message
            }
            val changed = permissions.size - refused.size
            check(refused.size < permissions.size) {
                "No permission could be ${permissionOperation.operationResult}. ${refused.first()}"
            }
            showSuccess(
                if (refused.isEmpty()) {
                    "All $changed permissions ${permissionOperation.operationResult}"
                } else {
                    "$changed of ${permissions.size} permissions ${permissionOperation.operationResult}; " +
                        "the device refused ${refused.size}: ${refused.joinToString("; ")}"
                },
            )
        }
    }

    override fun revokePermission(
        device: IDevice,
        listItem: ListItem,
        onDone: () -> Unit,
    ) {
        execute(onDone) {
            val applicationID = getApplicationID(device)
            RevokePermissionCommand().execute(applicationID, listItem, project, device)
            showSuccess("permission $listItem revoked")
        }
    }

    override fun grantPermission(
        device: IDevice,
        listItem: ListItem,
        onDone: () -> Unit,
    ) {
        execute(onDone) {
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

    override fun enableDisableDontKeepActivities(device: IDevice) {
        execute {
            showSuccess(EnableDisableDontKeepActivitiesCommand().execute(Any(), project, device))
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
        onDone: () -> Unit,
    ) {
        execute(onDone) {
            val result = ToggleNetworkCommand().execute(network, project, device)
            showSuccess(result)
        }
    }

    override fun wifiStatus(device: IDevice, block: (status: Result<WifiStatus>) -> Unit) =
        read(block) { WifiStatusCommand().execute(Any(), project, device) }

    override fun appInfo(device: IDevice, block: (info: Result<AppInfo>) -> Unit) =
        read(block) { AppInfoCommand().execute(getApplicationID(device), project, device) }

    override fun screen(device: IDevice, block: (screen: Result<ScreenInfo>) -> Unit) =
        read(block) {
            val activity = GetActivityCommand().execute(Any(), project, device)
            // Fragments are the app's own; with no app to ask about, the activity alone is the answer.
            val fragments = runCatching { GetFragmentsCommand().execute(getApplicationID(device), project, device) }
                .getOrDefault(emptyList())
            ScreenInfo(activity, fragments.map { it.fragment })
        }

    override fun permissionSummary(device: IDevice, block: (summary: Result<PermissionSummary>) -> Unit) =
        read(block) {
            val permissions = GetApplicationPermission().execute(getApplicationID(device), project, device)
            PermissionSummary(
                granted = permissions.count { it.isSelected },
                denied = permissions.count { !it.isSelected },
            )
        }

    /**
     * Reads something from the device on a pooled thread and answers on the EDT.
     *
     * A read rather than an action: nothing is reported to the user and nothing is logged,
     * because the caller is filling in a label and a device that cannot answer should leave it
     * empty rather than raise a balloon.
     */
    private fun <T> read(block: (Result<T>) -> Unit, work: () -> T) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(work)
            onEdt { block(result) }
        }
    }

    override fun networkState(device: IDevice, network: Network, block: (state: Result<NetworkState>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val state = runCatching { device.getNetworkState(network) }
            onEdt { block(state) }
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

    private fun showError(message: String) = report(message, ok = false).also {
        onEdt { CommonNotifier.showNotifier(project = project, content = message, type = NotificationType.ERROR) }
    }

    private fun showSuccess(message: String) = report(message, ok = true).also {
        onEdt {
            CommonNotifier.showNotifier(project = project, content = message, type = NotificationType.INFORMATION)
        }
    }

    /**
     * Tells the tool window what an action did, and how long it took.
     *
     * A balloon says what happened and then goes away, which is the wrong shape for the answer
     * to "did that work?" — the one question a developer asks after every button. The tool
     * window keeps the last one on screen.
     */
    private fun report(message: String, ok: Boolean) {
        val elapsed = startedAt.get()?.let { System.currentTimeMillis() - it }
        onEdt { resultListeners.forEach { it(ActionResult(message, ok, elapsed)) } }
    }

    /**
     * Runs [block] on a pooled thread, reporting failures to the user *and* the IDE log.
     *
     * The previous version discarded the exception entirely and showed `e.message ?: "not
     * found"`, so a null-message exception surfaced to the user as the word "not found"
     * with no stack trace recorded anywhere.
     *
     * @param onDone runs on the EDT once [block] has finished, whether or not it threw, for a
     *   caller that has to re-read what it shows.
     */
    private fun execute(onDone: (() -> Unit)? = null, block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // How long the action took, read by whichever showSuccess/showError the block
            // reaches. A thread local rather than a field: pooled threads run actions
            // concurrently, and each one has to time its own.
            startedAt.set(System.currentTimeMillis())
            try {
                block()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("Spock ADB command failed", e)
                showError(e.message?.takeIf { it.isNotBlank() } ?: "${e.javaClass.simpleName} — see idea.log")
            } finally {
                startedAt.remove()
                onDone?.let { onEdt(it) }
            }
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
            // `am start` exits 0 for a link nothing handles, so the verdict comes from what it
            // printed — reporting the send as a success is how this lied before.
            val result = OpenDeepLinkCommand().execute(input, project, device)
            if (result.succeeded) showSuccess(result.message) else showError(result.message)
        }
    }

    override fun setHttpProxy(proxy: HttpProxy, device: IDevice, onDone: () -> Unit) {
        execute(onDone) {
            showProxyWrite(SetHttpProxyCommand().execute(proxy, project, device))
        }
    }

    override fun clearHttpProxy(device: IDevice, onDone: () -> Unit) {
        execute(onDone) {
            showProxyWrite(ClearHttpProxyCommand().execute(Any(), project, device))
        }
    }

    /**
     * Reports what the device holds after the write rather than what was sent — see
     * [HttpProxyWrite]. A write that did not take is an error, not a success with a caveat.
     */
    private fun showProxyWrite(write: HttpProxyWrite) =
        if (write.took) showSuccess(write.message) else showError(write.message)

    override fun currentHttpProxy(device: IDevice, block: (read: Result<HttpProxy?>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val read = try {
                Result.success(GetHttpProxyCommand().execute(Any(), project, device))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                // A device that disconnects mid-read is reported as unknown, never as "no
                // proxy" — that would be a guess presented as a reading.
                log.warn("Could not read the device HTTP proxy", e)
                Result.failure(e)
            }
            onEdt { block(read) }
        }
    }

    override fun sendPushMessage(
        message: PushMessage,
        devices: List<ConnectedDevice>,
        onDone: (List<PushDelivery>) -> Unit,
    ) {
        execute {
            // Each device answers for itself: an app missing from one device, or a device that
            // drops mid-send, is that device's failure, not the whole send's.
            val deliveries = devices.map { target ->
                try {
                    SendPushMessageCommand()
                        .execute(getApplicationID(target.device), message, project, target.device)
                        .copy(device = target.info.displayName)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Could not send a push message to ${target.device.serialNumber}", e)
                    PushDelivery(
                        device = target.info.displayName,
                        packageName = selectedApp.orEmpty(),
                        outcome = PushDelivery.Outcome.FAILED,
                        access = ShellAccess.UNKNOWN,
                        detail = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
            val summary = deliveries.joinToString("\n") { it.message }
            if (deliveries.all { it.accepted }) showSuccess(summary) else showError(summary)
            onEdt { onDone(deliveries) }
        }
    }

    override fun pushShellAccess(devices: List<ConnectedDevice>, block: (Map<ConnectedDevice, ShellAccess>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val access = devices.associateWith { target ->
                try {
                    target.device.pushShellAccess(getApplicationID(target.device))
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Could not read shell access on ${target.device.serialNumber}", e)
                    ShellAccess.UNKNOWN
                }
            }
            onEdt { block(access) }
        }
    }

    override fun dispose() {
        AndroidDebugBridge.removeDeviceChangeListener(this)
        deviceObservers.clear()
    }
}

/** How far each level of fragment nesting is indented in the Fragments popup, in pixels. */
private const val INDENT_PER_LEVEL = 16

/** Space around each row of the Fragments popup, in pixels. */
private const val ROW_PADDING = 8
