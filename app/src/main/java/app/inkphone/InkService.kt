package app.inkphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * The optional paper wash drawn over other apps: a grey film with grain, a
 * black/white "page refresh" flash when the screen changes, and a request for
 * the panel's slowest refresh rate.
 *
 * Android blocks taps on sign-in, payment, install and permission screens
 * while any app is drawing over them. So the wash watches which app is in
 * front and takes itself off the screen completely for those, and the
 * notification has a Pause button for anything it does not recognise.
 */
class InkService : Service() {
    private lateinit var prefs: Prefs
    private var overlay: InkOverlay? = null
    private lateinit var params: WindowManager.LayoutParams
    private var attached = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastScreen: String? = null
    private var lastPoll = 0L
    private var sensitive = false
    private var pausedUntil = 0L

    // app limits: which app is in front, how long it had been used today when it came
    // to the front, and when that was
    private var currentPkg: String? = null
    private var baseUsedMs = 0L
    private var sinceMs = 0L
    private var lockView: View? = null
    private var lockedPkg: String? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(poll)
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                restartClock() // time with the screen off does not count
                handler.post(poll)
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val screen = runCatching { foregroundScreen() }.getOrNull()
            var changed = false
            if (screen != null && screen != lastScreen) {
                changed = lastScreen != null
                lastScreen = screen
                val pkg = screen.substringBefore('/')
                if (pkg != currentPkg) {
                    currentPkg = pkg
                    sensitive = isSensitive(pkg)
                    restartClock()
                    Widgets.refresh(this@InkService)
                }
            }
            val now = SystemClock.uptimeMillis()
            val pkg = currentPkg
            val limit = if (pkg != null && pkg != packageName) prefs.limit(pkg) else 0
            val over = limit > 0 && baseUsedMs + (now - sinceMs) >= (limit + prefs.bonus(pkg!!)) * 60_000L
            setLocked(if (over) pkg else null)
            val show = prefs.layerOn && !over && !sensitive && now >= pausedUntil
            setAttached(show)
            if (show && changed && prefs.flash) flash()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startInForeground()
        if (!Settings.canDrawOverlays(this) || !ScreenTime.hasAccess(this)) {
            stopSelf()
            return
        }

