package spock.adb.compat

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

/**
 * Guards the one integration point that is not guaranteed to exist in every IDE that
 * ships the `org.jetbrains.android` plugin.
 *
 * Everything else this plugin uses (ddmlib, `AndroidFacet`, `AndroidModel`, `AndroidSdkUtils`)
 * lives in `org.jetbrains.android` and is therefore available wherever that plugin is
 * installed. The debugger attach path additionally needs
 * `com.android.tools.idea.execution.common.debug.AndroidDebugger`, which is part of the
 * Android Studio execution stack and may be absent in a plain IntelliJ IDEA install.
 *
 * Rather than hard-depending on Android Studio for the whole plugin — which is what
 * `<depends>com.intellij.modules.androidstudio</depends>` did, and why the Marketplace
 * listed the plugin as incompatible with IntelliJ IDEA — the check is scoped to this one
 * feature, which reports a clear message when unavailable instead of failing with a
 * NoClassDefFoundError.
 */
object DebuggerSupport {

    private const val ANDROID_DEBUGGER_CLASS =
        "com.android.tools.idea.execution.common.debug.AndroidDebugger"

    const val UNAVAILABLE_MESSAGE: String =
        "Attaching the debugger requires the Android Studio execution tooling, " +
            "which is not available in this IDE. The app was restarted without a debugger."

    val isAvailable: Boolean by lazy {
        runCatching {
            Class.forName(ANDROID_DEBUGGER_CLASS, false, DebuggerSupport::class.java.classLoader)
        }.isSuccess
    }

    /**
     * Attaches the debugger to [packageName] on [device].
     *
     * @throws UnsupportedOperationException when the IDE does not ship the Android
     *   execution tooling. The `Debugger` class is only touched after the availability
     *   check so that its dependencies are never linked on an IDE that lacks them.
     */
    fun attach(project: Project, device: IDevice, packageName: String) {
        if (!isAvailable) throw UnsupportedOperationException(UNAVAILABLE_MESSAGE)
        doAttach(project, device, packageName)
    }

    private fun doAttach(project: Project, device: IDevice, packageName: String) {
        spock.adb.debugger.Debugger(project, device, packageName).attach()
    }
}
