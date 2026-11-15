package app.inkphone

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Three options, each a card with one obvious action:
 * Grey phone, E-ink home screen, and Advanced.
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var page: LinearLayout
    private var showAdvanced = false
    private val ink by lazy { getColor(R.color.ink) }
    private val paper by lazy { getColor(R.color.paper) }
    private val adbCommand by lazy { "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(36))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(page)
        })
    }

    // Rebuilt on every return so each card shows what was just changed in Android's settings.
    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        page.removeAllViews()
        page.addView(text("InkPhone", 32f, bold = true))
        page.addView(text("Pick what you want. Each one works on its own.", 16f).apply { setPadding(0, dp(4), 0, dp(4)) })
        greyCard()
        homeCard()
        advancedCard()
    }

    // ---- 1 · Grey phone ------------------------------------------------------

    private fun greyCard() {
        val grey = SystemInk.grayscaleState(this) == true
        val still = SystemInk.animationsOff(this)
        val card = card(R.drawable.ic_grey, "Grey phone", "Every app turns black and white. Nothing else changes.", grey)

        if (SystemInk.canWrite(this)) {
            card.addView(button(if (grey) "Turn colour back on" else "Turn on", primary = !grey) {
                guarded {
                    SystemInk.setGrayscale(this, !grey)
                    SystemInk.setAnimationsOff(this, !grey)
                }
                rebuild()
            })
            card.addView(note("One tap. Animations switch off and on with it."))
            return
        }

        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        if (!grey) {
            card.addView(steps(
                "Tap the button below",
                if (samsung) "Open Vision enhancements, then Colour correction" else "Turn on “Use colour correction”",
                if (samsung) "Turn it on and choose Greyscale" else "Choose Greyscale",
            ))
            card.addView(button("Open colour settings", primary = true) { openColourSettings() })
            card.addView(note("Come back here afterwards. Already grey but still says OFF? Some phones do not report it, and that is fine."))
        } else {
            card.addView(note("To get colour back, switch it off in the same Android screen."))
            card.addView(button("Open colour settings", primary = false) { openColourSettings() })
        }

        card.addView(divider())
        if (still) {
            card.addView(text("✓  Animations are off too", 15f))
        } else {
            card.addView(text("Extra: stop animations", 16f, bold = true))
            card.addView(note(
                if (samsung) "In Accessibility, open Vision enhancements and turn on Reduce animations."
                else "In Accessibility, open Colour and motion and turn on Remove animations."
            ))
            card.addView(button("Open Accessibility", primary = false) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        }
    }

    // ---- 2 · E-ink home screen -------------------------------------------------

    private fun homeCard() {
        val isHome = isDefaultHome()
        val card = card(
            R.drawable.ic_home_ink, "E-ink home screen",
            "A calm paper home screen. Apps turn pages instead of scrolling.", isHome,
        )
        card.addView(button(if (isHome) "Change home app" else "Set as home screen", primary = !isHome) {
            open(Settings.ACTION_HOME_SETTINGS)
        })
        if (!isHome) card.addView(note("Choose InkPhone Home in the list that opens."))

        card.addView(divider())
        card.addView(text("Icon look", 16f, bold = true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(IconStyler.DOODLE to "Doodle", IconStyler.INK to "Grey", IconStyler.ORIGINAL to "Original").forEach { (style, label) ->
            row.addView(
                button(label, primary = prefs.iconStyle == style) {
                    prefs.iconStyle = style
                    rebuild()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = dp(10)
                    marginEnd = if (style == IconStyler.ORIGINAL) 0 else dp(8)
                },
            )
        }
        card.addView(row)
        card.addView(note(
            when (prefs.iconStyle) {
                IconStyler.DOODLE -> "Every icon redrawn as a pen sketch."
                IconStyler.INK -> "The real icons, in strong grey."
                else -> "Icons exactly as they are."
            }
        ))
    }

    // ---- 3 · Advanced ----------------------------------------------------------

    private fun advancedCard() {
        val card = card(R.drawable.ic_advanced, "Advanced", "Fine-tuning and one-tap control. For technical users.", null)
        card.addView(button(if (showAdvanced) "Hide" else "Show", primary = false) {
            showAdvanced = !showAdvanced
            rebuild()
        })
        if (!showAdvanced) return

        // Paper wash
        card.addView(divider())
        card.addView(text("Paper wash over apps", 16f, bold = true))
        card.addView(note("A paper-grey film over everything. Fades colours, but does not remove them like Grey phone does."))
        if (!Settings.canDrawOverlays(this)) {
            card.addView(button("Allow display over other apps", primary = false) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
            })
        } else {
            card.addView(switch("Paper wash", prefs.layerOn) { on ->
                prefs.layerOn = on
                if (on) InkService.ensure(this) else InkService.stop(this)
            })
            InkService.ensure(this)
            card.addView(note("Strength"))
            card.addView(SeekBar(this).apply {
                max = 100
                progress = prefs.paperStrength
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                        if (fromUser) prefs.paperStrength = value
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
            card.addView(switch("Flash when the screen changes", prefs.flash) { prefs.flash = it })
            card.addView(switch("Slowest screen refresh rate", prefs.lowRefresh) { prefs.lowRefresh = it })
        }

        // Screen time
        card.addView(divider())
        card.addView(text("Screen time on the home screen", 16f, bold = true))
        if (ScreenTime.hasAccess(this)) {
            val minutes = runCatching { ScreenTime.todayMinutes(this) }.getOrDefault(0)
            card.addView(note("✓  On the phone today: ${ScreenTime.format(minutes)}"))
        } else {
            card.addView(note("Shows today's total on the home screen. The flash also uses it."))
            card.addView(button("Allow usage access", primary = false) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
        }

        // One-tap grey
        card.addView(divider())
        card.addView(text("One-tap Grey phone (debug)", 16f, bold = true))
        if (SystemInk.canWrite(this)) {
            card.addView(note("✓  Enabled. The Grey phone button now works in one tap."))
        } else {
            card.addView(note("Connect the phone to a computer once, with USB debugging on, and run this. After that the Grey phone button works in one tap."))
            card.addView(TextView(this).apply {
                text = adbCommand
                typeface = Typeface.MONOSPACE
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextIsSelectable(true)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = shape(fill = 0x141C1C1C, stroke = false, radius = 8)
            }, spaced(8))
            card.addView(button("Copy command", primary = false) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            })
        }

        if (prefs.hidden.isNotEmpty()) {
            card.addView(divider())
            card.addView(button("Show ${prefs.hidden.size} hidden app(s) again", primary = false) {
                prefs.hidden = emptySet()
                rebuild()
            })
        }
    }

    // ---- actions -----------------------------------------------------------------

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    /** Stock Android has a dedicated colour correction screen; other brands land on Accessibility. */
    private fun openColourSettings() {
        for (action in listOf(COLOUR_CORRECTION, Settings.ACTION_ACCESSIBILITY_SETTINGS)) {
            if (runCatching { startActivity(Intent(action)) }.isSuccess) return
        }
        open(Settings.ACTION_SETTINGS)
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun guarded(change: () -> Unit) {
        runCatching(change).onFailure {
            Toast.makeText(this, "Android refused the change: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- building blocks -----------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun spaced(top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun shape(fill: Int, stroke: Boolean, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(2), ink)
    }

    private fun text(value: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTypeface(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun note(value: String) = text(value, 14f).apply {
        alpha = 0.8f
        setPadding(0, dp(8), 0, 0)
    }

    private fun steps(vararg lines: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
        lines.forEachIndexed { i, line ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                addView(text("${i + 1}", 14f, bold = true).apply {
                    gravity = Gravity.CENTER
                    setTextColor(paper)
                    background = shape(fill = ink, stroke = false, radius = 13)
                }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(text(line, 15f).apply { setPadding(dp(10), 0, 0, 0) })
            })
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x331C1C1C)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16)
            bottomMargin = dp(14)
        }
    }

    /** A bordered card with icon, title, one-line description and an ON/OFF pill. Returns it for more content. */
    private fun card(icon: Int, title: String, subtitle: String, on: Boolean?): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(fill = 0xFFF9F7F1.toInt(), stroke = true, radius = 18)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(
            text(title, 22f, bold = true).apply { setPadding(dp(14), 0, dp(8), 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (on != null) {
            head.addView(text(if (on) "ON" else "OFF", 13f, bold = true).apply {
                setTextColor(if (on) paper else ink)
                background = shape(fill = if (on) ink else 0, stroke = true, radius = 20)
                setPadding(dp(12), dp(5), dp(12), dp(5))
            })
        }
        card.addView(head)
        card.addView(text(subtitle, 15f).apply { setPadding(0, dp(10), 0, dp(4)) })
        page.addView(card, spaced(16))
        return card
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTypeface(Typeface.SERIF, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) paper else ink)
        background = shape(fill = if (primary) ink else 0, stroke = true, radius = 12)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = spaced(12)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        typeface = Typeface.SERIF
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = checked
        setPadding(0, dp(12), 0, dp(4))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private companion object {
        /** Stock Android's colour correction screen; absent on some brands. */
        const val COLOUR_CORRECTION = "com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS"
    }
}
