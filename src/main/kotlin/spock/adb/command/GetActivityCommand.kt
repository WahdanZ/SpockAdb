package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

class GetActivityCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? {
        return GetBackStackCommand().execute(p, project, device).firstOrNull()
    }
}
