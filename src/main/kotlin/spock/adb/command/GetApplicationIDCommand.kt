package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.tools.idea.model.AndroidModel
import com.intellij.openapi.project.Project
import org.jetbrains.android.util.AndroidUtils

class GetApplicationIDCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? =
        AndroidUtils
            .getApplicationFacets(project)
            .firstOrNull()
            ?.let { androidFacet ->
                AndroidModel.get(androidFacet)?.applicationId
            }
}
