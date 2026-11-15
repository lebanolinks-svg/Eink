package app.inkphone

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/** System-wide switches: grayscale display and animations. Both need WRITE_SECURE_SETTINGS (one ADB command). */
object SystemInk {
    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val DALTONIZER = "accessibility_display_daltonizer"
    private const val MONOCHROME = 0
    private val ANIMATION_SCALES = listOf(
        Settings.Global.WINDOW_ANIMATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.ANIMATOR_DURATION_SCALE,
    )

    fun canWrite(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * True/false when the system greyscale state can be read, null when this phone hides it.
     * Covers stock Android's colour correction and Samsung's own greyscale switch.
     */
    fun grayscaleState(c: Context): Boolean? {
        val cr = c.contentResolver
        val stock = runCatching {
            Settings.Secure.getInt(cr, DALTONIZER_ENABLED, 0) == 1 &&
                Settings.Secure.getInt(cr, DALTONIZER, -1) == MONOCHROME
        }.getOrNull()
        val samsung = runCatching { Settings.System.getInt(cr, "greyscale_mode", 0) == 1 }.getOrDefault(false) ||
            runCatching { Settings.Secure.getInt(cr, "greyscale_mode", 0) == 1 }.getOrDefault(false)
        return if (samsung) true else stock
    }

    /** Stock Android has a dedicated colour correction screen; other brands land on Accessibility. */
    fun openColourSettings(c: Context) {
        val actions = listOf(
            "com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS",
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in actions) {
            val intent = android.content.Intent(action)
            if (c !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { c.startActivity(intent) }.isSuccess) return
        }
    }

    fun isGrayscale(c: Context): Boolean = grayscaleState(c) == true

    fun setGrayscale(c: Context, on: Boolean) {
        val cr = c.contentResolver
        if (on) Settings.Secure.putInt(cr, DALTONIZER, MONOCHROME)
        Settings.Secure.putInt(cr, DALTONIZER_ENABLED, if (on) 1 else 0)
    }

    fun animationsOff(c: Context): Boolean =
        ANIMATION_SCALES.all { Settings.Global.getFloat(c.contentResolver, it, 1f) == 0f }

    fun setAnimationsOff(c: Context, off: Boolean) {
        for (key in ANIMATION_SCALES) Settings.Global.putFloat(c.contentResolver, key, if (off) 0f else 1f)
    }
}

object ScreenTime {
    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Time spent in apps since midnight (home screen excluded), in minutes. */
    fun todayMinutes(c: Context): Int {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, midnight, System.currentTimeMillis())
            ?: return 0
        val total = stats
            .filter { it.packageName != c.packageName && it.lastTimeUsed >= midnight }
            .sumOf { it.totalTimeInForeground }
        return (total / 60_000L).toInt()
    }

    /** Milliseconds in the foreground since midnight, per package. */
    fun perApp(c: Context): Map<String, Long> {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, midnight, System.currentTimeMillis())
            ?: return emptyMap()
        val out = HashMap<String, Long>()
        for (s in stats) {
            if (s.lastTimeUsed >= midnight && s.totalTimeInForeground > 0) {
                out[s.packageName] = (out[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
        }
        return out
    }

    /** The apps used most today that can be opened from a launcher, longest first. */
    fun topApps(c: Context, count: Int): List<Pair<String, Int>> =
        perApp(c).entries
            .filter { it.key != c.packageName && it.value >= 60_000L && c.packageManager.getLaunchIntentForPackage(it.key) != null }
            .sortedByDescending { it.value }
            .take(count)
            .map { it.key to (it.value / 60_000L).toInt() }

    fun format(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
