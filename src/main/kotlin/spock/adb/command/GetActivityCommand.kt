package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class GetActivityCommand:Command<Any,PsiClass?> {
    override fun execute(p:Any,project: Project, device: IDevice): PsiClass? {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
      return  getCurrentActivityFromAdbLog(shellOutputReceiver)?.let {
            JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project))
        }
    }
    }

    private fun getCurrentActivityFromAdbLog(shellOutputReceiver: ShellOutputReceiver): String? {
        return shellOutputReceiver.toString()
            .split("\n")
            .filter { it.contains("  ACTIVITY") }
            .map {
                val data = (it.split(" ")
                    .getOrElse(3) { "" })
                    .replace("/.", ".")
                if (data.contains("/"))
                    data.split("/")
                        .getOrElse(1) { "" }
                else
                    data
            }.lastOrNull()
    }
