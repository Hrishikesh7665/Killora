package `in`.hrishikesh7665.killora

import android.util.Log

/**
 * Thin logging wrapper.
 *
 * Verbose diagnostics are written only in debug builds. A release build therefore never puts
 * the contents of another app's screen, or a detailed trace of what Killora is doing, into
 * logcat. Warnings are always kept because they describe failures and carry no screen content.
 */
internal object KLog {
    private const val TAG = "Killora"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }
}
