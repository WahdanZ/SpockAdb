package spock.adb

/**
 * An action that could be pinned to Quick actions on the old Device tab, by the registered IDE
 * action that now does the same thing.
 *
 * Pins were stored as these names. They are read once more, into the Spock Actions popup's
 * pins, so nobody loses what they had pinned; the three permission buttons have no action of
 * their own and are dropped. The names are persisted, so an entry may not be renamed.
 */
enum class QuickAction(val actionId: String?) {
    CURRENT_ACTIVITY("spock.adb.actions.GetCurrentActivityAction"),
    CURRENT_FRAGMENT("spock.adb.actions.GetCurrentFragmentAction"),
    APP_BACK_STACK("spock.adb.actions.GetCurrentApplicationBackStackAction"),
    ALL_ACTIVITIES("spock.adb.actions.ShowActivityStackAction"),

    RESTART_APP("spock.adb.actions.RestartAppAction"),
    ATTACH_DEBUGGER("spock.adb.actions.RestartAppWithDebuggerAction"),
    FORCE_STOP("spock.adb.actions.ForceStopAppAction"),
    PROCESS_DEATH("spock.adb.actions.TestProcessDeathAction"),

    CLEAR_DATA("spock.adb.actions.ClearAppDataAction"),
    CLEAR_CACHE("spock.adb.actions.ClearAppCacheAction"),
    CLEAR_DATA_AND_RESTART("spock.adb.actions.ClearAppDataAndRestartAction"),
    UNINSTALL("spock.adb.actions.UninstallAppAction"),

    MANAGE_PERMISSIONS(null),
    GRANT_ALL_PERMISSIONS(null),
    REVOKE_ALL_PERMISSIONS(null),
    ;

    companion object {
        /**
         * The stored names as actions, in the order they were stored.
         *
         * Unknown names are dropped rather than failing: settings written by a later version, or
         * an action removed since, must not cost a developer the rest of their pins.
         */
        fun read(stored: List<String>): List<QuickAction> =
            stored.mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct()

        /** The action IDs the stored pins stand for, for the Spock Actions popup. */
        fun actionIds(stored: List<String>): List<String> = read(stored).mapNotNull { it.actionId }
    }
}
