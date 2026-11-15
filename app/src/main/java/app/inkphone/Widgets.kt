package app.inkphone

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/** Home-screen widgets that work on any launcher: screen time, and the most used apps. */
object Widgets {
    private var lastRefresh = 0L

    /** Redraws every placed widget. Cheap to call often; it limits itself to twice a minute. */
    fun refresh(context: Context, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRefresh < 30_000) return
        lastRefresh = now
        val manager = AppWidgetManager.getInstance(context) ?: return
        runCatching {
            val time = manager.getAppWidgetIds(ComponentName(context, ScreenTimeWidget::class.java))
            if (time.isNotEmpty()) manager.updateAppWidget(time, ScreenTimeWidget.build(context))
            val used = manager.getAppWidgetIds(ComponentName(context, MostUsedWidget::class.java))
            if (used.isNotEmpty()) manager.updateAppWidget(used, MostUsedWidget.build(context))
        }
    }

    fun openMenu(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
}

class ScreenTimeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, build(context))
    }

    companion object {
        fun build(c: Context): RemoteViews {
            val views = RemoteViews(c.packageName, R.layout.widget_screen_time)
            if (ScreenTime.hasAccess(c)) {
                val minutes = runCatching { ScreenTime.todayMinutes(c) }.getOrDefault(0)
                views.setTextViewText(R.id.time, ScreenTime.format(minutes))
                views.setTextViewText(R.id.caption, "on the phone today")
            } else {
                views.setTextViewText(R.id.time, "–")
                views.setTextViewText(R.id.caption, "tap to turn on")
            }
            views.setOnClickPendingIntent(R.id.root, Widgets.openMenu(c))
            return views
        }
    }
}

class MostUsedWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, build(context))
    }

    companion object {
        private val SLOTS = intArrayOf(R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4)
        private val ICONS = intArrayOf(R.id.icon1, R.id.icon2, R.id.icon3, R.id.icon4)
        private val TIMES = intArrayOf(R.id.time1, R.id.time2, R.id.time3, R.id.time4)

        fun build(c: Context): RemoteViews {
            val views = RemoteViews(c.packageName, R.layout.widget_most_used)
            views.setOnClickPendingIntent(R.id.root, Widgets.openMenu(c))
            val access = ScreenTime.hasAccess(c)
            val top = if (access) runCatching { ScreenTime.topApps(c, 4) }.getOrDefault(emptyList()) else emptyList()
            views.setTextViewText(
                R.id.title,
                when {
                    !access -> "tap to turn on most used apps"
                    top.isEmpty() -> "nothing used yet today"
                    else -> "most used today"
                }
            )
            val style = Prefs(c).iconStyle
            val size = (44 * c.resources.displayMetrics.density).toInt()
            for (i in SLOTS.indices) {
                val entry = top.getOrNull(i)
                if (entry == null) {
                    views.setViewVisibility(SLOTS[i], View.INVISIBLE)
                    continue
                }
                val (pkg, minutes) = entry
                views.setViewVisibility(SLOTS[i], View.VISIBLE)
                runCatching {
                    val icon = c.packageManager.getApplicationIcon(pkg)
                    views.setImageViewBitmap(ICONS[i], IconStyler.render(icon, style, size, pkg.hashCode()))
                }
                views.setTextViewText(TIMES[i], ScreenTime.format(minutes))
                c.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                    views.setOnClickPendingIntent(
                        SLOTS[i],
                        PendingIntent.getActivity(c, 10 + i, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
            return views
        }
    }
}

/** Daily time limits per app. */
object Limits {
    private val CHOICES = intArrayOf(0, 5, 10, 15, 30, 45, 60, 90, 120)
    const val BONUS_MINUTES = 5

    fun label(minutes: Int): String = when {
        minutes == 0 -> "No limit"
        minutes < 60 -> "$minutes minutes a day"
        minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes > 60) "s" else ""} a day"
        else -> "${minutes / 60}h ${minutes % 60}m a day"
    }

    /** Asks how long this app may be used each day, then saves it. */
    fun pick(activity: Activity, prefs: Prefs, pkg: String, appLabel: String, done: () -> Unit) {
        val current = CHOICES.indexOf(prefs.limit(pkg)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("Daily limit for $appLabel")
            .setSingleChoiceItems(CHOICES.map { label(it) }.toTypedArray(), current) { dialog, which ->
                prefs.setLimit(pkg, CHOICES[which])
                InkService.ensure(activity)
                dialog.dismiss()
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** True once today's use has passed the limit plus any extra minutes taken. */
    fun reached(c: Context, prefs: Prefs, pkg: String): Boolean {
        val limit = prefs.limit(pkg)
        if (limit == 0 || !ScreenTime.hasAccess(c)) return false
        val usedMs = runCatching { ScreenTime.perApp(c)[pkg] ?: 0L }.getOrDefault(0L)
        return usedMs >= (limit + prefs.bonus(pkg)) * 60_000L
    }
}
