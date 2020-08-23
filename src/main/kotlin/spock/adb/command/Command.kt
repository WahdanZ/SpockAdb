package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

interface Command<in P, out R> {
    @Throws(Exception::class)
    fun execute(p: P, project: Project, device: IDevice): R
}
