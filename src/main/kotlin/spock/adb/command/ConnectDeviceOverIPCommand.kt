package spock.adb.command

import com.intellij.openapi.project.Project

class ConnectDeviceOverIPCommand : AdbCommand<String, Any> {
    override fun execute(p: String, project: Project): Any {
        return ""
    }
}
