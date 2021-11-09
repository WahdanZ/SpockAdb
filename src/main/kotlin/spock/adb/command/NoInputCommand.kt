package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

interface NoInputCommand<out R> {
    @Throws(Exception::class)
    fun execute(project: Project, device: IDevice): R
}
