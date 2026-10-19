package `in`.hrishikesh7665.killora

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** Reads/writes the user's chosen [ThemeMode] and resolves it to a concrete Activity theme. */
object ThemeManager {

    fun getMode(context: Context): ThemeMode =
        try { ThemeMode.valueOf(Prefs.getThemeModeName(context)) } catch (_: Exception) { ThemeMode.SYSTEM }

    fun setMode(context: Context, mode: ThemeMode) = Prefs.setThemeModeName(context, mode.name)

    /**
     * Resolves SYSTEM to a concrete theme. When following the system setting and the
     * system is in dark mode, AMOLED is used instead of the plain Dark theme (AMOLED is
     * prioritized over Dark whenever auto-selection applies, per the user's request).
     */
    fun resolveEffective(context: Context): ThemeMode {
        val mode = getMode(context)
        if (mode != ThemeMode.SYSTEM) return mode
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) ThemeMode.AMOLED else ThemeMode.LIGHT
    }

    private fun styleResId(effective: ThemeMode): Int = when (effective) {
        ThemeMode.LIGHT -> R.style.Theme_Killora_Light
        ThemeMode.DARK -> R.style.Theme_Killora_Dark
        ThemeMode.AMOLED -> R.style.Theme_Killora_Amoled
        ThemeMode.SYSTEM -> R.style.Theme_Killora_Light // unreachable, resolveEffective never returns SYSTEM
    }

    /** Call after super.onCreate() and before setContentView(). */
    fun apply(activity: Activity) {
        activity.setTheme(styleResId(resolveEffective(activity)))
    }
}
