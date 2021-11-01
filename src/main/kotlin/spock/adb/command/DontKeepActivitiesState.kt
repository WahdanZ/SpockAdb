package spock.adb.command

enum class DontKeepActivitiesState(val state: String) {
    ENABLED("1"),
    DISABLED("0");

    companion object {
        private val map = values().associateBy(DontKeepActivitiesState::state)
        fun getState(value: String) = map[value] ?: DISABLED
    }
}
