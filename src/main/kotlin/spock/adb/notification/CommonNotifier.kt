package spock.adb.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

class CommonNotifier {
    companion object {
        fun showNotifier(
            project: Project,
            title: String = "SpockAdb",
            content: String = "",
            type: NotificationType = NotificationType.INFORMATION
        ) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("SpockAdb")
                .createNotification(title, content, type)
                .notify(project)
        }
    }
}
