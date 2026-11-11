package cn.hdfk7.android.iptv.feature.player

import androidx.annotation.StringRes
import cn.hdfk7.android.iptv.R

internal enum class MoreAction(@StringRes val label: Int) {
    SOURCE(R.string.library_title),
    REFRESH(R.string.refresh),
    RETRY(R.string.retry_current_line),
    PREVIOUS_CHANNEL(R.string.previous),
    NEXT_CHANNEL(R.string.next),
    PREVIOUS_LINE(R.string.previous_line),
    NEXT_LINE(R.string.next_line),
    VOLUME_DOWN(R.string.volume_down),
    VOLUME_UP(R.string.volume_up),
    SETTINGS(R.string.settings_title)
}

internal enum class MoreMenuDirection { UP, DOWN, LEFT, RIGHT }

internal fun moreMenuRows(
    television: Boolean,
    channelCount: Int,
    hasSelection: Boolean,
    lineCount: Int,
    fixedVolume: Boolean
): List<List<MoreAction>> {
    return buildList(builderAction = {
        add(listOf(MoreAction.SOURCE))
        add(listOf(MoreAction.REFRESH))
        if (!television && channelCount > 1) {
            add(listOf(MoreAction.PREVIOUS_CHANNEL, MoreAction.NEXT_CHANNEL))
        }
        if (hasSelection) {
            add(listOf(MoreAction.RETRY))
            if (lineCount > 1) {
                add(listOf(MoreAction.PREVIOUS_LINE, MoreAction.NEXT_LINE))
            }
        }
        if (!fixedVolume) {
            add(listOf(MoreAction.VOLUME_DOWN, MoreAction.VOLUME_UP))
        }
        add(listOf(MoreAction.SETTINGS))
    })
}

internal fun moveMoreMenuFocus(
    rows: List<List<MoreAction>>,
    current: MoreAction,
    direction: MoreMenuDirection
): MoreAction {
    val rowIndex = rows.indexOfFirst(predicate = { row -> current in row })
    if (rowIndex < 0) {
        return MoreAction.SOURCE
    }
    val column = rows[rowIndex].indexOf(current)
    return when (direction) {
        MoreMenuDirection.UP -> rows.getOrNull(rowIndex - 1)?.let(block = { row -> row[column.coerceAtMost(row.lastIndex)] })
        MoreMenuDirection.DOWN -> rows.getOrNull(rowIndex + 1)?.let(block = { row -> row[column.coerceAtMost(row.lastIndex)] })
        MoreMenuDirection.LEFT -> rows[rowIndex].getOrNull(column - 1)
        MoreMenuDirection.RIGHT -> rows[rowIndex].getOrNull(column + 1)
    } ?: current
}
