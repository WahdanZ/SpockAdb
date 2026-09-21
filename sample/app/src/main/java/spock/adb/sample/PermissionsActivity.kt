package spock.adb.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Lists each runtime permission with its current state, refreshed whenever the screen comes back,
 * so a Grant or Revoke from the plugin is visible here. Revoking a granted permission kills the
 * process, which is expected: Android does that.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Permissions") {
            note("Grant or revoke these from the plugin's Permissions dialog, then come back here.")
            status = output()
            button("Refresh") { refresh() }
            button("Ask for all of them in the app") { request.launch(permissions.toTypedArray()) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = permissions.joinToString("\n") { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            "${if (granted) "GRANTED" else "denied "}  ${permission.substringAfterLast('.')}"
        }
    }
}
