package `in`.hrishikesh7665.killora

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible

/**
 * Runs the deep force-stop queue and shows a progress overlay while it works.
 *
 * In automatic mode ([Prefs.getManualMode] off) the overlay covers the whole screen, since the
 * point of it is that the user never sees the automation poking around in Settings. In manual
 * mode it is instead a small card pinned to a corner: the user needs to see and use the real
 * App info screen underneath, so the overlay only needs to show progress and stay out of the
 * way. Either way the overlay can be hidden for the current run (Hide button, with a Show
 * action appearing on the notification to bring it back) or disabled for good in Settings
 * ([Prefs.getShowOverlay]). Progress is always mirrored in the foreground notification.
 *
 * Sequencing matters in automatic mode: the accessibility automation is only started
 * ([beginClosing]) once the overlay is confirmed drawn. If the overlay is disabled, or cannot be
 * shown for any reason, closing starts immediately instead, because a UI problem must never
 * block the job.
 */
class OverlayService : Service() {

    private companion object {
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "closing_progress"

        /** Channel used by earlier versions; removed on first run so it does not linger in Settings. */
        const val LEGACY_CHANNEL_ID = "taskclose_overlay"

        /** Fallback if the first-draw callback never fires. Comfortably longer than a composite. */
        const val READY_FALLBACK_MS = 350L

        /** Baseline re-assert interval, and the minimum gap between event-driven re-asserts. */
        const val REASSERT_INTERVAL_MS = 150L
        const val REASSERT_MIN_GAP_MS = 120L

        /** Burst mode re-submits the layout roughly once per frame. */
        const val BURST_FRAME_MS = 16L
        const val DEFAULT_BURST_MS = 900L

        /** How long the final summary stays visible before the service stops. */
        const val SUMMARY_LINGER_OK_MS = 900L
        const val SUMMARY_LINGER_PARTIAL_MS = 2600L

        /** Re-shows an overlay the user hid for this run. See [showOverlayPendingIntent]. */
        const val ACTION_SHOW_OVERLAY = "in.hrishikesh7665.killora.action.SHOW_OVERLAY"
        const val REQUEST_SHOW_OVERLAY = 1
    }

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val skippedLabels = mutableListOf<String>()
    private var showCard = true
    private var manualMode = false
    private var lastReassertAtMs = 0L
    private var reassertBurstDeadlineMs = 0L
    private var packagesToClose: List<String> = emptyList()
    private var closingStarted = false
    private var overlayNoticeShown = false

    /** True once the user taps Hide during a run that still wants an overlay by default, so the
     *  notification can offer a way back in instead of leaving them without one. */
    private var hiddenByUser = false

    private val readyFallback = Runnable { beginClosing() }

    /**
     * Re-submits the overlay's window layout for as long as it is on screen. This is a safety net
     * on top of the event-driven nudges from [ForceCloseAccessibilityService.onWindowEvent]:
     * it covers a glitch that happens without any accessibility event, for the cost of one
     * extra IPC every [REASSERT_INTERVAL_MS] during a run that normally lasts seconds.
     */
    private val reassertRunnable = object : Runnable {
        override fun run() {
            reassertOverlayOnTop()
            uiHandler.postDelayed(this, REASSERT_INTERVAL_MS)
        }
    }

