package spock.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.ui.components.JBList
import java.util.concurrent.TimeUnit

class AdbControllerImp(
    private val project: Project,
    private val debugBridge: AndroidDebugBridge?,
    private val applicationID: String?
) : AdbController, AndroidDebugBridge.IDeviceChangeListener {
    private var updateDeviceList: ((List<IDevice>) -> Unit)? = null

    init {
        AndroidDebugBridge.addDeviceChangeListener(this)
    }

    override fun connectedDevices(block: (devices: List<IDevice>) -> Unit, error: (message: String) -> Unit) {
        updateDeviceList = block
        updateDeviceList?.invoke(debugBridge?.devices?.toList() as List<IDevice>)
    }

    override fun killApp(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        try {
            if (device.isAppInstall(applicationID, error))
                device.killApp("am force-stop $applicationID", 15L)
        } catch (ex: Exception) {
            error(ex.message.toString())
        }
    }

    override fun restartApp(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        try {
            if (device.isAppInstall(applicationID, error)) {
                killApp(device, success, error)
                val activity = device.getDefaultActivityForApplication(applicationID)
                if (activity.isNotEmpty()) {
                    device.startActivity(activity)
                } else {
                    error("No Default Activity Found")
                }
            }
        } catch (ex: Exception) {
            error(ex.message.toString())
        }
    }

    override fun clearAppData(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit) {
        try {
            if (device.isAppInstall(applicationID, error))
                device.clearAppData(applicationID)
        } catch (ex: Exception) {
            error(ex.message.toString())
        }
    }

    override fun currentActivity(device: IDevice, error: (message: String) -> Unit) {
        try {
            val shellOutputReceiver = ShellOutputReceiver()
            device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
            shellOutputReceiver.toString()
                .split("\n")
                .filter { it.contains("  ACTIVITY") }
                .map {
                    val data = (it.split(" ")
                        .getOrElse(3) { "" })
                        .replace("/.", ".")
                    if (data.contains("/"))
                        data.split("/")
                            .getOrElse(1) { "" }
                    else
                        data
                }.lastOrNull()?.let {
                    val psiClass = JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project))
                    if (psiClass != null) {
                        psiClass.openIn(project)
                    } else {
                        error("Error Class Not Found")
                    }
                }
        } catch (ex: Exception) {
            error(ex.message.toString())
        }
    }

    override fun currentFragment(device: IDevice, error: (message: String) -> Unit) {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
        val fragments = shellOutputReceiver.toString().split("Added Fragments:").lastOrNull()?.split("\n")
            ?.filter {
                it.contains("#")
            }?.map {
                it.split("{").first()
                    .split(" ")
                    .last()
            }?.filter { !it.contains(".") }?.map {
                PsiShortNamesCache.getInstance(project).getClassesByName(
                    it, GlobalSearchScope.allScope(project)
                ).getOrNull(0)
            }?.distinct()
        if (fragments != null) {
            if (fragments.size > 1) {
                val list = JBList(fragments.map { it1 -> it1.toString().split(":").lastOrNull() })
                PopupChooserBuilder<String>(list).apply {
                    this.setTitle("Chose Fragment")
                    this.setItemChoosenCallback {
                        fragments.getOrNull(list.selectedIndex)?.openIn(project)
                    }
                    this.createPopup().showCenteredInCurrentWindow(project)
                }
            } else {
                fragments.getOrNull(0)?.openIn(project)

            }
        }

    }

    override fun deviceConnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() as List<IDevice>)
    }

    override fun deviceDisconnected(iDevice: IDevice) {
        updateDeviceList?.invoke(debugBridge?.devices?.toList() as List<IDevice>)
    }

    override fun deviceChanged(iDevice: IDevice, i: Int) {

    }
}

