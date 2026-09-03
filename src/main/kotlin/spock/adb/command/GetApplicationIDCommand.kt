package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.tools.idea.model.AndroidModel
import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet

/**
 * Resolves the application ID (package name) of the app module in the open project.
 *
 * Two changes over the previous implementation:
 *
 *  - It reads through the [AndroidModel] interface rather than the Gradle-specific
 *    `GradleAndroidModel`. `AndroidModel` is the stable abstraction that
 *    `GradleAndroidModel` implements, it is present in the `org.jetbrains.android`
 *    plugin shipped by both Android Studio and IntelliJ IDEA, and it also covers
 *    non-Gradle Android projects.
 *  - It prefers application modules over libraries. Previously the first facet in an
 *    arbitrary order was used, so in a multi-module project the plugin could act on a
 *    library module and resolve no (or the wrong) application ID.
 */
class GetApplicationIDCommand : Command<Any, String?> {

    override fun execute(p: Any, project: Project, device: IDevice): String? =
        resolve(project)

    companion object {
        fun resolve(project: Project): String? {
            val facets = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID)
            if (facets.isEmpty()) return null

            // App modules first; libraries only as a last resort so single-module library
            // projects still behave as they did before.
            val ordered = facets.sortedByDescending { it.isApplicationModule() }

            return ordered.firstNotNullOfOrNull { facet -> facet.applicationId() }
        }

        private fun AndroidFacet.isApplicationModule(): Boolean =
            runCatching { configuration.isAppProject }.getOrDefault(false)

        private fun AndroidFacet.applicationId(): String? =
            runCatching { AndroidModel.get(this)?.applicationId }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != AndroidModel.UNINITIALIZED_APPLICATION_ID }
    }
}
