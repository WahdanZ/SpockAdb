package spock.adb.command

import com.intellij.openapi.project.Project

interface AdbCommand<in P, out R> {
    @Throws(Exception::class)
    fun execute(p: P, project: Project): R
}
