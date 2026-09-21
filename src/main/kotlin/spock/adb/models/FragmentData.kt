package spock.adb.models

class FragmentData(
    val fragment: String,
    var innerFragments: MutableList<FragmentData> = mutableListOf(),
) {
    fun getListStr(index: Int): String = fragment
}
