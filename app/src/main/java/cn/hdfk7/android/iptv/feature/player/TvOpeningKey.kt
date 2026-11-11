package cn.hdfk7.android.iptv.feature.player

/** Keep repeats from the opening press out of the newly focused panel. */
internal class TvOpeningKey {
    private var openingPress: Pair<Int, Long>? = null

    fun record(keyCode: Int, downTime: Long): Unit {
        openingPress = keyCode to downTime
    }

    fun consume(keyCode: Int, downTime: Long, released: Boolean): Boolean {
        if (openingPress != keyCode to downTime) {
            return false
        }
        if (released) {
            openingPress = null
        }
        return true
    }
}
