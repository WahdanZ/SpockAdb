package spock.adb.models

data class ActivityData(val activity: String, val fragment: List<String>, val status: String = "")
class BackStackData(val appPackage: String, val activitiesList: List<String>)