    /**
     * Fast re-assert for short windows of time. A dialog's own enter/exit animation lasts about
     * 200-300 ms and is drawn by the compositor as a one-shot, so the baseline loop is too
     * coarse to beat it. During a burst the layout is re-submitted on almost every frame, which
     * leaves no gap for the real Settings screen to show through. Overlapping triggers extend
     * the same burst instead of starting independent ones.
     */
    private val reassertBurstRunnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() >= reassertBurstDeadlineMs) return
            reassertOverlayOnTop(force = true)
            uiHandler.postDelayed(this, BURST_FRAME_MS)
        }
    }

    private fun startReassertBurst(durationMs: Long = DEFAULT_BURST_MS) {
        val now = SystemClock.uptimeMillis()
        val wasIdle = reassertBurstDeadlineMs < now
        if (now + durationMs > reassertBurstDeadlineMs) reassertBurstDeadlineMs = now + durationMs
        if (wasIdle) {
            uiHandler.removeCallbacks(reassertBurstRunnable)
            uiHandler.post(reassertBurstRunnable)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A foreground service must call startForeground() quickly, so do it first.
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_OVERLAY) {
            // The queue (and the foreground service itself) is already running; this just
            // re-attaches the view the user previously hid.
            hiddenByUser = false
            if (overlayView == null) showOverlay()
            return START_NOT_STICKY
        }

        packagesToClose = intent?.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
        showCard = Prefs.getShowOverlay(this)
        manualMode = Prefs.getManualMode(this)
        skippedLabels.clear()
        closingStarted = false
        overlayNoticeShown = false
        hiddenByUser = false

        ForceCloseAccessibilityService.onProgress = { done, total, label, pkg ->
            uiHandler.post { updateProgress(done, total, label, pkg) }
        }
        ForceCloseAccessibilityService.onSkipped = { label -> skippedLabels.add(label) }
        ForceCloseAccessibilityService.onWindowEvent = { durationMs ->
            uiHandler.post { startReassertBurst(durationMs) }
        }
        ForceCloseAccessibilityService.onFinished = { uiHandler.post { onRunFinished() } }
        ForceCloseAccessibilityService.onAborted = { uiHandler.post { onRunAborted() } }

        if (!showCard) {
            // No overlay requested: nothing to wait for.
            beginClosing()
            return START_NOT_STICKY
        }

        if (overlayView == null) showOverlay()

        if (overlayView == null) {
            // The overlay could not be attached. Do not leave the queue stuck: start right away.
            beginClosing()
        } else {
            // Safety net in case the first-draw callback never fires.
            uiHandler.removeCallbacks(readyFallback)
            uiHandler.postDelayed(readyFallback, READY_FALLBACK_MS)
        }
        return START_NOT_STICKY
    }

    /** Starts the queue. Runs once per close request, from the first draw or the fallback timer. */
    private fun beginClosing() {
        if (closingStarted) return
        closingStarted = true
        uiHandler.removeCallbacks(readyFallback)
        ForceCloseAccessibilityService.startClosing(packagesToClose, manual = manualMode)
    }

    private fun onRunFinished() {
        val total = packagesToClose.size
        val summary = if (skippedLabels.isEmpty()) {
            resources.getQuantityString(R.plurals.closing_done_all, total, total)
        } else {
            getString(R.string.closing_done_partial, skippedLabels.joinToString(", "))
        }
        updateProgress(total, total, summary, pkg = "", finished = true)
        // The overlay already shows the result. Post a notification only when the user would
        // otherwise miss it: something failed, or the overlay was hidden or turned off.
        if (skippedLabels.isNotEmpty() || overlayView == null) {
            ResultNotifier.post(this, summary, persistent = skippedLabels.isNotEmpty())
        }
        val linger = if (skippedLabels.isEmpty()) SUMMARY_LINGER_OK_MS else SUMMARY_LINGER_PARTIAL_MS
        uiHandler.postDelayed({ stopSelf() }, linger)
    }

    private fun onRunAborted() {
        ResultNotifier.post(this, getString(R.string.closing_aborted), persistent = true)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------
    // Overlay window
    // ---------------------------------------------------------------------------------------

    /**
     * Builds and attaches the overlay. Never throws: any failure leaves [overlayView] `null`,
     * and the caller carries on without an overlay.
     */
    private fun showOverlay() {
        if (overlayView != null) return

        // The overlay is a TYPE_ACCESSIBILITY_OVERLAY window, which only a process with a
        // connected accessibility service may add. MainActivity checks this before starting us,
        // but the service can still disconnect in the meantime (killed by the OEM, switched off
        // by the user). Tell the user explicitly instead of failing silently.
        if (!ForceCloseAccessibilityService.isReady()) {
            notifyAccessibilityDisconnected()
            return
        }

        val view = try {
            inflateOverlay()
        } catch (e: RuntimeException) {
            // InflateException and friends: a broken theme or layout must not stop the closing.
            KLog.w("Closing overlay could not be built; continuing without it", e)
            return
        }

        // Keeps the full-screen card clear of notches and system bars and stops it stretching
        // across a tablet. Only the full-screen layout has this view; the manual-mode card
        // does not, hence the null check.
        view.findViewById<View>(R.id.overlayContent)?.let {
            EdgeToEdge.padForInsets(it, R.dimen.overlay_max_width)
        }

        val params = overlayLayoutParams()
        view.findViewById<View>(R.id.btnStop).setOnClickListener {
            ForceCloseAccessibilityService.cancel()
            stopSelf()
        }
        view.findViewById<View>(R.id.btnHideOverlay).setOnClickListener {
            // Hides for this run only; the Settings toggle still decides the default. Closing is
            // unaffected, since it has already started (or will, via the fallback timer).
            hiddenByUser = true
            hideOverlayView()
            refreshNotificationActions()
        }
        // Pause/resume only exists in the full (automatic-mode) layout; manual mode has nothing
        // of its own to pause, since it never acts until the user does.
        view.findViewById<View>(R.id.btnPauseResume)?.let { btn ->
            updatePauseButton(btn)
            btn.setOnClickListener {
                if (ForceCloseAccessibilityService.isPaused()) {
                    ForceCloseAccessibilityService.resume()
                } else {
                    ForceCloseAccessibilityService.pause()
                }
                updatePauseButton(btn)
            }
        }

        if (!ForceCloseAccessibilityService.addOverlayWindow(view, params)) {
            KLog.w("Closing overlay could not be attached; continuing without it")
            return
        }

        overlayView = view
        overlayParams = params
        // Wait for the first real frame before letting the automation touch Settings. This is
        // what guarantees the user sees only our screen, never the real Force stop button.
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                beginClosing()
            }
        })
        uiHandler.removeCallbacks(reassertRunnable)
        uiHandler.post(reassertRunnable)
    }

    // A window overlay has no parent ViewGroup to inflate into, so a null root is correct here.
    @SuppressLint("InflateParams")
    private fun inflateOverlay(): View {
        val themedContext = ContextThemeWrapper(
            this,
            when (ThemeManager.resolveEffective(this)) {
                ThemeMode.LIGHT -> R.style.Theme_Killora_Light
                ThemeMode.AMOLED -> R.style.Theme_Killora_Amoled
                else -> R.style.Theme_Killora_Dark
            }
        )
        // Manual mode needs the real App info screen visible and usable underneath, so it gets
        // the small corner card instead of the full-screen layout.
        val layoutRes = if (manualMode) R.layout.overlay_closing_small else R.layout.overlay_closing
        return LayoutInflater.from(themedContext).inflate(layoutRes, null)
    }

    /**
     * Automatic mode: full-screen, drawn edge to edge (under the status and navigation bars too)
     * so it reads as a dedicated "Closing apps" screen rather than a card with Settings visible
     * around it. Manual mode: a small, wrap-content card pinned to a bottom corner, since the
     * user needs to see and tap the real screen underneath - FLAG_NOT_TOUCH_MODAL already lets
     * touches outside the card's own bounds fall through to Settings.
     * FLAG_NOT_FOCUSABLE keeps it from stealing accessibility focus from the automation.
     */
    private fun overlayLayoutParams(): WindowManager.LayoutParams {
        val size = if (manualMode) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            // Above ordinary app windows and above Settings' own dialogs. See
            // ForceCloseAccessibilityService.addOverlayWindow for why this type is used.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (manualMode) {
            params.gravity = Gravity.BOTTOM or Gravity.END
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            params.x = marginPx
            params.y = marginPx
        } else {
            params.gravity = Gravity.CENTER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return params
    }

    /** Toggles the pause/resume icon and its description to match the service's current state. */
    private fun updatePauseButton(btn: View) {
        val paused = ForceCloseAccessibilityService.isPaused()
        (btn as? ImageView)?.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        btn.contentDescription = getString(
            if (paused) R.string.closing_action_resume_cd else R.string.closing_action_pause_cd
        )
    }

    /**
     * Re-submits the overlay's layout to the WindowManager. Every layout pass re-derives each
     * window's stacking order from its type, so this cheaply corrects the overlay back to the top
     * if a transient compositor glitch let the screen underneath show through, without hiding or
     * re-creating the window. Throttled, because the callers can be frequent.
     */
    private fun reassertOverlayOnTop(force: Boolean = false) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastReassertAtMs < REASSERT_MIN_GAP_MS) return
        lastReassertAtMs = now
        ForceCloseAccessibilityService.updateOverlayWindow(view, params)
    }

    private fun hideOverlayView() {
        uiHandler.removeCallbacks(reassertRunnable)
        uiHandler.removeCallbacks(reassertBurstRunnable)
        reassertBurstDeadlineMs = 0L
        overlayView?.let { ForceCloseAccessibilityService.removeOverlayWindow(it) }
        overlayView = null
        overlayParams = null
        // Progress keeps flowing through the notification.
    }

    // ---------------------------------------------------------------------------------------
    // Progress reporting
    // ---------------------------------------------------------------------------------------

    private fun updateProgress(done: Int, total: Int, label: String, pkg: String, finished: Boolean = false) {
        val percent = if (total == 0) 100 else (done * 100 / total)

        overlayView?.let { view ->
            try {
                bindOverlay(view, done, total, percent, label, pkg, finished)
            } catch (e: RuntimeException) {
                // A view problem must never interrupt the close run: drop the overlay, keep going.
                KLog.w("Updating the overlay failed; hiding it", e)
                hideOverlayView()
            }
        }

        val title = getString(if (finished) R.string.app_name else R.string.closing_title)
        val text = if (pkg.isBlank()) label else getString(R.string.closing_current_app, label, pkg)
        updateNotification(title, text, percent, indeterminate = total == 0, ongoing = !finished)
    }

    private fun bindOverlay(
        view: View, done: Int, total: Int, percent: Int, label: String, pkg: String, finished: Boolean
    ) {
        // The ring and its percent label only exist in the full (automatic-mode) layout; the
        // small manual-mode card uses the linear bar below as its only progress indicator.
        view.findViewById<TextView>(R.id.tvPercent)?.text = getString(R.string.closing_percent, percent)
        view.findViewById<RamGaugeView>(R.id.progressRing)?.percent = percent.toFloat()
        view.findViewById<ProgressBar>(R.id.progressLinear)?.apply {
            isIndeterminate = total == 0
            progress = percent
        }
        view.findViewById<ImageView>(R.id.imgCurrentAppIcon)?.apply {
            val icon = pkg.takeIf { it.isNotBlank() }?.let { iconFor(it) }
            if (icon != null) setImageDrawable(icon)
            isVisible = icon != null
        }
        view.findViewById<TextView>(R.id.tvCurrentApp).text = label
        view.findViewById<TextView>(R.id.tvCurrentPackage)?.apply {
            isVisible = pkg.isNotBlank()
            if (pkg.isNotBlank()) text = pkg
        }
        view.findViewById<TextView>(R.id.tvProgressCount)?.apply {
            isVisible = total != 0
            if (total != 0) text = getString(R.string.closing_progress_count, done.coerceAtMost(total), total)
        }
        val controlsVisible = !finished
        view.findViewById<View>(R.id.btnStop).isVisible = controlsVisible
        view.findViewById<View>(R.id.btnHideOverlay).isVisible = controlsVisible
        view.findViewById<View>(R.id.btnPauseResume)?.isVisible = controlsVisible
        view.findViewById<TextView>(R.id.tvStatusHint)?.setText(
            when {
                finished -> R.string.closing_hint_finishing
                manualMode -> R.string.closing_hint_manual
                else -> R.string.closing_hint_wait
            }
        )
    }

    /** Best-effort icon lookup. A package uninstalled mid-queue must not interrupt progress. */
    private fun iconFor(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------------------

    private fun startForegroundNotification() {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.closing_starting))
            .setSmallIcon(R.drawable.ic_stat_killora)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_closing_name), NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    /** Remembers the last notification content so [refreshNotificationActions] can re-post it
     *  with an updated action set without needing the current progress values on hand. */
    private var lastNotificationTitle: String? = null
    private var lastNotificationText: String? = null
    private var lastNotificationPercent = 0
    private var lastNotificationIndeterminate = true
    private var lastNotificationOngoing = true

    private fun updateNotification(
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
        contentIntent: PendingIntent? = null
    ) {
        lastNotificationTitle = title
        lastNotificationText = text
        lastNotificationPercent = percent
        lastNotificationIndeterminate = indeterminate
        lastNotificationOngoing = ongoing
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_killora)
            .setProgress(100, percent, indeterminate)
            .setOngoing(ongoing)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        // Give the user a one-tap way back in if they hid the overlay but the run is still going.
        if (hiddenByUser && ongoing) {
            builder.addAction(R.drawable.ic_visibility, getString(R.string.closing_action_show), showOverlayPendingIntent())
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    /** Re-posts the last notification content, picking up a changed [hiddenByUser] action set. */
    private fun refreshNotificationActions() {
        val title = lastNotificationTitle ?: return
        val text = lastNotificationText ?: return
        updateNotification(title, text, lastNotificationPercent, lastNotificationIndeterminate, lastNotificationOngoing)
    }

    private fun showOverlayPendingIntent(): PendingIntent {
        val intent = Intent(this, OverlayService::class.java).setAction(ACTION_SHOW_OVERLAY)
        return PendingIntent.getService(
            this, REQUEST_SHOW_OVERLAY, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Shown at most once per run when the overlay is wanted but the accessibility service is not
     * connected. The notification links straight to Accessibility settings. Closing itself
     * carries on in the background either way; it just lacks the overlay.
     */
    private fun notifyAccessibilityDisconnected() {
        if (overlayNoticeShown) return
        overlayNoticeShown = true
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        updateNotification(
            getString(R.string.closing_overlay_unavailable_title),
            getString(R.string.closing_overlay_unavailable_text),
            percent = 0,
            indeterminate = true,
            ongoing = true,
            contentIntent = pendingIntent
        )
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        hideOverlayView()
        ForceCloseAccessibilityService.onProgress = null
        ForceCloseAccessibilityService.onSkipped = null
        ForceCloseAccessibilityService.onFinished = null
        ForceCloseAccessibilityService.onAborted = null
        ForceCloseAccessibilityService.onWindowEvent = null
        // The run is over (finished, cancelled, or aborted): let the screen untick what stopped.
        CloseTracker.finish()
        super.onDestroy()
    }
}

/** Intent extra carrying the package names for [OverlayService] to close. */
const val EXTRA_PACKAGES = "packages"
