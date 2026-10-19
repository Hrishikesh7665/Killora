package `in`.hrishikesh7665.killora

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Identifies "essential" system packages — the ones that can destabilize or soft-lock the
 * device if force-stopped (System UI, telephony, the active launcher/keyboard, Play
 * services, permission controller, etc.). These are hidden from the list by default, even
 * when "Show system apps" is on. They appear only after the user accepts the risk in the
 * warning dialog in MainActivity.
 *
 * The list is a sensible safety default, not a guarantee. Edit CORE_PROTECTED_PACKAGES if your
 * device has other OEM processes you want to keep protected.
 */
object EssentialApps {

    private val CORE_PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.media",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.se",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher"
    )

    fun isEssential(context: Context, packageName: String, ai: ApplicationInfo): Boolean =
        packageName in CORE_PROTECTED_PACKAGES ||
            (ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0 ||
            packageName == defaultLauncherPackage(context) ||
            packageName == defaultImePackage(context)

    private fun defaultLauncherPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolve?.activityInfo?.packageName
    }

    private fun defaultImePackage(context: Context): String? {
        val value = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return null
        return value.substringBefore('/')
    }
}
