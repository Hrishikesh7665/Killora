package `in`.hrishikesh7665.killora

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.LocaleManagerCompat
import androidx.core.net.toUri

/**
 * Drives the system "App info" screen to close one package at a time, in one of two modes.
 *
 * - **Automatic** (default): finds and taps the real Force stop button itself, confirms the
 *   dialog, and verifies the result before moving on. See [tryStep].
 * - **Manual** ([Prefs.getManualMode]): never taps or scrolls anything. It only opens each
 *   App info screen and waits for the user to tap Force stop and navigate back themselves,
 *   detected purely by watching which window is in front - see [tryStepManual].
 *
 * Scope and consent: this service only starts when the user is actively closing apps (via
 * [startClosing]), only reacts while that run is in progress, and only ever acts inside the
 * Settings app - never any other app, and never to change a setting the user didn't ask to
 * change. It reads no content outside Settings, stores nothing, and sends nothing off the
 * device. The user grants this service explicitly and separately in Android's own Accessibility
 * settings (see MainActivity's disclosure dialog before that screen opens), and can revoke it
 * there at any time; [onUnbind] stops any run in progress the moment that happens.
 *
 * Compose-based App info screens expose the control like this:
 *
 *     [View](clickable)            <- the node we must click
 *       [TextView] "Force stop"
 *
 * `findAccessibilityNodeInfosByText()` is unreliable for Compose nodes, so [findClickableByTexts]
 * walks the tree itself, matches text, and then climbs to the nearest clickable ancestor.
 */
class ForceCloseAccessibilityService : AccessibilityService() {

