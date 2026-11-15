package app.inkphone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
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
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Draws the e-ink layer over every app: a paper tint with grain, an optional
 * black/white "page refresh" flash on screen changes, and a request for the
 * panel's slowest refresh rate. The layer never receives touches and the
 * service does not read window content.
 */
class InkService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private var overlay: InkOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFlash = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        val view = InkOverlay(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, lp)
        overlay = view
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        running = true
        apply()
    }

    private fun apply() {
        val view = overlay ?: return
        view.paper = prefs.paper
        view.strength = prefs.paperStrength / 100f
        view.invalidate()

        val lp = view.layoutParams as WindowManager.LayoutParams
        val modeId = if (prefs.lowRefresh) slowestModeId() else 0
        if (lp.preferredDisplayModeId != modeId) {
            lp.preferredDisplayModeId = modeId
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(view, lp)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!prefs.flash || event.packageName == "com.android.systemui") return
        val now = SystemClock.uptimeMillis()
        if (now - lastFlash < 700) return
        lastFlash = now
        flash()
    }

    // Stepped by hand rather than with an animator: animators stop working when
    // system animations are switched off, which is exactly when this is wanted.
    private fun flash() {
        val view = overlay ?: return
        view.setFlash(Color.BLACK)
        handler.postDelayed({ view.setFlash(Color.WHITE) }, 70)
        handler.postDelayed({ view.setFlash(null) }, 150)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val view = overlay ?: return
        overlay = null
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    companion object {
        @Volatile
        var running = false
            private set
    }
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
            Color.argb(rnd.nextInt(46), shade, shade, shade)
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
            canvas.drawColor(Color.argb((strength * 64).toInt(), 0xD9, 0xCF, 0xB6))
            grain.alpha = (strength * 255).toInt()
            canvas.drawPaint(grain)
        }
        flashColor?.let { canvas.drawColor(Color.argb(215, Color.red(it), Color.green(it), Color.blue(it))) }
    }
}
