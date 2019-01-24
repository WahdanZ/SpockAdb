package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class GetFragmentsCommand:Command<Any,List<PsiClass?>?> {
    override fun execute(p:Any,project: Project, device: IDevice): List<PsiClass?>? {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
       return  getCurrentFragmentsFromLog(shellOutputReceiver)
            ?.map { getClassByName(it, project) }?.distinct()

    }

    private fun getCurrentFragmentsFromLog(shellOutputReceiver: ShellOutputReceiver):List<String>? {
        return shellOutputReceiver.toString().split("Added Fragments:").lastOrNull()?.split("\n")
            ?.filter {
                it.contains("#")
            }?.map {
                it.split("{").first()
                    .split(" ")
                    .last()
            }?.filter { !it.contains(".") }
    }
    private fun getClassByName(className:String, project: Project): PsiClass? {
        return PsiShortNamesCache.getInstance(project).getClassesByName(
            className, GlobalSearchScope.allScope(project)).getOrNull(0)
    }
}