@file:Suppress("SpellCheckingInspection") // OEM and brand names are not dictionary words.

package `in`.hrishikesh7665.killora

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Helps the user keep Killora's Accessibility Service alive on phones with aggressive
 * background management.
 *
 * Stock Android and most of One UI leave an enabled accessibility service alone. Several other
 * OEM skins add their own "autostart" or background manager on top of normal battery
 * optimization. That manager can suspend the service, or block it from acting on another app's
 * screen, even while it shows as enabled in system Settings. The result looks like a bug
 * (nothing gets tapped) but the cause is the OEM restriction. It is the main reason the Force
 * stop automation works on a Pixel and does nothing on a Xiaomi, realme, or Vivo phone.
 *
 * There is no public Android API for this, so we jump to the manufacturer's own screen when we
 * know it, and fall back step by step:
 *  1. Try every known activity for this manufacturer. OEMs rename them between firmware
 *     versions, so the first one that resolves wins.
 *  2. Otherwise, open the standard "ignore battery optimizations" request, which exists on every
 *     Android version and at least covers Doze and App Standby.
 *  3. Otherwise, open this app's own App info page so the user can find Battery settings by hand.
 */
object OemAutostartHelper {
    private data class Candidate(val pkg: String, val cls: String)

    // Component names taken from widely used, public "open autostart manager" lists. OEMs change
    // them across firmware versions without notice, so each manufacturer lists several known
    // activities, and they are tried in order.
    private val candidatesByManufacturer: Map<String, List<Candidate>> = mapOf(
        "xiaomi" to listOf(
            Candidate("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Candidate("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            Candidate("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
        ),
        "redmi" to listOf(
            Candidate("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        "poco" to listOf(
            Candidate("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        "oppo" to listOf(
            Candidate("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Candidate("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Candidate("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Candidate("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")
        ),
        "realme" to listOf(
            Candidate("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Candidate("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Candidate("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        ),
        "vivo" to listOf(
            Candidate("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Candidate("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Candidate("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        ),
        "iqoo" to listOf(
            Candidate("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Candidate("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        ),
        "samsung" to listOf(
            Candidate("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            Candidate("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        ),
        "huawei" to listOf(
            Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        ),
        "honor" to listOf(
            Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        ),
        "oneplus" to listOf(
            Candidate("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            Candidate("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        ),
        "asus" to listOf(
            Candidate("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
            Candidate("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
        ),
        "letv" to listOf(
            Candidate("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        ),
        "meizu" to listOf(
            Candidate("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity")
        ),
        "lenovo" to listOf(
            Candidate("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity")
        ),
        "nokia" to listOf(
            Candidate("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")
        )
    )

    /** Short, human-readable name of the current OEM skin, for display text. Unlisted brands
     *  fall back to the raw manufacturer name. The skin names are brand names and stay as they
     *  are in every language; only the last-resort fallback is a translated string. */
    fun skinName(context: Context): String {
        return when (normalizedManufacturer()) {
            "xiaomi", "redmi", "poco" -> "MIUI / HyperOS"
            "oppo", "realme", "oneplus" -> "ColorOS / realme UI / OxygenOS"
            "vivo", "iqoo" -> "Funtouch OS / OriginOS"
            "samsung" -> "One UI"
            "huawei" -> "EMUI"
            "honor" -> "MagicOS"
            else -> Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: context.getString(R.string.oem_fallback_device_name)
        }
    }

    /** True for manufacturers known to run a stricter background manager than stock Android.
     *  On those, Killora explains the extra step instead of relying on the standard permissions. */
    fun hasAggressiveBackgroundRestrictions(): Boolean =
        normalizedManufacturer() in candidatesByManufacturer.keys

    private fun normalizedManufacturer(): String =
        (Build.MANUFACTURER ?: "").trim().lowercase()

    /** Opens the best autostart/background-permission screen we know about for this
     *  device. Returns true if something was actually launched. */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = candidatesByManufacturer[normalizedManufacturer()] ?: emptyList()
        for ((pkg, cls) in candidates) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                KLog.d("Opened autostart settings via $pkg/$cls")
                return true
            } catch (_: ActivityNotFoundException) {
                // Expected on firmware where this activity moved or was renamed. Try the next one.
            } catch (e: RuntimeException) {
                KLog.w("Autostart intent failed for $pkg/$cls: ${e.message}")
            }
        }
        return openBatteryOptimizationSettings(context)
    }

    /**
     * Standard, documented-API fallback available on every Android version.
     *
     * This opens the general "Battery optimization" list screen (ACTION_IGNORE_BATTERY_
     * OPTIMIZATION_SETTINGS) rather than requesting a direct exemption for this app
     * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). The direct request needs the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission, which Play policy restricts to a narrow
     * set of app categories Killora does not fall into; the list screen needs no special
     * permission at all; the user finds Killora in it and switches it off themselves, which is
     * the same outcome with one extra, explicit tap.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: RuntimeException) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri()
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
                true
            } catch (e2: RuntimeException) {
                KLog.w("No battery optimization screen could be opened: ${e2.message}")
                false
            }
        }
    }

    /** Whether this app is already exempt from Doze and App Standby restrictions. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
