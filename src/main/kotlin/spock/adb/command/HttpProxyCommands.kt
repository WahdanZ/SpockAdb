package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.clearHttpProxy
import spock.adb.getHttpProxy
import spock.adb.setHttpProxy

/**
 * Points the device's global HTTP proxy at a debugging proxy (Charles, Proxyman, mitmproxy).
 *
 * The setting is global and survives a reboot, so the message names the value that was
 * written — a device left pointing at a proxy that is no longer listening fails every
 * request with nothing on screen to explain why, and that is the failure this feature is
 * most likely to cause.
 */
class SetHttpProxyCommand : Command<HttpProxy, String> {

    override fun execute(p: HttpProxy, project: Project, device: IDevice): String {
        device.setHttpProxy(p)
        return "HTTP proxy set to $p"
    }
}

/** Restores direct connections by writing Android's `:0` sentinel. */
class ClearHttpProxyCommand : Command<Any, String> {

    override fun execute(p: Any, project: Project, device: IDevice): String {
        device.clearHttpProxy()
        return "HTTP proxy cleared"
    }
}

/** Reads the proxy currently set on the device. Null when there is none. */
class GetHttpProxyCommand : Command<Any, HttpProxy?> {

    override fun execute(p: Any, project: Project, device: IDevice): HttpProxy? =
        device.getHttpProxy()
}
