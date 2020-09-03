package spock.adb.models

class FragmentData(
    val fragment: String,
    val fragmentIdentifier: String = "",
    var innerFragments: List<FragmentData> = emptyList()
)
