package `in`.hrishikesh7665.killora

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Closes apps using Android's own public API instead of simulating taps on the Settings UI.
 *
 * Why this exists: on a non-rooted phone, [ForceCloseAccessibilityService] has to guess at a
 * "Force stop" button inside system Settings, and that guess breaks differently on every OEM
 * skin (Samsung One UI, MIUI, stock AOSP all lay the App Info screen out differently, and some
 * ship it as a native/Compose view with no reliably matchable text at all). That's inherently
 * fragile - it is working around the OS, not with it.
 *
 * [ActivityManager.killBackgroundProcesses] is the real, public, documented API Android gives
 * every app for this. (It's what the permission `KILL_BACKGROUND_PROCESSES` exists for, and
 * it's what most "RAM booster" / task-killer apps on the Play Store actually call under the
 * hood - which is also why most of them don't need an Accessibility Service at all.) No
 * special permission screen, no OEM-specific UI to reverse-engineer, same call on every
 * device. It's used here as the primary, default way Killora closes an app.
 *
 * The trade-off, to be upfront about it: Android only lets this kill a process that's already
 * sitting in the background (cached/idle) - by design, no ordinary app is allowed to kill
 * another app's active foreground process or a process a foreground service is keeping alive
 * (think: a music player mid-playback, a nav app, a download in progress). That's not a bug
 * here, it's a deliberate OS guard rail, and it's the same guard rail every other task-killer
 * app runs into. For the apps people actually mean when they say "close this, I'm not using
 * it right now", it's exactly what's needed. For the few that are still holding something
 * open, the optional Accessibility-based "deep force stop" (off by default, see Settings)
 * remains available as a second pass.
 */
object AppCloser {

    /** Asks the OS to kill each package's background process. Fire-and-forget by design:
     *  Android does not let a normal app read back another app's process state (that
     *  visibility was locked down for every app's privacy since Android 5.1), so there is no
     *  reliable way to confirm success from here - the same is true for every other app on
     *  the Play Store that offers this feature. */
    fun killBackground(context: Context, packages: List<String>) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (pkg in packages) {
            try {
                am.killBackgroundProcesses(pkg)
            } catch (_: Exception) {
                // A handful of protected/system packages refuse this even with the
                // permission held; skip them rather than let one failure stop the rest.
            }
        }
    }

    /** Human-readable app name, or the package name itself if the app is not installed. */
    fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /**
     * Returns true if the package is currently in the stopped state
     * (ApplicationInfo.FLAG_STOPPED is set). Android sets this bit after a successful
     * Force stop. Used by [ForceCloseAccessibilityService] to verify the UI automation
     * actually worked before advancing the queue.
     */
    fun isPackageStopped(context: Context, packageName: String): Boolean {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            (ai.flags and ApplicationInfo.FLAG_STOPPED) != 0
        } catch (_: Exception) {
            false
        }
    }

    /** True if the app is installed and not in the stopped state. Never throws. */
    fun isRunning(context: Context, packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and ApplicationInfo.FLAG_STOPPED) == 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Closes [packages] with the best method available and reports what happened.
     *
     * Order of preference: root force stop (verified, with retries), then Android's own
     * background kill. Apps that are not running are skipped rather than sent through any close
     * path. **Blocking**: the root path runs shell commands and sleeps, so call it off the main
     * thread (see [Background.io]).
     */
    fun closeNow(context: Context, packages: List<String>): CloseReport {
        val (running, stopped) = packages.distinct().partition { isRunning(context, it) }
        val stoppedLabels = stopped.map { labelOf(context, it) }
        if (running.isEmpty()) return CloseReport(alreadyStopped = stoppedLabels)

        // Only take the root path when su has actually been granted. isDeviceRooted() alone just
        // means a su binary exists; isRootGranted() confirms the prompt was accepted.
        if (RootUtils.isDeviceRooted() && RootUtils.isRootGranted()) {
            val stillRunning = RootUtils.forceStopAsRootVerified(context, running)
            // With root granted the Accessibility Service is never needed or asked for. The only
            // exception is a user who already has it switched on: if root verified nothing at
            // all, that existing service may still be tried as a last resort.
            val accessibilityAlreadyOn = AccessibilityUtils.isForceCloseServiceEnabled(context)
            if (stillRunning.size < running.size || !accessibilityAlreadyOn) {
                return CloseReport(
                    closed = (running - stillRunning.toSet()).map { labelOf(context, it) },
                    alreadyStopped = stoppedLabels,
                    failed = stillRunning.map { labelOf(context, it) }
                )
            }
            // Root verified nothing and Accessibility is already on: fall back to the normal path.
        }

        killBackground(context, running)
        return CloseReport(
            closed = running.map { labelOf(context, it) },
            alreadyStopped = stoppedLabels,
            deepCandidates = running
        )
    }
}
