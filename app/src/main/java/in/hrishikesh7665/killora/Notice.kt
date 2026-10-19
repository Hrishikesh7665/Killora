package `in`.hrishikesh7665.killora

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar

/**
 * The one way screens tell the user something.
 *
 * Every message can be dismissed with a button, so none of them can get in the way:
 *  - A normal message hides itself after a few seconds.
 *  - A message with `persistent = true` stays until dismissed. Use it only when the user must
 *    notice something, such as an app that could not be closed.
 *  - A message can carry its own action (for example Undo or View) in place of "Dismiss".
 *
 * Callers should show **one** message per user action, summarizing the outcome, instead of a
 * message for every step. Activities with no window of their own (the shortcut and widget entry
 * point) use [ResultNotifier] instead.
 */
object Notice {

    fun show(
        activity: Activity,
        message: CharSequence,
        persistent: Boolean = false,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ) {
        val root: View = activity.findViewById(android.R.id.content) ?: return
        val duration = if (persistent) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG
        val snackbar = Snackbar.make(root, message, duration)
        // The default two lines would cut a multipart summary short.
        snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = MAX_LINES
        // Always offer an explicit way out; a custom action replaces the plain "Dismiss".
        snackbar.setAction(actionLabel ?: activity.getString(R.string.notice_dismiss)) { onAction?.invoke() }
        snackbar.show()
    }

    private const val MAX_LINES = 4
}
