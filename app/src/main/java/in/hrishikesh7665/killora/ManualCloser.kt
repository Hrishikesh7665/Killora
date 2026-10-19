package `in`.hrishikesh7665.killora

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Manual mode's engine. It needs **no Accessibility Service**.
 *
 * It opens each app's App info screen inside Killora's own task, so pressing Back after
 * tapping Force stop lands straight back in Killora. Killora notices that return through the
 * activity lifecycle ([onPause] / [onResume]) - no window watching, tapping or scrolling - and
 * opens the next app. Progress is shown with a Stop-able message and a toast, because the
 * accessibility overlay is not available without the service.
 *
 * State lives in memory, so it survives MainActivity being recreated while you are in Settings.
 * Automatic mode does not use this class at all ([ForceCloseAccessibilityService] is untouched).
 */
object ManualCloser {

    /** Extra used by the widget/shortcut entry point to hand its packages to MainActivity. */
    const val EXTRA_MANUAL_PACKAGES = "manual_packages"

    /** Short pause before each App info opens, so the Stop button on the message is reachable. */
    private const val OPEN_DELAY_MS = 1200L

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val notStopped = mutableListOf<String>()
    private var total = 0
    private var done = 0

    /** The package whose App info is open right now, or null. */
    private var current: String? = null

    /** True once Killora has actually left the foreground for [current]'s App info. */
    private var wentAway = false

    private var token = 0
    private var scheduled = false

    fun start(activity: Activity, packages: List<String>) {
        token++
        scheduled = false
        current = null
        queue.clear()
        notStopped.clear()
        queue.addAll(packages.distinct())
        total = queue.size
        done = 0
        scheduleNext(activity)
    }

    /** Call from the Activity's onPause. */
    fun onPause() {
        if (current != null) wentAway = true
    }

    /** Call from the Activity's onResume: this is how "the user came back" is detected. */
    fun onResume(activity: Activity) {
        val pkg = current
        if (pkg != null) {
            if (!wentAway) return
            current = null
            queue.remove(pkg)
            done++
            if (!AppCloser.isPackageStopped(activity, pkg)) notStopped += AppCloser.labelOf(activity, pkg)
            scheduleNext(activity)
        } else if (queue.isNotEmpty() && !scheduled) {
            // The screen was recreated before the next app was opened; carry on.
            scheduleNext(activity)
        }
    }

    private fun scheduleNext(activity: Activity) {
        while (queue.isNotEmpty() && AppCloser.isPackageStopped(activity, queue.first())) {
            queue.removeFirst()
            done++
        }
        if (queue.isEmpty()) {
            finish(activity)
            return
        }
        val pkg = queue.first()
        scheduled = true
        val myToken = ++token
        Notice.show(
            activity,
            activity.getString(R.string.manual_next, AppCloser.labelOf(activity, pkg), done + 1, total),
            actionLabel = activity.getString(R.string.manual_stop)
        ) { cancel(activity) }
        handler.postDelayed({
            if (myToken != token) return@postDelayed
            scheduled = false
            open(activity, pkg)
        }, OPEN_DELAY_MS)
    }

    private fun open(activity: Activity, pkg: String) {
        if (activity.isFinishing || activity.isDestroyed) return // onResume resumes the queue
        try {
            // No NEW_TASK: stays in Killora's task, so Back returns here.
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = "package:$pkg".toUri() }
            )
            current = pkg
            wentAway = false
            Toast.makeText(
                activity.applicationContext,
                activity.getString(R.string.manual_toast, AppCloser.labelOf(activity, pkg)),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: RuntimeException) {
            KLog.w("Manual mode: could not open App info for $pkg: ${e.message}")
            queue.remove(pkg)
            done++
            notStopped += AppCloser.labelOf(activity, pkg)
            scheduleNext(activity)
        }
    }

    private fun cancel(activity: Activity) {
        token++
        scheduled = false
        current = null
        queue.clear()
        CloseTracker.finish()
        Notice.show(activity, activity.getString(R.string.manual_stopped))
    }

    private fun finish(activity: Activity) {
        current = null
        scheduled = false
        CloseTracker.finish()
        val message = if (notStopped.isEmpty()) {
            activity.getString(R.string.manual_done_all)
        } else {
            activity.getString(R.string.manual_done_partial, notStopped.joinToString(", "))
        }
        Notice.show(activity, message, persistent = notStopped.isNotEmpty())
    }
}
