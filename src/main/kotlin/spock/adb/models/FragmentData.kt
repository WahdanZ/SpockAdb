package spock.adb.models

class FragmentData(
    val fragment: String,
    val fragmentIdentifier: String = "",
    var innerFragments: MutableList<FragmentData> = mutableListOf(),
    var isVisible: Boolean = false,
    var isNullParent: Boolean = false
) {
    fun getListStr(index: Int): String = "$fragment" //+ "-vis(${isVisible})-np(${isNullParent})"
}