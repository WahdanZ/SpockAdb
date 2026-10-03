package spock.adb.flutter

/**
 * What `flutter_app_status` reports for one app on one device: whether it is a Flutter app, how
 * the attach went and, with a session, what the session knows. Plain values, so the tool's words
 * are tested without a device or an IDE. Holds no VM Service address: the session never hands one
 * out, and the outcome's texts are scrubbed again where they are shown.
 *
 * @param build from the APK; null for an app that is not a Flutter app, which has no session.
 * @param outcome null when nobody could ask; [note] says why.
 */
class FlutterAppStatus(
    val applicationId: String,
    val serial: String,
    val build: FlutterBuild?,
    val outcome: FlutterAttachOutcome?,
    val note: String? = null,
    val live: Live? = null,
) {
    /** A connected session's facts, read once. */
    class Live(
        val identity: AppIdentity,
        val snapshot: FlutterSessionSnapshot,
        /** The session's guess from the UI isolate's extensions; null when it has none yet. */
        val buildMode: FlutterBuild?,
        /** `getVM().version`, shortened: `3.4.3 (stable)`; null when the VM did not say. */
        val dartVersion: String?,
        /** Null while measuring, or when the device would not say: [clockDone] tells which. */
        val deviceTime: DeviceTime?,
        val clockDone: Boolean,
    )

    companion object {
        /**
         * `3.4.3 (stable) (Tue Jun 4 19:51:39 2024 +0000) on "android_arm64"` → `3.4.3 (stable)`:
         * the version and its channel, without the build date and target.
         */
        fun dartVersion(vmVersion: String?): String? {
            val match = DART_VERSION.find(vmVersion?.trim().orEmpty()) ?: return null
            val (version, channel) = match.destructured
            return if (channel.isEmpty()) version else "$version ($channel)"
        }

        private val DART_VERSION = Regex("""^(\d+\.\d+\.\d+\S*)(?:\s+\((\w+)\))?""")
    }
}
