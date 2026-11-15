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
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * The home screen: paper background, clock, today's screen time and the app grid.
 * The grid turns pages like an e-reader instead of scrolling.
 */
class HomeActivity : Activity() {
    private class App(val label: String, val component: ComponentName, val info: ActivityInfo) {
        val key: String get() = component.flattenToShortString()
    }

    private lateinit var prefs: Prefs
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var screenTime: TextView
    private lateinit var greyHint: TextView
    private lateinit var pager: TextView

    private val io = Executors.newFixedThreadPool(2)
    private val icons = LruCache<String, Bitmap>(400)
    private var apps: List<App> = emptyList()
    private var shown: List<App> = emptyList()
    private var page = 0
    private var perPage = COLUMNS * 4
    private var iconStyle = -1
    private val ink by lazy { getColor(R.color.ink) }
    private val serif = Typeface.SERIF

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        iconStyle = prefs.iconStyle
        setContentView(buildUi())
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
        refresh()
        updateScreenTime()
        greyHint.visibility =
            if (SystemInk.grayscaleState(this) == true || prefs.greyHintDismissed) View.GONE else View.VISIBLE
        InkService.ensure(this)
    }

    /** Home pressed while already home: back to the first page. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        search.setText("")
        page = 0
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) search.setText("") else if (page > 0) turnPage(-page)
    }

    // ---- UI ---------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        root.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            typeface = serif
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 60f)
            includeFontPadding = false
        })
        root.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            typeface = serif
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        screenTime = TextView(this).apply {
            typeface = Typeface.create(serif, Typeface.ITALIC)
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { openSettings() }
        }
        root.addView(screenTime)

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

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
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
        bar.addView(TextView(this).apply {
            text = "Settings"
            typeface = serif
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener { openSettings() }
        })
        root.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(ink) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        grid = GridView(this).apply {
            numColumns = COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = appAdapter
            setOnItemClickListener { _, _, i, _ -> launch(appAdapter.getItem(i)) }
            setOnItemLongClickListener { _, _, i, _ -> appMenu(appAdapter.getItem(i)); true }
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    val fit = COLUMNS * max(1, (bottom - top) / dp(CELL_DP))
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

        pager = TextView(this).apply {
            typeface = serif
            gravity = Gravity.CENTER
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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

    private val pageCount: Int get() = max(1, (shown.size + perPage - 1) / perPage)

    private fun turnPage(delta: Int) {
        val target = (page + delta).coerceIn(0, pageCount - 1)
        if (target == page) return
        page = target
        refresh()
    }

    private fun refresh() {
        val query = search.text.toString().trim()
        val pinned = prefs.pinned
        val hidden = prefs.hidden
        shown = apps
            .filter { if (query.isEmpty()) it.key !in hidden else it.label.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<App> { it.key !in pinned }.thenBy { it.label.lowercase() })
        page = page.coerceIn(0, pageCount - 1)
        appAdapter.notifyDataSetChanged()
        pager.text = if (pageCount > 1) "‹     ${page + 1} / $pageCount     ›" else ""
    }

    private fun updateScreenTime() {
        if (!ScreenTime.hasAccess(this)) {
            screenTime.text = "Tap to turn on today's screen time"
            return
        }
        io.execute {
            val minutes = runCatching { ScreenTime.todayMinutes(this) }.getOrDefault(0)
            runOnUiThread { screenTime.text = "On the phone today: ${ScreenTime.format(minutes)}" }
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
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun appMenu(app: App) {
        val pinned = app.key in prefs.pinned
        val hidden = app.key in prefs.hidden
        val items = arrayOf(
            if (pinned) "Unpin from top" else "Pin to top",
            if (hidden) "Show on home" else "Hide from home (still searchable)",
            "App info",
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> prefs.pinned = if (pinned) prefs.pinned - app.key else prefs.pinned + app.key
                    1 -> prefs.hidden = if (hidden) prefs.hidden - app.key else prefs.hidden + app.key
                    2 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}"))
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
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CELL_DP))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
                addView(TextView(context).apply {
                    typeface = serif
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(2), dp(4), dp(2), 0)
                })
            }
            val app = getItem(position)
            val image = cell.getChildAt(0) as ImageView
            (cell.getChildAt(1) as TextView).text = app.label

            val cacheKey = "${app.key}|$iconStyle"
            image.tag = cacheKey
            val cached = icons.get(cacheKey)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                image.setImageDrawable(null)
                val style = iconStyle
                io.execute {
                    val bmp = runCatching {
                        IconStyler.render(app.info.loadIcon(packageManager), style, dp(ICON_DP), app.key.hashCode())
                    }.getOrNull() ?: return@execute
                    icons.put(cacheKey, bmp)
                    runOnUiThread { if (image.tag == cacheKey) image.setImageBitmap(bmp) }
                }
            }
            return cell
        }
    }

    private companion object {
        const val COLUMNS = 4
        const val ICON_DP = 56
        const val CELL_DP = 96
    }
}
