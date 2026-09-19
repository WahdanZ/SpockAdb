package spock.adb

import java.awt.Point
import java.awt.Rectangle

/**
 * An action on the Devices tab that can be pinned to Quick actions.
 *
 * Separate from [SpockAction], which is what the settings dialog switches on and off: the three
 * permission buttons share a single [SpockAction], so pinning cannot be keyed by it. Each entry
 * names one button, and [gate] is the setting that decides whether that button exists at all.
 *
 * The names are persisted, so an entry may be renamed only at the cost of somebody's pins.
 */
enum class QuickAction(val gate: SpockAction) {
    CURRENT_ACTIVITY(SpockAction.CURRENT_ACTIVITY),
    CURRENT_FRAGMENT(SpockAction.CURRENT_FRAGMENT),
    APP_BACK_STACK(SpockAction.CURRENT_APP_STACK),
    ALL_ACTIVITIES(SpockAction.BACK_STACK),

    RESTART_APP(SpockAction.RESTART),
    ATTACH_DEBUGGER(SpockAction.RESTART_DEBUG),
    FORCE_STOP(SpockAction.FORCE_KILL),
    PROCESS_DEATH(SpockAction.TEST_PROCESS_DEATH),

    CLEAR_DATA(SpockAction.CLEAR_APP_DATA),
    CLEAR_CACHE(SpockAction.CLEAR_APP_CACHE),
    CLEAR_DATA_AND_RESTART(SpockAction.CLEAR_APP_DATA_RESTART),
    UNINSTALL(SpockAction.UNINSTALL),

    MANAGE_PERMISSIONS(SpockAction.PERMISSIONS),
    GRANT_ALL_PERMISSIONS(SpockAction.PERMISSIONS),
    REVOKE_ALL_PERMISSIONS(SpockAction.PERMISSIONS),
    ;

    companion object {
        /**
         * The stored names as actions, in the order they were stored.
         *
         * Unknown names are dropped rather than failing: settings written by a later version, or
         * an action removed since, must not cost a developer the rest of their pins — the same
         * reason [SpockAdbViewer] ignores unknown entries in the visible-actions list.
         */
        fun read(stored: List<String>): List<QuickAction> =
            stored.mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct()
    }
}

/**
 * [pinned] with the action at [from] moved so that it sits at [to] in the row.
 *
 * [to] is an insertion point — 0 before the first, `size` after the last — so dropping to the
 * right of its own position has to account for the gap the action leaves behind.
 */
internal fun movedTo(pinned: List<QuickAction>, from: Int, to: Int): List<QuickAction> {
    if (from !in pinned.indices) return pinned
    val moved = pinned[from]
    val rest = pinned.toMutableList().apply { removeAt(from) }
    val target = to.coerceIn(0, pinned.size)
    rest.add(if (target > from) target - 1 else target, moved)
    return rest
}

/**
 * Where a drop at [point] lands in a row whose buttons occupy [bounds], as an insertion point.
 *
 * A button is dropped *before* the one whose middle it has passed. The row wraps, so the
 * buttons on the line the drop landed on are considered first; a drop below the last line falls
 * back to the whole row rather than refusing to be anywhere.
 */
internal fun dropIndexAt(bounds: List<Rectangle>, point: Point): Int {
    if (bounds.isEmpty()) return 0
    val onLine = bounds.indices.filter { point.y >= bounds[it].y && point.y < bounds[it].y + bounds[it].height }
    val candidates = if (onLine.isEmpty()) bounds.indices.toList() else onLine
    return candidates.firstOrNull { point.x < bounds[it].centerX } ?: (candidates.last() + 1)
}

/**
 * Whether a control whose label or tooltip is any of [text] answers [query].
 *
 * Every word has to match something, so "clear data" finds **Clear data** but not every action
 * with "clear" in it, and the words may be typed in either order.
 */
internal fun matchesActionSearch(query: String, vararg text: String?): Boolean {
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = text.filterNotNull().joinToString(" ").lowercase()
    return words.all { haystack.contains(it.lowercase()) }
}
