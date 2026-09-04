package spock.adb.commandcenter

/**
 * Recent and favourite commands, with the ordering rules the UI depends on.
 *
 * Pure and free of IDE types so the de-duplication and eviction behaviour can be tested —
 * getting these subtly wrong (duplicates piling up, a favourite silently evicted) is the
 * usual way a history feature becomes annoying.
 */
class CommandHistory(
    private val maxRecent: Int = DEFAULT_MAX_RECENT,
) {

    private val recent = ArrayDeque<String>()
    private val favourites = LinkedHashSet<String>()

    /** Most recently run first. Re-running a command moves it to the front, never duplicates. */
    fun recent(): List<String> = recent.toList()

    fun favourites(): List<String> = favourites.toList()

    fun record(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        recent.remove(trimmed)
        recent.addFirst(trimmed)
        while (recent.size > maxRecent) recent.removeLast()
    }

    fun toggleFavourite(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false

        return if (favourites.remove(trimmed)) {
            false
        } else {
            favourites.add(trimmed)
            true
        }
    }

    fun isFavourite(command: String): Boolean = command.trim() in favourites

    fun clearRecent() = recent.clear()

    /** Restores persisted state, dropping blanks and duplicates that a hand-edited file may contain. */
    fun load(recentCommands: List<String>, favouriteCommands: List<String>) {
        recent.clear()
        favourites.clear()
        recentCommands.map(String::trim).filter(String::isNotEmpty).distinct()
            .take(maxRecent)
            .forEach(recent::addLast)
        favouriteCommands.map(String::trim).filter(String::isNotEmpty).forEach(favourites::add)
    }

    companion object {
        const val DEFAULT_MAX_RECENT = 50
    }
}
