package spock.adb.command

import com.intellij.openapi.project.Project
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import org.jetbrains.android.sdk.AndroidSdkUtils

class ConnectDeviceOverIPCommand : AdbCommand<String, Any> {
    override fun execute(p: String, project: Project): Any {
        val adbPath = AndroidSdkUtils.getAdb(project)?.absolutePath
        var process: Process? = null
        print("$adbPath connect $p:5555")
        try {
            process = Runtime.getRuntime().exec("$adbPath connect $p:5555")
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.run { destroy() }
            }
            val content = process.errorStream.readBytes().toString(Charset.defaultCharset())
            print(content)
            process.run {
                print(content)
                destroy()
            }
            if (content.isNotEmpty()) throw Exception("enable to connect to $p")
            return ""
        } catch (e: Exception) {
            print(e)
            process?.destroy()
            throw Exception("enable to connect to $p")
        }
    }
}
