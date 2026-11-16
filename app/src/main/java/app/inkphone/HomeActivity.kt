package app.inkphone

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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

    // widgets from other apps
    private val widgetHost by lazy { AppWidgetHost(applicationContext, WIDGET_HOST_ID) }
    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private var pendingWidget = -1
    private val greyLayer = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Pro.touch(this)
        iconStyle = Pro.iconStyle(this, prefs)
        rebuildUi()
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
        if (intent?.getBooleanExtra(EXTRA_ADD_WIDGET, false) == true) pickWidget()
    }

    // The host only delivers widget updates while it is listening.
    override fun onStart() {
        super.onStart()
        runCatching { widgetHost.startListening() }
    }

    override fun onStop() {
        runCatching { widgetHost.stopListening() }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (Pro.iconStyle(this, prefs) != iconStyle) {
            iconStyle = Pro.iconStyle(this, prefs)
            icons.evictAll()
        }
        if (layoutKey() != layout) rebuildUi()
        forgetRemovedWidgets()
        refresh()
        updateUsage()
        updateGrey()
        InkService.ensure(this)
        Widgets.refresh(this)
    }

    /** Home pressed while already home: back to the first page. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_ADD_WIDGET, false) == true) {
            pickWidget()
            return
        }
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

    /** Home layout choices plus whether the extras are unlocked; the page is rebuilt when either changes. */
    private fun layoutKey() = prefs.homeLayout + Pro.unlocked(this)

    private fun rebuildUi() {
        layout = layoutKey()
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

    /** A clearly visible button: a pen-drawn pill, solid ink for the main one. */
    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = script(label, 24f).apply {
        gravity = Gravity.CENTER
        setTextColor(if (filled) getColor(R.color.paper) else ink)
        background = SketchDrawable(resources.displayMetrics.density, if (filled) ink else 0xFFFBF9F3.toInt(), ink, 14f, wobble = 0.7f)
        setPadding(dp(16), dp(9), dp(16), dp(10))
        minWidth = dp(72)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun pillParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            // Android 15+ draws apps under the status and navigation bars; keep the page clear of them.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(
                    dp(20) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight, dp(8) + insets.systemWindowInsetBottom,
                )
                insets
            }
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

        val pro = Pro.unlocked(this)
        if (!pro) {
            root.addView(TextView(this).apply {
                text = "Your free week is over. Doodle icons, app limits and widgets are locked. Tap to unlock for good."
                typeface = serif
                setTextColor(getColor(R.color.paper))
                setBackgroundColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { openSettings() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }
        if (prefs.showMostUsed && pro) {
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

        if (pro) addWidgets(root)

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
            greyButton = pill("", filled = false) { toggleGrey() }
            bar.addView(greyButton, pillParams())
        }
        bar.addView(pill("menu", filled = true) { openSettings() }, pillParams())
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
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

        pager = script("", 28f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
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
        pager.text = if (pageCount > 1) "◀      ${page + 1} / $pageCount      ▶" else ""
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

    // ---- widgets from other apps -------------------------------------------

    /** Stacks the saved widgets, full width, above the search bar. */
    private fun addWidgets(root: LinearLayout) {
        val density = resources.displayMetrics.density
        val widthDp = (resources.displayMetrics.widthPixels / density).toInt() - 40
        for ((id, heightDp) in prefs.widgets) {
            val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull() ?: continue
            val view = runCatching { widgetHost.createView(this, id, info) }.getOrNull() ?: continue
            runCatching {
                widgetManager.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                })
            }
            if (prefs.widgetsGrey) view.setLayerType(View.LAYER_TYPE_HARDWARE, greyLayer)
            root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
                topMargin = dp(6)
                bottomMargin = dp(2)
            })
        }
    }

    private fun pickWidget() {
        val providers = runCatching { widgetManager.installedProviders }.getOrDefault(emptyList())
            .map { it to "${it.loadLabel(packageManager)}  ·  ${appName(it.provider.packageName)}" }
            .sortedBy { it.second.lowercase() }
        if (providers.isEmpty()) {
            Toast.makeText(this, "No widgets found on this phone", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add which widget?")
            .setItems(providers.map { it.second }.toTypedArray()) { _, which -> addWidget(providers[which].first) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Step 1: reserve an id and get Android's permission to show this widget here. */
    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = runCatching { widgetHost.allocateAppWidgetId() }.getOrNull() ?: return
        pendingWidget = id
        val allowed = runCatching { widgetManager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (allowed) {
            configureWidget(id)
        } else {
            val ask = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            if (runCatching { startActivityForResult(ask, REQUEST_BIND) }.isFailure) dropWidget(id)
        }
    }

    /** Step 2: some widgets want to be set up (choose a city, an account) before they show. */
    private fun configureWidget(id: Int) {
        val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull()
        if (info == null) {
            dropWidget(id)
            return
        }
        val started = info.configure != null && runCatching {
            widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE, null)
        }.isSuccess
        if (!started) keepWidget(id)
    }

    /** Step 3: save it and show it. */
    private fun keepWidget(id: Int) {
        pendingWidget = -1
        val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull()
        val density = resources.displayMetrics.density
        val heightDp = ((info?.minHeight ?: 0) / density).toInt().coerceIn(90, 260)
        prefs.widgets = prefs.widgets.filter { it.first != id } + (id to heightDp)
        rebuildUi()
        refresh()
        updateUsage()
        updateGrey()
    }

    private fun dropWidget(id: Int) {
        pendingWidget = -1
        runCatching { widgetHost.deleteAppWidgetId(id) }
    }

    /** Frees widgets the user removed in the menu. */
    private fun forgetRemovedWidgets() {
        val keep = prefs.widgets.map { it.first }.toSet()
        runCatching {
            widgetHost.appWidgetIds.filter { it !in keep && it != pendingWidget }.forEach { widgetHost.deleteAppWidgetId(it) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget) ?: pendingWidget
        if (id == -1) return
        when {
            resultCode != RESULT_OK -> dropWidget(id)
            requestCode == REQUEST_BIND -> configureWidget(id)
            else -> keepWidget(id)
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

    companion object {
        /** Start the home screen with this extra to open the widget picker. */
        const val EXTRA_ADD_WIDGET = "add_widget"
        private const val WIDGET_HOST_ID = 1024
        private const val REQUEST_BIND = 71
        private const val REQUEST_CONFIGURE = 72
    }
}
