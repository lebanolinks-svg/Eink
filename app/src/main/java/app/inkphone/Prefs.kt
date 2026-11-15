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
        get() = sp.getBoolean(LAYER_ON, true)
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

    var pinned: Set<String>
        get() = sp.getStringSet(PINNED, emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet(PINNED, v).apply()

    var hidden: Set<String>
        get() = sp.getStringSet(HIDDEN, emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet(HIDDEN, v).apply()

    companion object {
        const val ICON_STYLE = "icon_style"
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
