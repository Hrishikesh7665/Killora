package `in`.hrishikesh7665.killora

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/** Whether Killora's [ForceCloseAccessibilityService] is currently enabled in system
 *  Settings. Shared by every screen that needs to know before offering "Deep force
 *  stop" (MainActivity, QuickCloseActivity, SettingsActivity). */
object AccessibilityUtils {

    fun isForceCloseServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${ForceCloseAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
