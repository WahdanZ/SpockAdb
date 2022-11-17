package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.tools.idea.gradle.project.model.AndroidModuleModel
import com.android.tools.idea.model.AndroidModel
import com.intellij.openapi.project.Project
import org.jetbrains.android.util.AndroidUtils

class GetApplicationIDCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? {
        return AndroidUtils.getApplicationFacets(project)
            .getOrNull(0)
                ?.let {
                    AndroidModel.get(it)?.applicationId
                }
    }
    }
