package `in`.hrishikesh7665.killora

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Posts the outcome of a close run as a notification, for flows that have no window to show a
 * message in (the home-screen shortcut, the widget, the deep force-stop service).
 *
 * Behavior is designed so it never nags:
 *  - Uses a quiet, low-importance channel: no sound, no vibration, no heads-up.
 *  - Reuses one notification id, so repeated runs update it instead of stacking.
 *  - Always swipe-away dismissable, and tapping it opens Killora and clears it.
 *  - A clean result clears itself after [SUCCESS_TIMEOUT_MS]. A problem stays until the user
 *    dismisses it, because they may need to act on it.
 */
object ResultNotifier {
    private const val CHANNEL_ID = "close_results"
    private const val NOTIFICATION_ID = 43
    private const val SUCCESS_TIMEOUT_MS = 8_000L

    fun post(context: Context, report: CloseReport) {
        post(context, report.summary(context), persistent = report.hasProblems)
    }

    /** @param persistent keep the notification until the user dismisses it. */
    fun post(context: Context, message: String, persistent: Boolean) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        createChannel(appContext, manager)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_stat_killora)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        openAppIntent(appContext)?.let { builder.setContentIntent(it) }
        if (!persistent) builder.setTimeoutAfter(SUCCESS_TIMEOUT_MS)

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.channel_results_name), NotificationManager.IMPORTANCE_LOW
        )
        channel.description = context.getString(R.string.channel_results_description)
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }
}
