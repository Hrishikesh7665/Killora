package `in`.hrishikesh7665.killora

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystemApp: Boolean,
    val isEssential: Boolean = false,
    /** True if the app currently has a running process (or is not in FLAG_STOPPED). */
    val isRunning: Boolean = true,
    /** From PackageInfo.versionName. Null for the rare package where the OS can't resolve
     *  one (so the UI can fall back to showing just the package name). */
    val versionName: String? = null,
    var selected: Boolean = false
)
