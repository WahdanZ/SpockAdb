package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.clearHttpProxy
import spock.adb.getHttpProxy
import spock.adb.setHttpProxy

/**
 * Points the device's global HTTP proxy at a debugging proxy (Charles, Proxyman, mitmproxy).
 *
 * The setting is global and survives a reboot, so the result names what the device holds
 * afterwards — a device left pointing at a proxy that is no longer listening fails every
 * request with nothing on screen to explain why, and that is the failure this feature is
 * most likely to cause.
 *
 * The MCP tools call the same [IDevice] functions directly rather than this class: they
 * need no [Project], and cannot always supply one.
 */
class SetHttpProxyCommand : Command<HttpProxy, HttpProxyWrite> {

    override fun execute(p: HttpProxy, project: Project, device: IDevice): HttpProxyWrite =
        device.setHttpProxy(p)
}

/** Restores direct connections by writing Android's `:0` sentinel, then reads it back. */
class ClearHttpProxyCommand : Command<Any, HttpProxyWrite> {

    override fun execute(p: Any, project: Project, device: IDevice): HttpProxyWrite =
        device.clearHttpProxy()
}

/** Reads the proxy currently set on the device. Null when there is none. */
class GetHttpProxyCommand : Command<Any, HttpProxy?> {

    override fun execute(p: Any, project: Project, device: IDevice): HttpProxy? =
        device.getHttpProxy()
}
