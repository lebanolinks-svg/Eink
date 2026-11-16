package app.inkphone

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
import java.util.concurrent.Executors

/**
 * The menu, drawn like a page from a sketchbook. A before/after strip of the
 * user's own apps on top, then three choices: Master grey, E-ink launcher, Advanced.
 */
class SettingsActivity : Activity() {
    /** One app icon in the three looks the launcher offers. */
    private class Sample(val colour: Bitmap, val grey: Bitmap, val doodle: Bitmap)

    private lateinit var prefs: Prefs
    private lateinit var page: LinearLayout
    private var showAdvanced = false
    private var billing: Billing? = null
    private var samples: List<Sample> = emptyList()
    private var realSamplesLoaded = false
    private val io = Executors.newSingleThreadExecutor()

    private val ink by lazy { getColor(R.color.ink) }
    private val paper by lazy { getColor(R.color.paper) }
    private val hand: Typeface by lazy { runCatching { resources.getFont(R.font.caveat) }.getOrDefault(Typeface.SERIF) }
    private val density by lazy { resources.displayMetrics.density }
    private val adbCommand by lazy { "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Pro.touch(this)
        billing = runCatching { Billing(this) { rebuild() }.also { it.start() } }.getOrNull()
        samples = builtInSamples()
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(40))
        }
        setContentView(ScrollView(this).apply {
            // Android 15+ draws apps under the status and navigation bars; keep the page clear of them.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
                insets
            }
            addView(page)
        })
        loadRealSamples()
    }

    override fun onDestroy() {
        billing?.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    // Rebuilt on every return so each card shows what was just changed in Android's settings.
    override fun onResume() {
        super.onResume()
        billing?.restore()
        rebuild()
    }

    private fun rebuild() {
        page.removeAllViews()
        page.addView(script("InkPhone", 54f))
        page.addView(script("a quieter phone, drawn in ink", 24f).apply { alpha = 0.75f })
        hero()
        unlockCard()
        page.addView(script("choose your ink", 30f), spaced(22))
        greyCard()
        homeCard()
        limitsCard()
        advancedCard()
    }

    // ---- before / after strip --------------------------------------------------

    private fun hero() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(density, 0xFFFBF9F3.toInt(), ink, 20f)
            setPadding(dp(8), dp(12), dp(8), dp(10))
        }
        box.addView(InkCompareView(this, ink, paper).apply { pairs = samples.map { it.colour to it.doodle } })
        box.addView(script("drag the pen across your apps", 21f).apply {
            gravity = Gravity.CENTER
            alpha = 0.75f
        })
        page.addView(box, spaced(14))
    }

    /** Swaps the built-in sample tiles for four of the user's own apps. */
    private fun loadRealSamples() {
        val size = dp(76)
        io.execute {
            val found = runCatching {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(launcher, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                    .let { all -> if (all.size <= 4) all else List(4) { i -> all[i * all.size / 4] } }
                    .map { info ->
                        val icon = info.loadIcon(packageManager)
                        val seed = info.activityInfo.packageName.hashCode()
                        Sample(
                            IconStyler.render(icon, IconStyler.ORIGINAL, size, seed),
                            IconStyler.render(icon, IconStyler.INK, size, seed),
                            IconStyler.render(icon, IconStyler.DOODLE, size, seed),
                        )
                    }
            }.getOrDefault(emptyList())
            if (found.size == 4) runOnUiThread {
                if (!isDestroyed) {
                    samples = found
                    realSamplesLoaded = true
                    rebuild()
                }
            }
        }
    }

    /** Four made-up colourful app tiles, used until the real icons are ready. */
    private fun builtInSamples(): List<Sample> {
        val size = dp(76)
        val s = size.toFloat()
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val ring = Paint(white).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.08f }
        val tiles: List<Pair<Int, (Canvas) -> Unit>> = listOf(
            0xFF1FAF5A.toInt() to { c -> // chat bubble
                c.drawOval(RectF(s * .24f, s * .26f, s * .76f, s * .68f), white)
                c.drawPath(Path().apply { moveTo(s * .32f, s * .6f); lineTo(s * .27f, s * .8f); lineTo(s * .48f, s * .66f); close() }, white)
            },
            0xFFE03A2F.toInt() to { c -> // play
                c.drawPath(Path().apply { moveTo(s * .38f, s * .28f); lineTo(s * .74f, s * .5f); lineTo(s * .38f, s * .72f); close() }, white)
            },
            0xFF1E6FE0.toInt() to { c -> // envelope
                c.drawRoundRect(RectF(s * .22f, s * .32f, s * .78f, s * .68f), s * .04f, s * .04f, ring)
                c.drawPath(Path().apply { moveTo(s * .24f, s * .35f); lineTo(s * .5f, s * .54f); lineTo(s * .76f, s * .35f) }, ring)
            },
            0xFFF08A00.toInt() to { c -> // camera
                c.drawCircle(s * .5f, s * .52f, s * .2f, ring)
                c.drawCircle(s * .5f, s * .52f, s * .07f, white)
                c.drawRect(s * .4f, s * .22f, s * .6f, s * .3f, white)
            },
        )
        return tiles.mapIndexed { i, (colour, glyph) ->
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawRoundRect(
                RectF(s * .06f, s * .06f, s * .94f, s * .94f), s * .22f, s * .22f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour },
            )
            glyph(canvas)
            val icon = BitmapDrawable(resources, bmp)
            Sample(
                bmp,
                IconStyler.render(icon, IconStyler.INK, size, i),
                IconStyler.render(icon, IconStyler.DOODLE, size, i * 17 + 3),
            )
        }
    }

    // ---- trial and unlock ---------------------------------------------------------

    private fun unlockCard() {
        val price = billing?.price ?: "\$0.99"
        val daysLeft = Pro.trialDaysLeft(this)
        when {
            Pro.owned(this) -> page.addView(note("✓  InkPhone is unlocked for good. Thank you."), spaced(10))
            BuildConfig.DEBUG -> page.addView(note("Test build: everything is unlocked."), spaced(10))
            else -> {
                val card = if (daysLeft > 0) {
                    card(
                        R.drawable.ic_unlock, "Free week",
                        "$daysLeft day${if (daysLeft == 1) "" else "s"} left with everything open. After that, doodle icons, app limits, " +
                            "widgets and the paper wash lock. The home screen and Master grey stay free.",
                        null,
                    )
                } else {
                    card(
                        R.drawable.ic_unlock, "Your free week is over",
                        "Doodle icons, app limits, widgets and the paper wash are locked. The home screen and Master grey still work.",
                        null,
                    )
                }
                card.addView(button("unlock for good · $price", primary = true) {
                    if (billing?.buy() != true) {
                        Toast.makeText(
                            this, "Google Play is not ready. Check your connection and that InkPhone was installed from Google Play.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                })
                card.addView(note("One payment, once. No subscription."))
                card.addView(button("I already paid: restore", primary = false) {
                    billing?.restore()
                    Toast.makeText(this, "Checking with Google Play…", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    // ---- 1 · Master grey ---------------------------------------------------------

    private fun greyCard() {
        val grey = SystemInk.grayscaleState(this) == true
        val still = SystemInk.animationsOff(this)
        val card = card(R.drawable.ic_grey, "Master grey", "The whole phone in black and white. Every app, every video.", grey)

        if (SystemInk.canWrite(this)) {
            card.addView(button(if (grey) "bring colour back" else "turn the phone grey", primary = !grey) {
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
            card.addView(button("open colour settings", primary = true) { openColourSettings() })
            card.addView(note("You only do this once. Android does not let any app flip this switch for you."))
        } else {
            card.addView(button("turn grey off", primary = true) { openColourSettings() })
            card.addView(note("Switch colour correction off on the screen that opens. The ◐ button on the home screen does the same."))
        }

        card.addView(divider())
        if (still) {
            card.addView(body("✓  Animations are off too"))
        } else {
            card.addView(script("and stop the animations", 23f))
            card.addView(note(
                if (samsung) "In Accessibility, open Vision enhancements and turn on Reduce animations."
                else "In Accessibility, open Colour and motion and turn on Remove animations."
            ))
            card.addView(button("open accessibility", primary = false) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        }
    }

    // ---- 2 · E-ink launcher --------------------------------------------------------

    private fun homeCard() {
        val isHome = isDefaultHome()
        val card = card(
            R.drawable.ic_home_ink, "E-ink launcher",
            "A paper home screen. Apps turn pages instead of scrolling.", isHome,
        )
        card.addView(button(if (isHome) "change home app" else "make it my home screen", primary = !isHome) {
            open(Settings.ACTION_HOME_SETTINGS)
        })
        if (!isHome) card.addView(note("Choose InkPhone Home in the list that opens."))

        card.addView(divider())
        card.addView(script("how should icons look?", 23f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sample = samples.first()
        listOf(
            Triple(IconStyler.DOODLE, "doodle", sample.doodle),
            Triple(IconStyler.INK, "grey", sample.grey),
            Triple(IconStyler.ORIGINAL, "as is", sample.colour),
        ).forEach { (style, label, preview) ->
            val chosen = prefs.iconStyle == style
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = SketchDrawable(density, if (chosen) 0xFFFFFFFF.toInt() else 0, if (chosen) ink else 0x551C1C1C, 14f, shadow = chosen)
                setPadding(dp(6), dp(10), dp(if (chosen) 12 else 6), dp(if (chosen) 12 else 6))
                isClickable = true
                setOnClickListener {
                    prefs.iconStyle = style
                    rebuild()
                }
                addView(ImageView(context).apply { setImageBitmap(preview) }, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(script(label, 21f).apply { gravity = Gravity.CENTER })
            }
            row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(8) })
        }
        card.addView(row)
        customise(card)
    }

    /** Which blocks the home screen shows and how the grid looks. */
    private fun customise(card: LinearLayout) {
        card.addView(divider())
        card.addView(script("build your home screen", 23f))
        card.addView(switch("Clock", prefs.showClock) { prefs.showClock = it })
        card.addView(switch("Handwritten clock", prefs.handClock) { prefs.handClock = it })
        card.addView(switch("Date", prefs.showDate) { prefs.showDate = it })
        card.addView(switch("Screen time today", prefs.showScreenTime) { prefs.showScreenTime = it })
        card.addView(switch("Most used apps", prefs.showMostUsed) { prefs.showMostUsed = it })
        card.addView(switch("Search box", prefs.showSearch) { prefs.showSearch = it })
        card.addView(switch("Grey on/off button", prefs.showGreyButton) { prefs.showGreyButton = it })
        card.addView(switch("App names under icons", prefs.showLabels) { prefs.showLabels = it })

        card.addView(script("icons per row", 23f).apply { setPadding(0, dp(14), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in 3..6) {
            row.addView(
                button("$n", primary = prefs.columns == n) {
                    prefs.columns = n
                    rebuild()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = dp(8)
                    marginEnd = if (n == 6) 0 else dp(8)
                },
            )
        }
        card.addView(row)

        card.addView(divider())
        card.addView(script("widgets from other apps", 23f))
        card.addView(note("Weather, calendar, music: put any widget on the paper home screen, above the search bar."))
        val manager = android.appwidget.AppWidgetManager.getInstance(this)
        val placed = prefs.widgets
        for ((id, height) in placed) {
            val info = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
            val name = info?.loadLabel(packageManager) ?: "Widget no longer available"
            card.addView(body("▤  $name  ·  ${widgetSizeName(height)}").apply {
                background = SketchDrawable(density, 0, 0x551C1C1C, 10f, wobble = 0.6f)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = spaced(10)
                setOnClickListener { editWidget(id, name) }
            })
        }
        if (placed.isNotEmpty()) card.addView(note("Tap a widget to resize, move or remove it."))
        card.addView(button("add a widget", primary = false) {
            startActivity(Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_ADD_WIDGET, true))
        })
        card.addView(switch("Show widgets in black and white", prefs.widgetsGrey) { prefs.widgetsGrey = it })
        card.addView(note("InkPhone's own “Screen time” and “Most used apps” widgets are in your phone's widget list too, for any home screen."))
    }

    private fun widgetSizeName(heightDp: Int) = when {
        heightDp <= 110 -> "small"
        heightDp <= 180 -> "medium"
        heightDp <= 260 -> "large"
        else -> "extra large"
    }

    private fun editWidget(id: Int, name: String) {
        val sizes = listOf("Small" to 100, "Medium" to 160, "Large" to 240, "Extra large" to 340)
        val items = sizes.map { it.first } + listOf("Move up", "Move down", "Remove")
        android.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items.toTypedArray()) { _, which ->
                val list = prefs.widgets.toMutableList()
                val at = list.indexOfFirst { it.first == id }
                if (at >= 0) {
                    when {
                        which < sizes.size -> list[at] = id to sizes[which].second
                        which == sizes.size && at > 0 -> list.add(at - 1, list.removeAt(at))
                        which == sizes.size + 1 && at < list.size - 1 -> list.add(at + 1, list.removeAt(at))
                        which == sizes.size + 2 -> list.removeAt(at)
                    }
                    prefs.widgets = list
                }
                rebuild()
            }
            .show()
    }

    // ---- 3 · App limits ---------------------------------------------------------------

    private fun limitsCard() {
        val limits = prefs.limits()
        val card = card(R.drawable.ic_limit, "App limits", "Give an app minutes per day. When they run out, it locks until tomorrow.", limits.isNotEmpty())
        val usageOk = ScreenTime.hasAccess(this)
        if (!usageOk) {
            card.addView(note("InkPhone needs usage access to count the minutes."))
            card.addView(button("allow usage access", primary = true) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
            return
        }

        val used = runCatching { ScreenTime.perApp(this) }.getOrDefault(emptyMap())
        for ((pkg, minutes) in limits.entries.sortedBy { appName(it.key).lowercase() }) {
            val spent = ((used[pkg] ?: 0L) / 60_000L).toInt()
            val over = spent >= minutes + prefs.bonus(pkg)
            card.addView(body("${if (over) "🔒" else "○"}  ${appName(pkg)}  ·  ${ScreenTime.format(spent)} of ${ScreenTime.format(minutes)}").apply {
                background = SketchDrawable(density, 0, 0x551C1C1C, 10f, wobble = 0.6f)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = spaced(10)
                setOnClickListener { Limits.pick(this@SettingsActivity, prefs, pkg, appName(pkg)) { rebuild() } }
            })
        }
        if (limits.isNotEmpty()) card.addView(note("Tap a limit to change or remove it."))

        card.addView(button("add a limit", primary = limits.isEmpty()) { pickAppForLimit() })
        if (!Settings.canDrawOverlays(this)) {
            card.addView(note("Right now a limit only stops the app being opened from the InkPhone home screen. To lock it from inside the app too, allow this:"))
            card.addView(button("allow display over other apps", primary = false) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
            })
        } else {
            InkService.ensure(this)
            card.addView(note("A locked app shows a “time's up” page with a way back home and a ${Limits.BONUS_MINUTES}-minute extension."))
        }
    }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun pickAppForLimit() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { it to appName(it) }
            .sortedBy { it.second.lowercase() }
        android.app.AlertDialog.Builder(this)
            .setTitle("Limit which app?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                val (pkg, name) = apps[which]
                Limits.pick(this, prefs, pkg, name) { rebuild() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- 4 · Advanced ----------------------------------------------------------------

    private fun advancedCard() {
        val card = card(R.drawable.ic_advanced, "Advanced", "Paper wash, screen time and the debug shortcut.", null)
        card.addView(button(if (showAdvanced) "close" else "open", primary = false) {
            showAdvanced = !showAdvanced
            rebuild()
        })
        if (!showAdvanced) return

        // Paper wash
        card.addView(divider())
        card.addView(script("paper wash over apps", 23f))
        card.addView(note(
            "A paper-grey film over everything. It fades colours but does not remove them. " +
                "It steps aside by itself on sign-in, payment and settings screens, which refuse taps while anything is drawn over them."
        ))
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = ScreenTime.hasAccess(this)
        if (!overlayOk) {
            card.addView(button("allow display over other apps", primary = false) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
            })
        }
        if (!usageOk) {
            card.addView(button("allow usage access", primary = false) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
            card.addView(note("Needed so the wash knows when to step aside."))
        }
        if (overlayOk && usageOk) {
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
        card.addView(script("screen time on the home screen", 23f))
        if (usageOk) {
            val minutes = runCatching { ScreenTime.todayMinutes(this) }.getOrDefault(0)
            card.addView(note("✓  On the phone today: ${ScreenTime.format(minutes)}"))
        } else {
            card.addView(note("Shows today's total on the home screen."))
            card.addView(button("allow usage access", primary = false) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
        }

        // One-tap grey
        card.addView(divider())
        card.addView(script("one-tap master grey (debug)", 23f))
        if (SystemInk.canWrite(this)) {
            card.addView(note("✓  Enabled. Master grey now works in one tap."))
        } else {
            card.addView(note("Connect the phone to a computer once, with USB debugging on, and run this. After that Master grey is a single tap."))
            card.addView(TextView(this).apply {
                text = adbCommand
                typeface = Typeface.MONOSPACE
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextIsSelectable(true)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = SketchDrawable(density, 0x141C1C1C, 0x551C1C1C, 8f)
            }, spaced(8))
            card.addView(button("copy command", primary = false) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            })
        }

        if (prefs.hidden.isNotEmpty()) {
            card.addView(divider())
            card.addView(button("show ${prefs.hidden.size} hidden app(s) again", primary = false) {
                prefs.hidden = emptySet()
                rebuild()
            })
        }
    }

    // ---- actions -----------------------------------------------------------------------

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun openColourSettings() = SystemInk.openColourSettings(this)

    private fun open(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun guarded(change: () -> Unit) {
        runCatching(change).onFailure {
            Toast.makeText(this, "Android refused the change: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- building blocks -----------------------------------------------------------------

    private fun dp(v: Int) = (v * density).toInt()

    private fun spaced(top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    /** Handwritten text, for titles and buttons. */
    private fun script(value: String, sp: Float) = TextView(this).apply {
        text = value
        typeface = hand
        includeFontPadding = false
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /** Plain serif text, for anything that has to be read carefully. */
    private fun body(value: String) = TextView(this).apply {
        text = value
        typeface = Typeface.SERIF
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun note(value: String) = body(value).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        alpha = 0.8f
        setPadding(0, dp(8), 0, 0)
    }

    private fun steps(vararg lines: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, 0)
        lines.forEachIndexed { i, line ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                addView(script("${i + 1}", 22f).apply {
                    gravity = Gravity.CENTER
                    background = SketchDrawable(density, 0, ink, 16f, wobble = 0.3f)
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(body(line).apply { setPadding(dp(10), 0, 0, 0) })
            })
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x331C1C1C)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(18)
            bottomMargin = dp(14)
        }
    }

    /** A hand-drawn card with icon, handwritten title, description and an ON stamp. Returns it for more content. */
    private fun card(icon: Int, title: String, subtitle: String, on: Boolean?): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(density, 0xFFFBF9F3.toInt(), ink, 20f, shadow = true)
            setPadding(dp(20), dp(18), dp(26), dp(28))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(
            script(title, 30f).apply { setPadding(dp(12), 0, dp(6), 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (on == true) {
            // a rubber stamp, pressed on slightly crooked
            head.addView(script("ON", 24f).apply {
                rotation = -9f
                background = SketchDrawable(density, 0, ink, 8f, wobble = 0.8f)
                setPadding(dp(12), dp(5), dp(12), dp(5))
            })
        } else if (on == false) {
            head.addView(script("off", 24f).apply { alpha = 0.4f })
        }
        card.addView(head)
        card.addView(body(subtitle).apply { setPadding(0, dp(10), 0, dp(2)) })
        page.addView(card, spaced(14))
        return card
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = hand
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
        setTextColor(if (primary) paper else ink)
        background = SketchDrawable(density, if (primary) ink else 0, ink, 14f)
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
}
