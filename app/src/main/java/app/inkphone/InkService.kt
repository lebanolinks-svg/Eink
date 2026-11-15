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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * Draws the e-ink layer over every app: a paper tint with grain, an optional
 * black/white "page refresh" flash when the foreground screen changes, and a
 * request for the panel's slowest refresh rate.
 *
 * Uses the ordinary "display over other apps" permission. The layer never
 * receives touches; Android only lets touches pass through an overlay that is
 * at most 80% opaque, so the whole window is capped there.
 */
class InkService : Service() {
    private lateinit var prefs: Prefs
    private var overlay: InkOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastScreen: String? = null
    private var lastPoll = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(poll)
            if (intent?.action == Intent.ACTION_SCREEN_ON) handler.post(poll)
        }
    }

    /** Watches which screen is in front (needs usage access) and flashes when it changes. */
    private val poll = object : Runnable {
        override fun run() {
            if (prefs.flash && ScreenTime.hasAccess(this@InkService)) {
                val current = runCatching { foregroundScreen() }.getOrNull()
                if (current != null && current != lastScreen) {
                    if (lastScreen != null) flash()
                    lastScreen = current
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val view = InkOverlay(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.alpha = MAX_PASS_THROUGH_ALPHA
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val added = runCatching { windowManager().addView(view, lp) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }
        overlay = view
        running = true
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        apply()
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun windowManager() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Paper screen layer", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ink)
            .setContentTitle("Paper screen layer is on")
            .setContentText("Tap to change or turn off")
            .setContentIntent(open)
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
        if (!prefs.layerOn) {
            stopSelf()
            return
        }
        view.paper = prefs.paper
        view.strength = prefs.paperStrength / 100f
        view.invalidate()

        val lp = view.layoutParams as WindowManager.LayoutParams
        val modeId = if (prefs.lowRefresh) slowestModeId() else 0
        if (lp.preferredDisplayModeId != modeId) {
            lp.preferredDisplayModeId = modeId
            runCatching { windowManager().updateViewLayout(view, lp) }
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
        overlay?.let { view ->
            overlay = null
            prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { windowManager().removeView(view) }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "layer"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 400L
        private const val EVENT_RESUMED = 1 // UsageEvents.Event.ACTIVITY_RESUMED
        private const val MAX_PASS_THROUGH_ALPHA = 0.8f

        @Volatile
        var running = false
            private set

        /** Starts the layer if the user wants it and the permission is there. Safe to call often. */
        fun ensure(context: Context) {
            if (running || !Prefs(context).layerOn || !Settings.canDrawOverlays(context)) return
            runCatching { context.startForegroundService(Intent(context, InkService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InkService::class.java))
        }
    }
}

/** Brings the layer back after a reboot or an app update. */
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
            // Warm, slightly grey wash: knocks back pure whites and pure blacks like a reflective panel.
            canvas.drawColor(Color.argb((strength * 80).toInt(), 0xD9, 0xCF, 0xB6))
            grain.alpha = (strength * 255).toInt()
            canvas.drawPaint(grain)
        }
        flashColor?.let { canvas.drawColor(it) }
    }
}
