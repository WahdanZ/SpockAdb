package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

import spock.adb.ShellOutputReceiver
import spock.adb.isAppInstall
import spock.adb.isMarshmallow
import spock.adb.premission.PermissionListItem
import java.util.concurrent.TimeUnit

class GetApplicationPermission : Command<String, List<PermissionListItem>> {

    override fun execute(p: String, project: Project, device: IDevice): List<PermissionListItem> {
        if (device.isMarshmallow()) {
            if (device.isAppInstall(p)) {
                val shellOutputReceiver = ShellOutputReceiver()
                val ps = mutableMapOf<String, Boolean>()
                device.executeShellCommand(
                    "dumpsys package $p  | grep permission",
                    shellOutputReceiver,
                    15L,
                    TimeUnit.SECONDS
                )
                shellOutputReceiver.toString().split("\n")
                    .map { it.trim() }
                    .filter { it.contains(".permission.") }
                    .distinct()
                    .forEach { convertPermissionToMap(it, ps) }

                return ps.map { PermissionListItem(it.key, it.value) }
                    .filter {
                        dangerousPermissions.find { dangerousPermission ->
                            dangerousPermission.contains(it.permission.split(".").getOrElse(2) { "any" })
                        } != null
                    }
                    .toList()
            } else
                throw Exception("Application $p not installed")
        } else
            throw Exception("All Permissions Denied Device Bazinga!! Your Device before Marshmallow ")

    }

    private fun convertPermissionToMap(
        it: String,
        ps: MutableMap<String, Boolean>
    ) {
        val permission = it.split(":").getOrElse(0) { "" }
        val grant = it.split("=").getOrElse(1) { "false" }.contains("true")
        ps[permission] = grant
    }

    private val dangerousPermissions = listOf(
        "READ_CALENDAR",
        "WRITE_CALENDAR",
        "CAMERA",
        "READ_CONTACTS",
        "WRITE_CONTACTS",
        "GET_ACCOUNTS",
        "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION",
        "RECORD_AUDIO",
        "READ_PHONE_STATE",
        "READ_PHONE_NUMBERS ",
        "CALL_PHONE",
        "ANSWER_PHONE_CALLS ",
        "READ_CALL_LOG",
        "WRITE_CALL_LOG",
        "ADD_VOICEMAIL",
        "USE_SIP",
        "PROCESS_OUTGOING_CALLS",
        "BODY_SENSORS",
        "SEND_SMS",
        "RECEIVE_SMS",
        "READ_SMS",
        "RECEIVE_WAP_PUSH",
        "RECEIVE_MMS",
        "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE"
    )

}
