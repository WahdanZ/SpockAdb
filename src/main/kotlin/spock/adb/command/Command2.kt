package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

interface Command2<in P, in P2, out R> {
    @Throws(Exception::class)
    fun execute(p: P, p2: P2, project: Project, device: IDevice): R
}
