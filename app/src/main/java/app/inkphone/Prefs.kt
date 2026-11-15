package app.inkphone

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("ink", Context.MODE_PRIVATE)

    var iconStyle: Int
        get() = sp.getInt(ICON_STYLE, IconStyler.DOODLE)
        set(v) = sp.edit().putInt(ICON_STYLE, v).apply()

    /** Master switch for the layer drawn over other apps. */
    var layerOn: Boolean
        get() = sp.getBoolean(LAYER_ON, false)
        set(v) = sp.edit().putBoolean(LAYER_ON, v).apply()

    var paper: Boolean
        get() = sp.getBoolean(PAPER, true)
        set(v) = sp.edit().putBoolean(PAPER, v).apply()

    /** 0..100 */
    var paperStrength: Int
        get() = sp.getInt(PAPER_STRENGTH, 55)
        set(v) = sp.edit().putInt(PAPER_STRENGTH, v).apply()

    /** The user waved away the "colours are still on" hint on the home screen. */
    var greyHintDismissed: Boolean
        get() = sp.getBoolean(GREY_HINT_DISMISSED, false)
        set(v) = sp.edit().putBoolean(GREY_HINT_DISMISSED, v).apply()

    var flash: Boolean
        get() = sp.getBoolean(FLASH, true)
        set(v) = sp.edit().putBoolean(FLASH, v).apply()

    var lowRefresh: Boolean
        get() = sp.getBoolean(LOW_REFRESH, true)
        set(v) = sp.edit().putBoolean(LOW_REFRESH, v).apply()

    // ---- home screen layout ----
    var showClock: Boolean
        get() = sp.getBoolean("show_clock", true)
        set(v) = sp.edit().putBoolean("show_clock", v).apply()
    var handClock: Boolean
        get() = sp.getBoolean("hand_clock", false)
        set(v) = sp.edit().putBoolean("hand_clock", v).apply()
    var showDate: Boolean
        get() = sp.getBoolean("show_date", true)
        set(v) = sp.edit().putBoolean("show_date", v).apply()
    var showScreenTime: Boolean
        get() = sp.getBoolean("show_screen_time", true)
        set(v) = sp.edit().putBoolean("show_screen_time", v).apply()
    var showMostUsed: Boolean
        get() = sp.getBoolean("show_most_used", true)
        set(v) = sp.edit().putBoolean("show_most_used", v).apply()
    var showSearch: Boolean
        get() = sp.getBoolean("show_search", true)
        set(v) = sp.edit().putBoolean("show_search", v).apply()
    var showGreyButton: Boolean
        get() = sp.getBoolean("show_grey_button", true)
        set(v) = sp.edit().putBoolean("show_grey_button", v).apply()
    var showLabels: Boolean
        get() = sp.getBoolean("show_labels", true)
        set(v) = sp.edit().putBoolean("show_labels", v).apply()
    /** 3..6 */
    var columns: Int
        get() = sp.getInt("columns", 4)
        set(v) = sp.edit().putInt("columns", v).apply()

    /** Widgets from other apps placed on the home screen, as (widget id, height in dp), top to bottom. */
    var widgets: List<Pair<Int, Int>>
        get() = sp.getString("widgets", "")!!.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            id to (parts.getOrNull(1)?.toIntOrNull() ?: 150)
        }
        set(v) = sp.edit().putString("widgets", v.joinToString(",") { "${it.first}:${it.second}" }).apply()

    /** Draw other apps' widgets in black and white so they match the page. */
    var widgetsGrey: Boolean
        get() = sp.getBoolean("widgets_grey", true)
        set(v) = sp.edit().putBoolean("widgets_grey", v).apply()

    /** Everything that changes how the home screen is built, as one comparable value. */
    val homeLayout: String
        get() = listOf(
            showClock, handClock, showDate, showScreenTime, showMostUsed, showSearch, showGreyButton, showLabels, columns,
            sp.getString("widgets", ""), widgetsGrey,
        ).joinToString()

    // ---- daily app limits ----
    /** Minutes allowed per day for this package; 0 means no limit. */
    fun limit(pkg: String): Int = sp.getInt(LIMIT + pkg, 0)

    fun setLimit(pkg: String, minutes: Int) {
        if (minutes > 0) sp.edit().putInt(LIMIT + pkg, minutes).apply() else sp.edit().remove(LIMIT + pkg).apply()
    }

    fun limits(): Map<String, Int> =
        sp.all.filter { it.key.startsWith(LIMIT) && it.value is Int }.map { it.key.removePrefix(LIMIT) to it.value as Int }.toMap()

    /** Extra minutes the user gave themselves for this package today. */
    fun bonus(pkg: String): Int = sp.getInt(bonusKey(pkg), 0)

    fun addBonus(pkg: String, minutes: Int) = sp.edit().putInt(bonusKey(pkg), bonus(pkg) + minutes).apply()

    private fun bonusKey(pkg: String): String {
        val c = java.util.Calendar.getInstance()
        return "bonus:${c.get(java.util.Calendar.YEAR)}:${c.get(java.util.Calendar.DAY_OF_YEAR)}:$pkg"
    }

    var pinned: Set<String>
        get() = sp.getStringSet(PINNED, emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet(PINNED, v).apply()

    var hidden: Set<String>
        get() = sp.getStringSet(HIDDEN, emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet(HIDDEN, v).apply()

    companion object {
        const val ICON_STYLE = "icon_style"
        private const val LIMIT = "limit:"
        const val LAYER_ON = "layer_on"
        const val PAPER = "paper"
        const val PAPER_STRENGTH = "paper_strength"
        const val GREY_HINT_DISMISSED = "grey_hint_dismissed"
        const val FLASH = "flash"
        const val LOW_REFRESH = "low_refresh"
        const val PINNED = "pinned"
        const val HIDDEN = "hidden"
    }
}
