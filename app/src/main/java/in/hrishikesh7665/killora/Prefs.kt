package `in`.hrishikesh7665.killora

import android.content.Context
import androidx.core.content.edit

/** Single SharedPreferences store for every app-level setting: theme, filter toggles, and the
 *  saved Quick List used by the app, the pinned shortcut and the widget. Use [QuickList] to
 *  change the Quick List rather than writing it here directly. */
object Prefs {
    // Keep this name. It comes from an earlier version of the app, and changing it would make
    // every existing user lose their saved Quick List and settings.
    private const val NAME = "taskclose_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_QUICK_LIST = "quick_list"
    private const val KEY_SHOW_SYSTEM = "show_system"
    private const val KEY_SHOW_ESSENTIAL = "show_essential"
    private const val KEY_SHOW_OVERLAY = "show_closing_overlay"
    private const val KEY_MANUAL_MODE = "manual_close_mode"
    private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    private const val KEY_QUICK_LIST_SHOW_STOPPED = "quick_list_show_stopped"

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getThemeModeName(context: Context): String = sp(context).getString(KEY_THEME, "SYSTEM") ?: "SYSTEM"
    fun setThemeModeName(context: Context, name: String) = sp(context).edit { putString(KEY_THEME, name) }

    fun saveQuickList(context: Context, packages: List<String>) =
        sp(context).edit { putString(KEY_QUICK_LIST, packages.joinToString(",")) }

    fun getQuickList(context: Context): List<String> {
        val raw = sp(context).getString(KEY_QUICK_LIST, "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }.distinct()
    }

    fun setShowSystem(context: Context, value: Boolean) = sp(context).edit { putBoolean(KEY_SHOW_SYSTEM, value) }
    fun getShowSystem(context: Context): Boolean = sp(context).getBoolean(KEY_SHOW_SYSTEM, false)

    fun setShowEssential(context: Context, value: Boolean) = sp(context).edit { putBoolean(KEY_SHOW_ESSENTIAL, value) }
    fun getShowEssential(context: Context): Boolean = sp(context).getBoolean(KEY_SHOW_ESSENTIAL, false)

    /** Whether the progress overlay (full-screen in automatic mode, a small corner card in
     *  manual mode - see [getManualMode]) is shown during a deep force stop. On by default.
     *  When off, closing still runs and reports progress in the notification. */
    fun setShowOverlay(context: Context, value: Boolean) = sp(context).edit { putBoolean(KEY_SHOW_OVERLAY, value) }
    fun getShowOverlay(context: Context): Boolean = sp(context).getBoolean(KEY_SHOW_OVERLAY, true)

    /** Off by default. When on, the deep force-stop pass opens each app's App info screen and
     *  waits for *you* to tap Force stop and press back yourself, then moves to the next app -
     *  instead of tapping it for you. It never scrolls or taps anything on your behalf; it only
     *  opens the screen and watches for you to navigate away from it. See [AppCloser] and
     *  [ForceCloseAccessibilityService] for how the automatic pass differs. */
    fun setManualMode(context: Context, value: Boolean) = sp(context).edit { putBoolean(KEY_MANUAL_MODE, value) }
    fun getManualMode(context: Context): Boolean = sp(context).getBoolean(KEY_MANUAL_MODE, false)

    /** Whether the one-time "run reliably in the background" prompt has been shown. It targets
     *  OEM skins that kill accessibility services (MIUI, ColorOS, Funtouch OS, and so on) and
     *  is shown once so it never nags on every close. */
    fun setBatteryPromptShown(context: Context, value: Boolean) = sp(context).edit { putBoolean(KEY_BATTERY_PROMPT_SHOWN, value) }
    fun getBatteryPromptShown(context: Context): Boolean = sp(context).getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)

    /** On by default, so nothing you've saved is ever hidden unless you choose to hide it.
     *  When off, the Quick List tab only shows apps that are currently running. */
    fun setQuickListShowStopped(context: Context, value: Boolean) =
        sp(context).edit { putBoolean(KEY_QUICK_LIST_SHOW_STOPPED, value) }
    fun getQuickListShowStopped(context: Context): Boolean =
        sp(context).getBoolean(KEY_QUICK_LIST_SHOW_STOPPED, true)
}
