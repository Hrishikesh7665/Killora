package `in`.hrishikesh7665.killora

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Flexible/resizable home-screen widget: live RAM reading + one-tap Close All for the
 *  saved Quick List. Resizes freely per widget_info.xml (resizeMode="horizontal|vertical"). */
class RamWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / 1e9
        val availGb = info.availMem / 1e9
        val percent = if (totalGb > 0) (((totalGb - availGb) / totalGb) * 100).toInt() else 0

        val views = RemoteViews(context.packageName, R.layout.widget_ram)
        views.setTextViewText(R.id.widgetRamPercent, context.getString(R.string.percent_format, percent))

        val closeIntent = Intent(context, QuickCloseActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingClose = PendingIntent.getActivity(
            context, widgetId, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetBtnCloseAll, pendingClose)

        // Tapping the percent refreshes the reading (widgets can't poll continuously).
        val refreshIntent = Intent(context, RamWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
        val pendingRefresh = PendingIntent.getBroadcast(
            context, widgetId, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRamPercent, pendingRefresh)

        appWidgetManager.updateAppWidget(widgetId, views)
    }
}
