package app.inkphone

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * The home screen: paper background and a stack of optional blocks (clock,
 * date, screen time, most used apps, search) above an app grid that turns
 * pages like an e-reader instead of scrolling. Which blocks show, and how
 * the grid looks, is chosen in the menu.
 */
class HomeActivity : Activity() {
    private class App(val label: String, val component: ComponentName, val info: ActivityInfo) {
        val key: String get() = component.flattenToShortString()
        val pkg: String get() = component.packageName
    }

    private lateinit var prefs: Prefs
    private lateinit var grid: GridView
    private lateinit var pager: TextView
    private var search: EditText? = null
    private var screenTime: TextView? = null
    private var greyHint: TextView? = null
    private var greyButton: TextView? = null
    private var mostUsed: LinearLayout? = null

    private val io = Executors.newFixedThreadPool(2)
    private val icons = LruCache<String, Bitmap>(400)
    private var apps: List<App> = emptyList()
    private var shown: List<App> = emptyList()
    private var page = 0
    private var columns = 4
    private var perPage = 16
    private var iconStyle = -1
    private var layout = ""
    private val ink by lazy { getColor(R.color.ink) }
    private val serif = Typeface.SERIF
    private val hand: Typeface by lazy { runCatching { resources.getFont(R.font.caveat) }.getOrDefault(serif) }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        iconStyle = prefs.iconStyle
        rebuildUi()
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.iconStyle != iconStyle) {
            iconStyle = prefs.iconStyle
            icons.evictAll()
        }
        if (prefs.homeLayout != layout) rebuildUi()
        refresh()
        updateUsage()
        updateGrey()
        InkService.ensure(this)
        Widgets.refresh(this)
    }

    /** Home pressed while already home: back to the first page. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        search?.setText("")
        page = 0
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val box = search
        if (box != null && box.text.isNotEmpty()) box.setText("") else if (page > 0) turnPage(-page)
    }

    // ---- UI ---------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rebuildUi() {
        layout = prefs.homeLayout
        columns = prefs.columns.coerceIn(3, 6)
        search = null; screenTime = null; greyHint = null; greyButton = null; mostUsed = null
        setContentView(buildUi())
    }

    private fun script(value: String, sp: Float) = TextView(this).apply {
        text = value
        typeface = hand
        includeFontPadding = false
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        if (prefs.showClock) {
            root.addView(TextClock(this).apply {
                format12Hour = "h:mm"
                format24Hour = "HH:mm"
                typeface = if (prefs.handClock) hand else serif
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (prefs.handClock) 76f else 60f)
                includeFontPadding = false
            })
        }
        if (prefs.showDate) {
            root.addView(TextClock(this).apply {
                format12Hour = "EEEE, d MMMM"
                format24Hour = "EEEE, d MMMM"
                typeface = hand
                includeFontPadding = false
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            })
        }
        if (prefs.showScreenTime) {
            screenTime = script("", 21f).apply {
                alpha = 0.8f
                setPadding(0, dp(4), 0, dp(6))
                setOnClickListener { openSettings() }
            }
            root.addView(screenTime)
        }

        greyHint = TextView(this).apply {
            text = "Apps still open in colour. Tap to make the whole phone black and white (hold to hide this)."
            typeface = serif
            setTextColor(getColor(R.color.paper))
            setBackgroundColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            setOnClickListener { openSettings() }
            setOnLongClickListener {
                prefs.greyHintDismissed = true
                visibility = View.GONE
                true
            }
        }
        root.addView(greyHint)

        if (prefs.showMostUsed) {
            mostUsed = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                background = SketchDrawable(resources.displayMetrics.density, 0, ink, 16f)
                setPadding(dp(10), dp(8), dp(10), dp(10))
            }
            root.addView(mostUsed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            })
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (prefs.showSearch) {
            search = EditText(this).apply {
                hint = "Search apps"
                typeface = serif
                setSingleLine()
                setTextColor(ink)
                setHintTextColor(0x801C1C1C.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = null
                setPadding(0, dp(8), 0, dp(8))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        page = 0
                        refresh()
                    }
                })
            }
            bar.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        if (prefs.showGreyButton) {
            greyButton = script("", 22f).apply {
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { toggleGrey() }
            }
            bar.addView(greyButton)
        }
        bar.addView(script("menu", 22f).apply {
            setPadding(dp(14), dp(8), dp(6), dp(8))
            setOnClickListener { openSettings() }
        })
        root.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(ink) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        grid = GridView(this).apply {
            numColumns = columns
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = appAdapter
            setOnItemClickListener { _, _, i, _ -> launch(appAdapter.getItem(i)) }
            setOnItemLongClickListener { _, _, i, _ -> appMenu(appAdapter.getItem(i)); true }
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    val fit = columns * max(1, (bottom - top) / cellHeight())
                    if (fit != perPage) {
                        perPage = fit
                        post { refresh() }
                    }
                }
            }
        }
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val main = if (abs(vy) > abs(vx)) vy else vx
                if (abs(main) < dp(300)) return false
                turnPage(if (main < 0) 1 else -1)
                return true
            }
        })
        grid.setOnTouchListener { _, e -> swipe.onTouchEvent(e); false }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pager = script("", 22f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            // Tap the left or right half to turn the page.
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) turnPage(if (e.x > v.width / 2) 1 else -1)
                true
            }
        }
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun iconDp() = when (columns) { 3 -> 64; 4 -> 56; 5 -> 48; else -> 42 }
    private fun cellHeight() = dp(iconDp() + if (prefs.showLabels) 40 else 22)

    private val pageCount: Int get() = max(1, (shown.size + perPage - 1) / perPage)

    private fun turnPage(delta: Int) {
        val target = (page + delta).coerceIn(0, pageCount - 1)
        if (target == page) return
        page = target
        refresh()
    }

    private fun refresh() {
        val query = search?.text?.toString()?.trim().orEmpty()
        val pinned = prefs.pinned
        val hidden = prefs.hidden
        shown = apps
            .filter { if (query.isEmpty()) it.key !in hidden else it.label.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<App> { it.key !in pinned }.thenBy { it.label.lowercase() })
        page = page.coerceIn(0, pageCount - 1)
        appAdapter.notifyDataSetChanged()
        pager.text = if (pageCount > 1) "‹     ${page + 1} / $pageCount     ›" else ""
    }

    // ---- blocks -------------------------------------------------------------

    private fun updateUsage() {
        if (!ScreenTime.hasAccess(this)) {
            screenTime?.text = "tap to turn on today's screen time"
            mostUsed?.visibility = View.GONE
            return
        }
        io.execute {
            val minutes = runCatching { ScreenTime.todayMinutes(this) }.getOrDefault(0)
            val top = if (mostUsed != null) runCatching { ScreenTime.topApps(this, 4) }.getOrDefault(emptyList()) else emptyList()
            val size = dp(44)
            val tiles = top.mapNotNull { (pkg, mins) ->
                runCatching {
                    Triple(pkg, mins, IconStyler.render(packageManager.getApplicationIcon(pkg), iconStyle, size, pkg.hashCode()))
                }.getOrNull()
            }
            runOnUiThread {
                screenTime?.text = "on the phone today: ${ScreenTime.format(minutes)}"
                showMostUsed(tiles)
            }
        }
    }

    private fun showMostUsed(tiles: List<Triple<String, Int, Bitmap>>) {
        val box = mostUsed ?: return
        box.removeAllViews()
        if (tiles.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(script("most used today", 20f).apply { alpha = 0.8f })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((pkg, mins, bmp) in tiles) {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                addView(ImageView(context).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(context).apply {
                    val limit = prefs.limit(pkg)
                    text = if (limit > 0) "${ScreenTime.format(mins)} / ${ScreenTime.format(limit)}" else ScreenTime.format(mins)
                    typeface = serif
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
                setOnClickListener { launchPackage(pkg) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(row)
    }

    private fun updateGrey() {
        val grey = SystemInk.grayscaleState(this) == true
        greyButton?.text = if (grey) "◐ colour" else "◐ grey"
        greyHint?.visibility = if (grey || prefs.greyHintDismissed) View.GONE else View.VISIBLE
    }

    /** One tap when the debug permission is there; otherwise straight to Android's colour screen. */
    private fun toggleGrey() {
        val grey = SystemInk.grayscaleState(this) == true
        if (SystemInk.canWrite(this)) {
            runCatching {
                SystemInk.setGrayscale(this, !grey)
                SystemInk.setAnimationsOff(this, !grey)
            }
            updateGrey()
        } else {
            Toast.makeText(
                this, if (grey) "Switch colour correction off here" else "Turn on colour correction and choose Greyscale",
                Toast.LENGTH_LONG,
            ).show()
            SystemInk.openColourSettings(this)
        }
    }

    // ---- apps -------------------------------------------------------------

    private fun loadApps() {
        io.execute {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = packageManager.queryIntentActivities(intent, 0)
                .map { App(it.loadLabel(packageManager).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.activityInfo) }
                .distinctBy { it.key }
            runOnUiThread {
                apps = list
                refresh()
            }
        }
    }

    private fun launch(app: App) {
        if (app.pkg != packageName && Limits.reached(this, prefs, app.pkg)) {
            timeUp(app.pkg, app.label) { launch(app) }
            return
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun launchPackage(pkg: String) {
        val app = apps.firstOrNull { it.pkg == pkg }
        if (app != null) launch(app) else packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it) } }
    }

    private fun timeUp(pkg: String, label: String, retry: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Time's up for $label")
            .setMessage("You gave it ${Limits.label(prefs.limit(pkg)).lowercase()}, and that is used up for today.")
            .setPositiveButton("OK", null)
            .setNeutralButton("${Limits.BONUS_MINUTES} more minutes") { _, _ ->
                prefs.addBonus(pkg, Limits.BONUS_MINUTES)
                retry()
            }
            .show()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun appMenu(app: App) {
        val pinned = app.key in prefs.pinned
        val hidden = app.key in prefs.hidden
        val limit = prefs.limit(app.pkg)
        val items = arrayOf(
            if (pinned) "Unpin from top" else "Pin to top",
            if (hidden) "Show on home" else "Hide from home (still searchable)",
            if (limit > 0) "Daily limit: ${Limits.label(limit).lowercase()}" else "Set a daily limit",
            "App info",
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> prefs.pinned = if (pinned) prefs.pinned - app.key else prefs.pinned + app.key
                    1 -> prefs.hidden = if (hidden) prefs.hidden - app.key else prefs.hidden + app.key
                    2 -> Limits.pick(this, prefs, app.pkg, app.label) { updateUsage() }
                    3 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    )
                }
                refresh()
            }
            .show()
    }

    private val appAdapter = object : BaseAdapter() {
        override fun getCount() = (shown.size - page * perPage).coerceIn(0, perPage)
        override fun getItem(position: Int) = shown[page * perPage + position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val iconPx = dp(iconDp())
            val cell = (convertView as? LinearLayout)?.takeIf { it.tag == layout } ?: LinearLayout(this@HomeActivity).apply {
                tag = layout
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight())
                addView(ImageView(context), LinearLayout.LayoutParams(iconPx, iconPx))
                addView(TextView(context).apply {
                    typeface = serif
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(2), dp(4), dp(2), 0)
                    visibility = if (prefs.showLabels) View.VISIBLE else View.GONE
                })
            }
            val app = getItem(position)
            val image = cell.getChildAt(0) as ImageView
            (cell.getChildAt(1) as TextView).text = app.label

            val cacheKey = "${app.key}|$iconStyle|$iconPx"
            image.tag = cacheKey
            val cached = icons.get(cacheKey)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                image.setImageDrawable(null)
                val style = iconStyle
                io.execute {
                    val bmp = runCatching {
                        IconStyler.render(app.info.loadIcon(packageManager), style, iconPx, app.key.hashCode())
                    }.getOrNull() ?: return@execute
                    icons.put(cacheKey, bmp)
                    runOnUiThread { if (image.tag == cacheKey) image.setImageBitmap(bmp) }
                }
            }
            return cell
        }
    }
}
