@file:Suppress("SpellCheckingInspection") // Root-manager names (Magisk, KernelSU, ...) are not dictionary words.

package `in`.hrishikesh7665.killora

import android.content.Context
import android.content.pm.PackageManager
import java.io.DataOutputStream
import java.io.File

/**
 * Root detection and root-level force stop.
 *
 * Three questions matter and are kept deliberately separate:
 *  - [isDeviceRooted]: is a su binary present at all, so the device is *capable* of root?
 *  - [isRootManagerInstalled]: is a root manager app installed? A manager can be present without
 *    having granted this app access yet, which is the common "Magisk is here but hasn't granted
 *    us root" case the UI warns about.
 *  - [isRootGranted]: has the user actually accepted the su prompt for this app? This is the only
 *    check that may show a root-manager permission dialog.
 *
 * All functions here do blocking I/O. Call them off the main thread.
 */
object RootUtils {

    private val SU_PATHS = arrayOf(
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
        "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
        "/su/bin/su"
    )

    /** Known root-manager packages. Presence means a root solution is installed, whether
     *  this app has been granted access to it. */
    private val ROOT_MANAGER_PACKAGES = arrayOf(
        "com.topjohnwu.magisk", // Magisk
        "com.topjohnwu.magisk.canary",
        "me.weishu.kernelsu", // KernelSU
        "me.bmax.apatch", // APatch
        "eu.chainfire.supersu", // SuperSU
        "com.noshufou.android.su",
        "com.koushikdutta.superuser"
    )

    /** Android package names only ever contain these characters. Anything else is rejected
     *  before it can reach a root shell. */
    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private const val RETRY_PAUSE_MS = 250L

    /** True if a su binary is present on the device. */
    fun isDeviceRooted(): Boolean = SU_PATHS.any { File(it).exists() } || whichSuFindsBinary()

    private fun whichSuFindsBinary(): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("which", "su")).inputStream.bufferedReader().readText().isNotBlank()
    } catch (_: java.io.IOException) {
        false
    }

    /** True if a known root-manager app (Magisk, KernelSU, APatch, SuperSU, and so on) is
     *  installed, regardless of whether this app has been granted root yet. */
    fun isRootManagerInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return ROOT_MANAGER_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * True only if the user has actually granted root to this app (`su -c id` reports uid=0).
     * The first call may show a root-manager permission prompt.
     */
    fun isRootGranted(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() == 0 && output.contains("uid=0")
    } catch (_: java.io.IOException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Force-stops every package as root, verifying each one afterward (with [AppCloser.isPackageStopped],
     * the same FLAG_STOPPED check Android sets after a real force stop) and retrying the ones that
     * did not stop, up to [maxAttempts] times in total.
     *
     * @return the packages still not confirmed stopped after every attempt, so the caller can tell
     *   the user which apps need a manual close. Returns [packages] unchanged, having attempted
     *   nothing, if root access is not granted.
     */
    fun forceStopAsRootVerified(
        context: Context,
        packages: List<String>,
        maxAttempts: Int = 3
    ): List<String> {
        if (!isRootGranted()) return packages
        var remaining = packages
        var attempt = 0
        while (remaining.isNotEmpty() && attempt < maxAttempts) {
            attempt++
            runForceStopCommand(remaining)
            Thread.sleep(RETRY_PAUSE_MS)
            remaining = remaining.filterNot { AppCloser.isPackageStopped(context, it) }
        }
        return remaining
    }

    /** Runs `am force-stop <pkg>` as root for each valid package name. Returns true if the su
     *  shell exited cleanly. That alone does not prove every app stopped. */
    private fun runForceStopCommand(packages: List<String>): Boolean = try {
        val process = Runtime.getRuntime().exec("su")
        DataOutputStream(process.outputStream).use { os ->
            for (pkg in packages.filter { PACKAGE_NAME_REGEX.matches(it) }) {
                os.writeBytes("am force-stop $pkg\n")
            }
            os.writeBytes("exit\n")
            os.flush()
        }
        process.waitFor() == 0
    } catch (_: java.io.IOException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
