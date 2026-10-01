package spock.adb.command

/**
 * One runtime permission as `dumpsys package` reports it for user 0.
 *
 * @param userSet the `USER_SET` flag: the user answered the prompt at least once.
 * @param userFixed the `USER_FIXED` flag: Android sets it after two denials (Android 11+), or one
 *   "Don't ask again" before that, and from then on the prompt is not shown.
 */
data class RuntimePermission(
    val name: String,
    val granted: Boolean,
    val userSet: Boolean = false,
    val userFixed: Boolean = false,
) {
    /** Denied, and the system will not show the prompt again: requestPermissions() returns denied with no dialog. */
    val wontAskAgain: Boolean get() = !granted && userFixed
}