    companion object {
        /** Live instance while the service is connected, `null` otherwise. */
        var instance: ForceCloseAccessibilityService? = null
            private set

        // --- Callbacks consumed by OverlayService ------------------------------------------

        var onProgress: ((done: Int, total: Int, currentLabel: String, currentPackage: String) -> Unit)? = null
        var onSkipped: ((label: String) -> Unit)? = null
        var onFinished: (() -> Unit)? = null

        /** Fired if the service is switched off while a run is in progress. */
        var onAborted: (() -> Unit)? = null

        /**
         * Fired whenever the window set may have changed (a screen or dialog appeared, closed or
         * scrolled), and also just *before* we start a navigation ourselves. The overlay uses it
         * to re-assert its position on top for `durationMs` milliseconds. Firing proactively matters because a
         * cross-task switch sends our own task to the background before any accessibility event
         * reaches us, so a purely reactive approach can miss the very first frame.
         * See [OverlayService.startReassertBurst].
         */
        var onWindowEvent: ((durationMs: Long) -> Unit)? = null

        // --- Timing -----------------------------------------------------------------------

        /** Short burst: a same-task dialog opening/closing, or a settled screen scrolling. */
        private const val BURST_SHORT_MS = 900L

        /** Long burst: a cross-task navigation into (possibly cold-starting) Settings. */
        private const val BURST_NAV_MS = 1800L

        /** Moments after opening App info at which we try to find and tap Force stop. Tightened
         *  from the original ladder (which started at 500ms and ran to 7500ms) so a device that's
         *  ready early doesn't sit waiting on it; the long tail is still here for slow ones. */
        private val STEP_RETRY_DELAYS_MS = longArrayOf(350, 700, 1200, 1800, 2600, 3600, 4800, 6200)

        /** Give up on one attempt for a package after this long without a verified stop. */
        private const val ATTEMPT_TIMEOUT_MS = 8200L

        /** Pause before the first, and then the second, "is it really stopped?" check. */
        private const val VERIFY_FIRST_DELAY_MS = 500L
        private const val VERIFY_SECOND_DELAY_MS = 450L

        /** How long to wait for a confirmation dialog before checking the result anyway. */
        private const val CONFIRM_WAIT_MS = 2200L

        /** Until this long after tapping Force stop, a button labelled "Force stop" is not
         *  accepted as the confirm button unless a Cancel button proves the dialog is up. In the
         *  first moments the only such button on screen is the App info page's own one. */
        private const val FORCE_LABEL_CONFIRM_GRACE_MS = 700L

        /** Delay between backing out of App info and opening the next package. */
        private const val ADVANCE_DELAY_MS = 450L

        /** Delay between the final back action and returning to Killora. */
        private const val RETURN_DELAY_MS = 250L

        /** Scroll-up nudges after tapping Force stop, so the confirmation dialog stays reachable. */
        private const val SCROLL_UP_FIRST_DELAY_MS = 150L
        private const val SCROLL_UP_SECOND_DELAY_MS = 400L

        /** Manual mode only: how long to ignore window changes right after opening App info, so
         *  the screen's own entrance animation/load-in isn't mistaken for the user backing out. */
        private const val MANUAL_SETTLE_DELAY_MS = 900L

        /** A package is reported as "could not be closed" after this many failed attempts. */
        private const val MAX_ATTEMPTS_PER_PACKAGE = 3

        /** Search limits that keep tree walks cheap and bounded. */
        private const val MAX_SEARCH_DEPTH = 22
        private const val MAX_CLICKABLE_ANCESTOR_STEPS = 10
        private const val MAX_SCROLLABLE_SEARCH_DEPTH = 14
        private const val MAX_SCROLL_DOWN_TRIES = 4
        private const val MAX_SCROLL_UP_TRIES = 4

        /** Minimum gap between two scroll nudges. Without it, a burst of accessibility events
         *  during one scroll animation fires several scrolls in a row and overshoots. */
        private const val SCROLL_COOLDOWN_MS = 300L
        private const val MAX_DUMPS_PER_PACKAGE = 2
        private const val MAX_DUMP_NODES = 50
        private const val MAX_DUMP_DEPTH = 12

        private val queue = ArrayDeque<String>()
        private var total = 0
        private var done = 0
        private var running = false

        /** Set for the whole run by [startClosing]. See the class doc for what each mode does. */
        private var manualMode = false

        /** While true, [tryStep] and [tryStepManual] ignore every accessibility event: no taps,
         *  no scrolling, no advancing. The queue and the current screen are left exactly as they
         *  are until [resume]. */
        private var paused = false

        private val FORCE_STOP_TEXTS = listOf(
            "Force stop", "Force Stop", "FORCE STOP",
            "Force close", "Force Close", "FORCE CLOSE"
        )
        private val CANCEL_TEXTS = listOf("Cancel", "CANCEL")
        private val CONFIRM_TEXTS = listOf(
            "OK", "Ok", "Force stop", "Force Stop", "FORCE STOP",
            "Yes", "YES", "Confirm", "CONFIRM"
        )

        /** Names of the string resources Settings uses for its Force stop label. */
        private val FORCE_STOP_RESOURCE_NAMES = listOf(
            "force_stop_button_label",
            "device_apps_app_management_force_stop",
            "application_force_stop",
            "force_stop"
        )

        /**
         * Starts closing [packages].
         * @param manual when true, runs in manual mode (see the class doc): opens each App info
         *   screen and waits for the user's own back navigation instead of tapping anything.
         * Does nothing (beyond logging) if the service is not connected.
         */
        fun startClosing(packages: List<String>, manual: Boolean = false) {
            queue.clear()
            queue.addAll(packages)
            total = packages.size
            done = 0
            running = true
            paused = false
            manualMode = manual
            KLog.d("startClosing: queued ${packages.size} package(s), manual=$manual")
            val service = instance
            if (service == null) {
                KLog.w("startClosing called but the accessibility service is not connected")
                return
            }
            service.attemptCounts.clear()
            service.goToNextPackage()
        }

        /** Stops the current run and brings the user back to Killora. */
        fun cancel() {
            KLog.d("cancel()")
            running = false
            paused = false
            queue.clear()
            instance?.attemptCounts?.clear()
            instance?.returnToKillora()
        }

        /** Suspends tapping/advancing until [resume]. Has no automatic timeout: the run waits for
         *  as long as the user wants before resuming, exactly like manual mode already does. */
        fun pause() {
            if (!running || paused) return
            paused = true
            KLog.d("pause()")
        }

        /** Resumes a paused run. In automatic mode, retries the current screen immediately
         *  instead of waiting for the next accessibility event, which may not come if nothing
         *  on screen changes while paused. */
        fun resume() {
            if (!paused) return
            paused = false
            KLog.d("resume()")
            if (!manualMode) instance?.let { svc -> if (!svc.advancing) svc.tryStep() }
        }

        fun isPaused(): Boolean = paused

        /**
         * True once the accessibility service is connected. This is the only requirement for the
         * closing overlay, because the overlay is a TYPE_ACCESSIBILITY_OVERLAY window and so
         * needs no separate "display over other apps" permission.
         */
        fun isReady(): Boolean = instance != null

        /**
         * Adds [view] as an accessibility-overlay window.
         *
         * TYPE_ACCESSIBILITY_OVERLAY windows can only be added through the WindowManager of a
         * connected accessibility service, so OverlayService routes all window calls through
         * here. That layer sits above ordinary app windows and above Settings' own dialogs,
         * which is what stops the Force stop dialogs from showing through the overlay. The
         * re-assert loop in OverlayService stays as a cheap safety net.
         *
         * @return `true` if the window was added.
         */
        fun addOverlayWindow(view: View, params: WindowManager.LayoutParams): Boolean {
            val service = instance ?: return false
            return try {
                service.getSystemService(WindowManager::class.java).addView(view, params)
                true
            } catch (e: RuntimeException) {
                KLog.w("addOverlayWindow failed: ${e.message}")
                false
            }
        }

        fun updateOverlayWindow(view: View, params: WindowManager.LayoutParams) {
            val service = instance ?: return
            try {
                service.getSystemService(WindowManager::class.java).updateViewLayout(view, params)
            } catch (_: RuntimeException) {
                // The window may have just been torn down (service stopping, user tapped Hide,
                // accessibility service killed). Nothing to correct, so ignore.
            }
        }

        fun removeOverlayWindow(view: View) {
            val service = instance ?: return
            try {
                service.getSystemService(WindowManager::class.java).removeView(view)
            } catch (_: RuntimeException) {
                // Already removed.
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Bumped for every navigation; delayed callbacks from an older attempt compare and bail out. */
    private var attemptToken = 0
    private var advancing = false
    private var waitingForConfirm = false
    private var waitingForConfirmSince = 0L

    /** True once the confirm dialog's button was successfully tapped for the current attempt, so
     *  later accessibility events don't keep tapping (and re-scheduling verification). */
    private var confirmClicked = false

    /** True once a stop-verification has been scheduled for the current attempt. Together with
     *  the attempt token this guarantees each attempt is verified at most once. */
    private var verifying = false
    private var lastClickedNodeKey: String? = null
    private var currentPackage: String? = null
    private var scrollTries = 0
    private var scrollUpTries = 0
    private var lastScrollAtMs = 0L
    private var treeDumpCount = 0

    /** Manual mode only. See [tryStepManual]. */
    private var manualInitialClassName: String? = null
    private var manualSettled = false

    private val resolvedLabelCache = HashMap<String, String?>()

    /** Per-package retry counter. See [retryOrSkip]. */
    private val attemptCounts = HashMap<String, Int>()

    override fun onServiceConnected() {
        instance = this
        KLog.d("onServiceConnected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        if (running) {
            // The user (or the system) turned the service off mid-run. Stop cleanly and tell the
            // caller, instead of leaving it waiting for a finish that will never come.
            running = false
            paused = false
            queue.clear()
            handler.removeCallbacksAndMessages(null)
            onAborted?.invoke()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Queue handling
    // ---------------------------------------------------------------------------------------

    private fun goToNextPackage() {
        if (!running || queue.isEmpty()) {
            finishRun()
            return
        }
        advancing = false
        waitingForConfirm = false
        waitingForConfirmSince = 0L
        confirmClicked = false
        verifying = false
        lastClickedNodeKey = null
        scrollTries = 0
        scrollUpTries = 0
        lastScrollAtMs = 0L
        treeDumpCount = 0
        manualInitialClassName = null
        manualSettled = false
        attemptToken++
        val myToken = attemptToken
        val pkg = queue.first()
        currentPackage = pkg
        attemptCounts.putIfAbsent(pkg, 1)
        KLog.d("Navigating to App Info for $pkg (${done + 1}/$total, attempt ${attemptCounts[pkg]}/$MAX_ATTEMPTS_PER_PACKAGE)")
        onProgress?.invoke(done, total, AppCloser.labelOf(this, pkg), pkg)

        if (AppCloser.isPackageStopped(this, pkg)) {
            KLog.d("Package $pkg already stopped, skipping")
            advanceQueue()
            return
        }

        try {
            // Fire the burst *before* navigating. This is a cross-task switch, so our task starts
            // moving to the background the moment startActivity() returns.
            onWindowEvent?.invoke(BURST_NAV_MS)
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$pkg".toUri()
                // NO_ANIMATION: the transition into App info can briefly draw above the overlay on
                // some Android versions and OEM skins. Skipping it helps; the burst above covers
                // whatever is left.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            })
        } catch (e: RuntimeException) {
            KLog.w("Could not open App Info for $pkg: ${e.message}")
            onSkipped?.invoke(AppCloser.labelOf(this, pkg))
            advanceQueue()
            return
        }

        if (manualMode) {
            // No taps to schedule and no timeout: the user sets the pace. Only mark the screen
            // "settled" after its own entrance animation, so the very first window-state event
            // (the App info screen itself appearing) isn't mistaken for the user leaving it.
            handler.postDelayed({ if (myToken == attemptToken) manualSettled = true }, MANUAL_SETTLE_DELAY_MS)
            return
        }

        for (delay in STEP_RETRY_DELAYS_MS) {
            handler.postDelayed({ if (myToken == attemptToken) tryStep() }, delay)
        }
        handler.postDelayed({
            if (myToken == attemptToken && !advancing && !paused) {
                if (AppCloser.isPackageStopped(this, pkg)) {
                    KLog.d("Timeout, but $pkg is stopped: success")
                    advanceQueue()
                } else {
                    KLog.w("Timed out on $pkg")
                    retryOrSkip(pkg)
                }
            }
        }, ATTEMPT_TIMEOUT_MS)
    }

    /** Leaves Settings and lands back inside Killora once the queue is empty or canceled. */
    private fun finishRun() {
        running = false
        paused = false
        KLog.d("Queue finished (done=$done/$total)")
        // Returning to Killora is itself a task switch, and the overlay stays up a little longer
        // specifically to cover it, so make sure it is fighting to stay on top.
        onWindowEvent?.invoke(BURST_NAV_MS)
        // In manual mode the user already backed out of the last App info screen themselves;
        // an extra BACK here could pop one more screen than intended (for example out of
        // Settings' own app list and onto the home screen).
        if (!manualMode) performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ returnToKillora() }, RETURN_DELAY_MS)
        onFinished?.invoke()
    }

    /**
     * Checks that [pkg] really ended up stopped, then moves on (or retries).
     *
     * Every delayed step here is bound to the attempt it was scheduled for via [attemptToken].
     * Without that, a callback left over from package N would fire after package N+1 had
     * started, see that N is stopped, and call [advanceQueue] - which dequeues whatever is at
     * the head of the queue, i.e. N+1, without it ever being force-stopped. That is what made
     * some apps get silently skipped while the run still reported success.
     */
    private fun verifyAndAdvance(pkg: String) {
        if (advancing || verifying) return
        verifying = true
        val token = attemptToken
        handler.postDelayed({
            if (token != attemptToken || advancing) return@postDelayed
            if (AppCloser.isPackageStopped(this, pkg)) {
                KLog.d("Force-stop confirmed for $pkg")
                advanceQueue()
            } else {
                handler.postDelayed({
                    if (token != attemptToken || advancing) return@postDelayed
                    if (AppCloser.isPackageStopped(this, pkg)) {
                        KLog.d("Force-stop confirmed for $pkg (2nd check)")
                        advanceQueue()
                    } else {
                        KLog.w("$pkg still not confirmed stopped")
                        retryOrSkip(pkg)
                    }
                }, VERIFY_SECOND_DELAY_MS)
            }
        }, VERIFY_FIRST_DELAY_MS)
    }

    /**
     * Called whenever a force-stop attempt for [pkg] could not be verified. Repeats the whole
     * navigate-and-tap sequence until [MAX_ATTEMPTS_PER_PACKAGE] is reached, then reports the app
     * through [onSkipped] so the user knows which apps still need closing by hand.
     */
    private fun retryOrSkip(pkg: String) {
        val attemptsSoFar = attemptCounts[pkg] ?: 1
        if (attemptsSoFar < MAX_ATTEMPTS_PER_PACKAGE) {
            attemptCounts[pkg] = attemptsSoFar + 1
            KLog.w("Retrying force-stop for $pkg (attempt ${attemptsSoFar + 1}/$MAX_ATTEMPTS_PER_PACKAGE)")
            goToNextPackage()
        } else {
            KLog.w("$pkg still not stopped after $MAX_ATTEMPTS_PER_PACKAGE attempts, giving up")
            attemptCounts.remove(pkg)
            onSkipped?.invoke(AppCloser.labelOf(this, pkg))
            advanceQueue()
        }
    }

    private fun advanceQueue() {
        if (advancing) return
        advancing = true
        queue.removeFirstOrNull()?.let { attemptCounts.remove(it) }
        done++
        currentPackage = null
        // Backing out of App info (its own task) is a task switch too, so it gets the same
        // proactive nudge as the forward navigation.
        onWindowEvent?.invoke(BURST_NAV_MS)
        // Only back out. The next package opens its own task on top, and finishRun() returns to
        // Killora at the end, so there is no need to detour through the home screen. In manual
        // mode the user has already navigated back themselves - that is literally the signal
        // that got us here (see tryStepManual) - so performing it again would be a second,
        // unrequested back action.
        if (!manualMode) performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ goToNextPackage() }, ADVANCE_DELAY_MS)
    }

    /** Brings Killora back to the foreground instead of leaving the user in Settings. */
    private fun returnToKillora() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(intent)
        } catch (e: RuntimeException) {
            KLog.w("Could not return to Killora: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Accessibility events and UI automation
    // ---------------------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running || event == null) return
        if (manualMode) {
            // Manual mode only needs to know when the front-most screen changes - it never taps
            // or scrolls, so content-changed/scrolled events carry nothing it acts on.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                onWindowEvent?.invoke(BURST_SHORT_MS)
                tryStepManual(event)
            }
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // TYPE_WINDOWS_CHANGED fires the moment a window (such as the Force stop
                // confirmation) is added or removed, which is exactly when something might be
                // covering the overlay.
                onWindowEvent?.invoke(BURST_SHORT_MS)
                tryStep()
            }
            // Every other event type is irrelevant to this automation.
            else -> Unit
        }
    }

    override fun onInterrupt() {
        // Nothing to release: the automation holds no feedback resources.
    }

    /**
     * Manual mode's only automation, and it neither taps nor scrolls anything. [event]'s class
     * name identifies the front-most screen; the only way that can change here is the user
     * navigating away (back button, gesture, or Recents) themselves, since nothing else is
     * touching the device. The first event after opening App info just records that screen's
     * identity - after a short settle window for its own entrance animation, any different
     * class name means the user has moved on, so we advance to the next package.
     */
    private fun tryStepManual(event: AccessibilityEvent) {
        if (!running || advancing || paused || currentPackage == null) return
        val className = event.className?.toString()
        val recorded = manualInitialClassName
        if (recorded == null) {
            manualInitialClassName = className
            return
        }
        if (!manualSettled) return
        if (className != recorded) {
            KLog.d("Manual mode: window changed ($recorded -> $className), advancing")
            advanceQueue()
        }
    }

    private fun tryStep() {
        if (!running || advancing || paused) return
        val root = findSettingsRoot() ?: run {
            KLog.d("no Settings window yet")
            return
        }
        val settingsPkg = root.packageName?.toString() ?: return
        if (settingsPkg == packageName) return

        val dynamicLabel = resolveLabel(settingsPkg)
        val forceTexts = (listOfNotNull(dynamicLabel) + FORCE_STOP_TEXTS).distinct()
        val confirmTexts = (
            listOfNotNull(runCatching { getString(android.R.string.ok) }.getOrNull(), dynamicLabel) +
                CONFIRM_TEXTS
            ).distinct()

        val pkg = currentPackage ?: return

        if (waitingForConfirm) {
            handleConfirmDialog(root, pkg, confirmTexts)
            return
        }

        // Primary search: walk the tree ourselves (works with Compose).
        val forceBtn = findClickableByTexts(root, forceTexts)
        if (forceBtn != null) {
            tapForceStop(forceBtn)
            return
        }

        // No Force stop button, but a confirmation dialog is open (left behind by an earlier tap
        // that missed it): answer it instead of searching for a button it is covering.
        if (isConfirmDialogShown(root)) {
            KLog.d("Confirmation dialog already open, answering it")
            waitingForConfirm = true
            waitingForConfirmSince = System.currentTimeMillis()
            handleConfirmDialog(root, pkg, confirmTexts)
            return
        }

        KLog.d("Force stop not found (pkg=$settingsPkg label=$dynamicLabel)")
        if (treeDumpCount < MAX_DUMPS_PER_PACKAGE) {
            treeDumpCount++
            dumpTree(root)
        }
        // Not visible yet. Nudge the page, but never twice within one scroll animation.
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollAtMs < SCROLL_COOLDOWN_MS) return
        if (scrollTries < MAX_SCROLL_DOWN_TRIES && scrollDown(root)) {
            scrollTries++
            lastScrollAtMs = now
            KLog.d("Scrolled down (try $scrollTries)")
            return
        }
        // Scrolling down is exhausted or impossible (already at the bottom). Force stop sits at
        // the TOP of App info, so a page that was scrolled past it - which Android keeps when
        // App info is reopened for a retry - must be brought back up, or it never reappears.
        if (scrollUpTries < MAX_SCROLL_UP_TRIES && scrollUp(root)) {
            scrollUpTries++
            lastScrollAtMs = now
            KLog.d("Scrolled up (try $scrollUpTries)")
        }
    }

    private fun tapForceStop(forceBtn: AccessibilityNodeInfo) {
        if (!forceBtn.isEnabled) {
            KLog.d("Force stop is disabled: already stopped")
            advanceQueue()
            return
        }
        KLog.d("Clicking Force stop: class='${forceBtn.className}'")
        lastClickedNodeKey = nodeKey(forceBtn)
        if (!clickNode(forceBtn)) {
            // The tap was not accepted. Don't wait for a dialog that will never appear; the next
            // accessibility event (or retry step) simply tries again.
            lastClickedNodeKey = null
            // Most likely the button was mid-scroll or just off-screen: bring the page back up.
            findSettingsRoot()?.let { if (scrollUp(it)) lastScrollAtMs = SystemClock.uptimeMillis() }
            return
        }
        waitingForConfirm = true
        waitingForConfirmSince = System.currentTimeMillis()
        // The confirmation dialog's entrance animation is the highest-risk moment for the overlay
        // (our NO_ANIMATION flag cannot reach it), so nudge the overlay a few times across it.
        onWindowEvent?.invoke(BURST_SHORT_MS)
        // Scroll back toward the top so the confirmation dialog stays reachable.
        val scrollToken = attemptToken
        for (delay in longArrayOf(SCROLL_UP_FIRST_DELAY_MS, SCROLL_UP_SECOND_DELAY_MS)) {
            handler.postDelayed({
                if (scrollToken != attemptToken) return@postDelayed
                findSettingsRoot()?.let { scrollUp(it) }
                onWindowEvent?.invoke(BURST_SHORT_MS)
            }, delay)
        }
    }

    private fun handleConfirmDialog(root: AccessibilityNodeInfo, pkg: String, confirmTexts: List<String>) {
        // Already confirmed for this attempt: verification is scheduled, nothing more to tap.
        if (confirmClicked) return

        // If we scrolled the page down to reach Force stop, scroll back up so the dialog, and it's
        // OK button are visible and present in the accessibility tree.
        scrollUp(root)

        // Exact match here: a substring search for "OK" also matches words like "Book's Story".
        //
        // Plain OK / Yes / Confirm buttons come first. A button labelled "Force stop" is also
        // accepted (some phones label the dialog's button that way), but only once the dialog is
        // provably up: before that, the only such button is the App info page's own Force stop
        // button, and tapping it a second time "confirms" nothing while the real dialog then
        // opens unanswered.
        val dialogUp = isConfirmDialogShown(root) ||
            System.currentTimeMillis() - waitingForConfirmSince > FORCE_LABEL_CONFIRM_GRACE_MS
        val forceLabels = (listOfNotNull(resolveLabel(root.packageName?.toString().orEmpty())) + FORCE_STOP_TEXTS)
            .map { it.lowercase() }.toSet()
        val plainConfirmTexts = confirmTexts.filter { it.lowercase() !in forceLabels }
        val btn = findClickableByTexts(root, plainConfirmTexts, excludeKey = lastClickedNodeKey, exact = true)
            ?: if (dialogUp) {
                findClickableByTexts(root, confirmTexts, excludeKey = lastClickedNodeKey, exact = true)
            } else {
                null
            }
        if (btn != null) {
            KLog.d("Clicking confirm button")
            if (clickNode(btn)) {
                confirmClicked = true
                // The dialog dismissing (and App info settling back) is its own brief animation
                // window that the overlay needs to fight for.
                onWindowEvent?.invoke(BURST_SHORT_MS)
                verifyAndAdvance(pkg)
            }
            // If the tap was refused, leave things as they are and retry on the next event.
        } else if (System.currentTimeMillis() - waitingForConfirmSince > CONFIRM_WAIT_MS) {
            KLog.d("No confirm dialog, verifying")
            verifyAndAdvance(pkg)
        }
    }

    /** True when the Force stop confirmation dialog is on screen, recognised by its Cancel
     *  button. The App info page has none, so this never mistakes the page for the dialog. */
    private fun isConfirmDialogShown(root: AccessibilityNodeInfo): Boolean {
        val cancelTexts = listOfNotNull(runCatching { getString(android.R.string.cancel) }.getOrNull()) + CANCEL_TEXTS
        return findClickableByTexts(root, cancelTexts.distinct(), exact = true) != null
    }

    /**
     * Walks the whole accessibility tree for a node whose text or content description contains
     * one of [texts] (case-insensitive), then climbs to its nearest clickable ancestor.
     * Far more reliable than `findAccessibilityNodeInfosByText()` on Compose UIs.
     */
    private fun findClickableByTexts(
        root: AccessibilityNodeInfo,
        texts: List<String>,
        excludeKey: String? = null,
        exact: Boolean = false
    ): AccessibilityNodeInfo? {
        val lowerTexts = texts.map { it.lowercase() }
        val matches = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_SEARCH_DEPTH) return
            val matched = if (exact) {
                // The whole text (or the whole description) must equal one of the labels.
                listOf(node.text, node.contentDescription).any { field ->
                    val value = field?.toString()?.trim()?.lowercase().orEmpty()
                    value.isNotEmpty() && value in lowerTexts
                }
            } else {
                val combined = buildString {
                    node.text?.let { append(it).append(' ') }
                    node.contentDescription?.let { append(it) }
                }.lowercase().trim()
                combined.isNotEmpty() && lowerTexts.any { combined.contains(it) }
            }
            if (matched) matches.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)

        for (match in matches) {
            var candidate: AccessibilityNodeInfo? = match
            var steps = 0
            while (candidate != null && steps < MAX_CLICKABLE_ANCESTOR_STEPS) {
                if (candidate.isClickable && isSafe(candidate)) {
                    if (excludeKey != null && nodeKey(candidate) == excludeKey) break
                    return candidate
                }
                candidate = candidate.parent
                steps++
            }
        }
        return null
    }

    /** Never click anything that belongs to Killora itself. */
    private fun isSafe(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString() ?: return false
        if (pkg == packageName) return false
        val text = (node.text?.toString().orEmpty()) + (node.contentDescription?.toString().orEmpty())
        return !(text.contains("Quick List", ignoreCase = true) || text.contains("Killora", ignoreCase = true))
    }

    /** Finds the Settings window, whether it is the currently active one. */
    private fun findSettingsRoot(): AccessibilityNodeInfo? {
        try {
            for (window in windows ?: emptyList()) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (pkg.contains("settings", ignoreCase = true) && pkg != packageName) return root
            }
        } catch (e: RuntimeException) {
            KLog.w("windows lookup failed: ${e.message}")
        }
        val active = rootInActiveWindow
        val pkg = active?.packageName?.toString()
        if (pkg != null && pkg.contains("settings", ignoreCase = true) && pkg != packageName) return active
        return null
    }

    /**
     * Looks up the localized "Force stop" label from the Settings app's own resources, so the
     * automation also works on non-English devices. Results (including "not found") are cached.
     */
    // Reading another app's strings by name is the only way to get its localized label; there
    // is no compile-time R class for a different package.
    @Suppress("DiscouragedApi")
    private fun resolveLabel(settingsPkg: String): String? {
        resolvedLabelCache[settingsPkg]?.let { return it }
        if (resolvedLabelCache.containsKey(settingsPkg)) return null
        val resolved = try {
            val res = systemLanguageResources(settingsPkg)
            FORCE_STOP_RESOURCE_NAMES.firstNotNullOfOrNull { name ->
                val id = res.getIdentifier(name, "string", settingsPkg)
                if (id != 0) res.getText(id).toString().takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            // Package not found or resources unreadable: fall back to the English texts.
            null
        }
        KLog.d("resolveLabel($settingsPkg) = $resolved")
        resolvedLabelCache[settingsPkg] = resolved
        return resolved
    }

    /**
     * The Settings app's resources in the *device* language. The Settings screen is always shown
     * in the system language, so its "Force stop" label has to be looked up in that language too.
     * A plain getResourcesForApplication() can pick up Killora's own language choice instead (on
     * Android 13+ a per-app language applies to the whole process), which would look for, say, an
     * English label on a screen that is in another language and the automation would never find
     * its button. Whenever the app follows the system language, this returns the same strings
     * that call did.
     */
    private fun systemLanguageResources(settingsPkg: String): Resources {
        val settingsContext = createPackageContext(settingsPkg, 0)
        val deviceLocales = LocaleManagerCompat.getSystemLocales(this).toLanguageTags()
        val config = Configuration(settingsContext.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(deviceLocales))
        return settingsContext.createConfigurationContext(config).resources
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_SCROLLABLE_SEARCH_DEPTH) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findScrollable(it, depth + 1) }?.let { return it }
        }
        return null
    }

    private fun scrollDown(root: AccessibilityNodeInfo): Boolean =
        findScrollable(root, 0)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false

    /** SCROLL_BACKWARD brings content back toward the top. */
    private fun scrollUp(root: AccessibilityNodeInfo): Boolean =
        findScrollable(root, 0)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false

    /**
     * Debug builds only: logs what the Settings screen looks like when Force stop cannot be
     * found, which is how new OEM layouts get diagnosed. Never runs in release builds, because
     * it writes on-screen text to logcat.
     */
    private fun dumpTree(root: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        val out = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_DUMP_NODES || depth > MAX_DUMP_DEPTH) return
            val text = node.text?.toString()?.take(40)
            val desc = node.contentDescription?.toString()?.take(40)
            val cls = node.className?.toString()?.substringAfterLast('.')
            if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable) {
                out.append("  ".repeat(depth))
                    .append("[$cls]")
                    .append(if (node.isClickable) "(click)" else "")
                    .append(if (!node.isEnabled) "(disabled)" else "")
                    .append(" text='$text' desc='$desc'\n")
                count++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        KLog.d("TREE DUMP #$treeDumpCount ($count nodes):\n$out")
    }

    /** Stable-enough identity for a node, used to avoid clicking the same button twice. */
    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "${node.text}|${node.viewIdResourceName}|$bounds"
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        KLog.d("ACTION_CLICK returned $ok")
        return ok
    }
}
