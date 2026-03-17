package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet

class GetApplicationIDCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? {
        return ProjectFacetManager.getInstance(project)
            .getFacets(AndroidFacet.ID)
            .firstOrNull()
            ?.let { GradleAndroidModel.get(it)?.applicationId }
    }
}
