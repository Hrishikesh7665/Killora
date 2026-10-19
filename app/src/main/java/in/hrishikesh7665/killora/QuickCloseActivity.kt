package `in`.hrishikesh7665.killora

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Entry point for the pinned home-screen shortcut and the widget's "Close All" button.
 * Closes the saved Quick List without opening Killora's own UI.
 *
 * The activity is transparent and stays alive only until the work is done (it must, because
 * starting the deep force-stop foreground service is only allowed while we are visible).
 * Since it has no window to show a message in, the outcome goes to [ResultNotifier].
 */
class QuickCloseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packages = Prefs.getQuickList(this)
        if (packages.isEmpty()) {
            ResultNotifier.post(this, getString(R.string.quick_close_empty), persistent = false)
            finish()
            return
        }

        val appContext = applicationContext
        // Root commands block, so keep all closing off the main thread.
        Background.io {
            val report = AppCloser.closeNow(appContext, packages)
            Background.main { onClosed(report) }
        }
    }

    private fun onClosed(report: CloseReport) {
        if (report.deepCandidates.isNotEmpty() && Prefs.getManualMode(this)) {
            // Manual mode needs no Accessibility Service, but it does need a visible screen to
            // guide you through each app, so hand the apps to Killora's main screen.
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putStringArrayListExtra(ManualCloser.EXTRA_MANUAL_PACKAGES, ArrayList(report.deepCandidates))
            startActivity(intent)
        } else if (report.deepCandidates.isNotEmpty()) {
            if (AccessibilityUtils.isForceCloseServiceEnabled(this)) {
                // The overlay is the feedback for the deep pass, so no extra notification here.
                val intent = Intent(this, OverlayService::class.java)
                intent.putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(report.deepCandidates))
                ContextCompat.startForegroundService(this, intent)
            } else {
                ResultNotifier.post(this, getString(R.string.quick_close_deep_not_ready), persistent = true)
            }
        } else {
            ResultNotifier.post(this, report)
        }
        finish()
    }
}
