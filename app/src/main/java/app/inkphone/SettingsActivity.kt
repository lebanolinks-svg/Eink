package app.inkphone

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val ink by lazy { getColor(R.color.ink) }
    private val adbCommand by lazy { "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(list)
        })
    }

    // Rebuilt on every return so the status lines reflect what was just changed in system settings.
    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        build()
    }

    private fun build() {
        title("InkPhone")
        body("Makes this phone look and move like an e-ink device, so it is calmer to use and easier to put down.")

        // 1 — launcher
        section("1 · Home screen")
        val isHome = isDefaultHome()
        body(if (isHome) "InkPhone is your home screen." else "Set InkPhone as the home app to get the paper home screen.")
        button(if (isHome) "Change home app" else "Set as home app") { open(Settings.ACTION_HOME_SETTINGS) }

        body("Icon style")
        val styles = RadioGroup(this)
        listOf(
            IconStyler.DOODLE to "Doodle — every icon redrawn as a pen sketch",
            IconStyler.INK to "Ink — original icons in high-contrast grey",
            IconStyler.ORIGINAL to "Original — icons as they are",
        ).forEach { (style, label) ->
            styles.addView(RadioButton(this).apply {
                id = style + 1
                text = label
                styleText(this)
                isChecked = prefs.iconStyle == style
            })
        }
        styles.setOnCheckedChangeListener { _, id -> prefs.iconStyle = id - 1 }
        list.addView(styles)
        if (prefs.hidden.isNotEmpty()) {
            button("Show ${prefs.hidden.size} hidden app(s) again") {
                prefs.hidden = emptySet()
                rebuild()
            }
        }

        // 2 — overlay
        section("2 · Paper screen layer (all apps)")
        if (InkService.running) {
            body("The screen layer is on.")
        } else {
            body(
                "Turn on “InkPhone screen layer” under Accessibility → Installed apps. " +
                    "If Android says the setting is restricted, open App info for InkPhone, tap ⋮ and choose “Allow restricted settings”, then try again."
            )
            button("Open Accessibility settings") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            button("Open InkPhone app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }
        }
        switch("Paper tint and grain", prefs.paper) { prefs.paper = it }
        body("Strength")
        list.addView(SeekBar(this).apply {
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
        switch("Page-refresh flash when the screen changes", prefs.flash) { prefs.flash = it }
        switch("Ask the display for its slowest refresh rate", prefs.lowRefresh) { prefs.lowRefresh = it }

        // 3 — system
        section("3 · Black-and-white and no animations (all apps)")
        if (SystemInk.canWrite(this)) {
            switch("Greyscale display", SystemInk.isGrayscale(this)) { on -> guarded { SystemInk.setGrayscale(this, on) } }
            switch("Remove animations", SystemInk.animationsOff(this)) { off -> guarded { SystemInk.setAnimationsOff(this, off) } }
        } else {
            body(
                "Android only lets an app switch these after a one-time permission given from a computer. " +
                    "Connect the phone with USB debugging on and run:"
            )
            list.addView(TextView(this).apply {
                text = adbCommand
                typeface = Typeface.MONOSPACE
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextIsSelectable(true)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundColor(0x141C1C1C)
            })
            button("Copy command") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            body(
                "No computer? Set them by hand instead: Accessibility → Colour correction → Greyscale, " +
                    "and Accessibility → Remove animations."
            )
            button("Open Accessibility settings") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        }

        // 4 — screen time
        section("4 · Screen time on the home screen")
        if (ScreenTime.hasAccess(this)) {
            body("On the phone today: ${ScreenTime.format(runCatching { ScreenTime.todayMinutes(this) }.getOrDefault(0))}")
        } else {
            body("Allow usage access so the home screen can show how long you have been on the phone today.")
            button("Allow usage access") { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        }
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
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

    // ---- small view helpers ------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styleText(t: TextView, sp: Float = 15f) {
        t.typeface = Typeface.SERIF
        t.setTextColor(ink)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun title(text: String) = list.addView(TextView(this).apply {
        this.text = text
        styleText(this, 30f)
    })

    private fun section(text: String) {
        list.addView(View(this).apply { setBackgroundColor(ink) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(22) })
        list.addView(TextView(this).apply {
            this.text = text
            styleText(this, 19f)
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(2))
        })
    }

    private fun body(text: String) = list.addView(TextView(this).apply {
        this.text = text
        styleText(this)
        setPadding(0, dp(8), 0, dp(4))
    })

    private fun button(text: String, onClick: () -> Unit) = list.addView(
        Button(this).apply {
            this.text = text
            isAllCaps = false
            styleText(this)
            setOnClickListener { onClick() }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = list.addView(Switch(this).apply {
        this.text = text
        styleText(this)
        isChecked = checked
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    })
}
