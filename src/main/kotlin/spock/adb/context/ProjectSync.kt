package spock.adb.context

import com.android.tools.idea.projectsystem.ProjectSystemSyncManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Tells [SpockSelection] when a Gradle sync ends, which is when the project's app ID appears.
 *
 * The first device usually arrives before the first sync of a freshly opened project finishes,
 * so the project's app could not be resolved and nothing was selected — and nothing asked again.
 *
 * The topic has moved: it was a companion constant of `ProjectSystemSyncManager` and is now
 * on `ProjectSystemSyncUtil`. It is looked up by name in both places rather than compiled
 * against either, so the plugin loads on every supported Android Studio.
 */
internal object ProjectSync {

    private val log = Logger.getInstance(ProjectSync::class.java)

    fun whenSynced(project: Project, parent: Disposable, onSynced: () -> Unit) {
        val topic = topic() ?: return log.info("No project sync topic here; the app is resolved on refresh only")
        project.messageBus.connect(parent).subscribe(
            topic,
            object : ProjectSystemSyncManager.SyncResultListener {
                override fun syncEnded(result: ProjectSystemSyncManager.SyncResult) = onSynced()
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun topic(): Topic<ProjectSystemSyncManager.SyncResultListener>? =
        OWNERS.firstNotNullOfOrNull { owner ->
            runCatching { Class.forName(owner).getField(FIELD).get(null) }.getOrNull()
        } as? Topic<ProjectSystemSyncManager.SyncResultListener>

    private const val FIELD = "PROJECT_SYSTEM_SYNC_TOPIC"
    private val OWNERS = listOf(
        "com.android.tools.idea.projectsystem.ProjectSystemSyncUtil",
        "com.android.tools.idea.projectsystem.ProjectSystemSyncManager",
    )
}