        overlay = InkOverlay(this)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        // Android only lets touches through an overlay that is at most 80% opaque.
        params.alpha = MAX_PASS_THROUGH_ALPHA
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        running = true
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        apply()
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE && overlay != null) {
            pausedUntil = SystemClock.uptimeMillis() + PAUSE_MS
            setAttached(false)
        }
        return START_STICKY
    }

    private fun restartClock() {
        sinceMs = SystemClock.uptimeMillis()
        val pkg = currentPkg
        baseUsedMs = if (pkg == null) 0L else runCatching { ScreenTime.perApp(this)[pkg] ?: 0L }.getOrDefault(0L)
    }

    /** Covers the app with a "time's up" page, or takes that page away. */
    private fun setLocked(pkg: String?) {
        if (pkg == lockedPkg) return
        lockView?.let { runCatching { windowManager().removeView(it) } }
        lockView = null
        lockedPkg = null
        if (pkg == null) return
        val view = LockScreen.build(this, prefs, pkg) { setLocked(null) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )
        if (runCatching { windowManager().addView(view, lp) }.isSuccess) {
            lockView = view
            lockedPkg = pkg
        }
    }

    private fun windowManager() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Puts the wash on screen or takes it off entirely; a hidden-but-present window would still block taps. */
    private fun setAttached(show: Boolean) {
        val view = overlay ?: return
        if (show == attached) return
        attached = if (show) {
            runCatching { windowManager().addView(view, params) }.isSuccess
        } else {
            runCatching { windowManager().removeView(view) }
            false
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "App limits and paper wash", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, InkService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle("InkPhone is on")
            .setContentText("Keeping app limits and the paper wash. Pause the wash here if a screen will not take taps.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Pause wash 5 minutes", pause).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun apply() {
        val view = overlay ?: return
        if (!prefs.layerOn && prefs.limits().isEmpty()) {
            stopSelf()
            return
        }
        view.paper = prefs.paper
        view.strength = prefs.paperStrength / 100f
        view.invalidate()

        val modeId = if (prefs.lowRefresh) slowestModeId() else 0
        if (params.preferredDisplayModeId != modeId) {
            params.preferredDisplayModeId = modeId
            if (attached) runCatching { windowManager().updateViewLayout(view, params) }
        }
    }

    /** The lowest refresh rate the panel offers at the current resolution (usually 60 Hz, lower on LTPO panels). */
    private fun slowestModeId(): Int {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { it.refreshRate }
            ?.modeId ?: 0
    }

    /** The most recently resumed activity, or null if nothing changed since the last poll. */
    private fun foregroundScreen(): String? {
        val now = System.currentTimeMillis()
        val from = if (lastPoll == 0L) now - 60_000 else lastPoll
        lastPoll = now
        val events = (getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(from, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.getNextEvent(event)) {
            if (event.eventType == EVENT_RESUMED) latest = "${event.packageName}/${event.className}"
        }
        return latest
    }

    // Stepped by hand rather than with an animator: animators stop working when
    // system animations are switched off, which is exactly when this is wanted.
    private fun flash() {
        val view = overlay ?: return
        view.setFlash(Color.BLACK)
        handler.postDelayed({ view.setFlash(Color.WHITE) }, 70)
        handler.postDelayed({ view.setFlash(null) }, 150)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (overlay != null) {
            setLocked(null)
            setAttached(false)
            overlay = null
            prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
            runCatching { unregisterReceiver(screenReceiver) }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "layer"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 400L
        private const val PAUSE_MS = 5 * 60_000L
        private const val EVENT_RESUMED = 1 // UsageEvents.Event.ACTIVITY_RESUMED
        private const val MAX_PASS_THROUGH_ALPHA = 0.8f
        private const val ACTION_PAUSE = "app.inkphone.PAUSE"

        /** Apps whose screens refuse taps while anything is drawn over them. */
        private val SENSITIVE_APPS = setOf(
            "com.google.android.gms",          // Google sign-in and account screens
            "com.google.android.gsf.login",
            "com.android.vending",             // Play Store purchases
            "com.google.android.apps.walletnfcrel",
            "com.android.settings",            // permission and security switches
            "com.osp.app.signin",              // Samsung account
            "com.samsung.android.spay",
            "com.samsung.android.samsungpass",
        )
        private val SENSITIVE_WORDS = listOf(
            "packageinstaller", "permissioncontroller", "bank", "wallet", "paypal",
            "authenticator", "password", "keychain", ".pay", "securitycenter",
        )

        fun isSensitive(packageName: String): Boolean {
            val name = packageName.lowercase()
            return name in SENSITIVE_APPS || SENSITIVE_WORDS.any { it in name }
        }

        @Volatile
        var running = false
            private set

        /** Starts the wash if the user wants it and both permissions are there. Safe to call often. */
        fun ensure(context: Context) {
            val prefs = Prefs(context)
            if (running || (!prefs.layerOn && prefs.limits().isEmpty())) return
            if (!Settings.canDrawOverlays(context) || !ScreenTime.hasAccess(context)) return
            runCatching { context.startForegroundService(Intent(context, InkService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InkService::class.java))
        }
    }
}

/** The page shown over an app whose daily limit has run out. */
private object LockScreen {
    fun build(c: Context, prefs: Prefs, pkg: String, dismiss: () -> Unit): View {
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val ink = c.getColor(R.color.ink)
        val paper = c.getColor(R.color.paper)
        val hand = runCatching { c.resources.getFont(R.font.caveat) }.getOrDefault(android.graphics.Typeface.SERIF)
        val name = runCatching {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("this app")

        fun text(value: String, sp: Float, face: android.graphics.Typeface) = android.widget.TextView(c).apply {
            text = value
            typeface = face
            gravity = android.view.Gravity.CENTER
            setTextColor(ink)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        }
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = text(label, 26f, hand).apply {
            setTextColor(if (primary) paper else ink)
            background = SketchDrawable(d, if (primary) ink else 0, ink, 14f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { onClick() }
        }

        return android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(paper)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true // swallow taps so the app underneath cannot be used
            addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.ic_limit) },
                android.widget.LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(text("time's up", 60f, hand))
            addView(text("You gave $name ${Limits.label(prefs.limit(pkg)).lowercase()}. That is used up for today.", 17f,
                android.graphics.Typeface.SERIF).apply { setPadding(0, dp(8), 0, dp(28)) })
            val wide = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(button("back to home", primary = true) {
                runCatching {
                    c.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                dismiss()
            }, wide)
            addView(button("${Limits.BONUS_MINUTES} more minutes", primary = false) {
                prefs.addBonus(pkg, Limits.BONUS_MINUTES)
                dismiss()
            }, android.widget.LinearLayout.LayoutParams(wide).apply { topMargin = dp(14) })
        }
    }
}

/** Brings the wash back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = InkService.ensure(context)
}

private class InkOverlay(context: Context) : View(context) {
    var paper = true
    var strength = 0.4f
    private var flashColor: Int? = null

    private val grain = Paint().apply {
        val size = 128
        val rnd = java.util.Random(11)
        val px = IntArray(size * size) {
            val shade = if (rnd.nextInt(3) == 0) 255 else 0 // mostly dark specks, some light fibres
            Color.argb(rnd.nextInt(58), shade, shade, shade)
        }
        val tile = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun setFlash(color: Int?) {
        flashColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (paper && strength > 0f) {
            // Paper-grey wash. Mixing every pixel towards one grey fades colours and pulls
            // pure white and pure black together, like a reflective panel. At full strength
            // (with the 80% window cap) about two thirds of the colour is gone.
            canvas.drawColor(Color.argb((strength * 204).toInt(), 0xC9, 0xC5, 0xB8))
            grain.alpha = (strength * 255).toInt()
            canvas.drawPaint(grain)
        }
        flashColor?.let { canvas.drawColor(it) }
    }
}
