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

    fun isGrayscale(c: Context): Boolean {
        val cr = c.contentResolver
        return Settings.Secure.getInt(cr, DALTONIZER_ENABLED, 0) == 1 &&
            Settings.Secure.getInt(cr, DALTONIZER, -1) == MONOCHROME
    }

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

    fun format(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
