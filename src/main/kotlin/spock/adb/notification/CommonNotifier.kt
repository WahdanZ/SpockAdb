package spock.adb.notification

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

class CommonNotifier {
    companion object {
        fun showNotifier(
            project: Project, title: String = "SpockAdb",
            content: String = "",
            type: NotificationType = NotificationType.INFORMATION
        ) {
            val notification = Notification("SpockAdb", title, content, type)
            notification.notify(project)
        }
    }
}