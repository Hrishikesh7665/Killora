package `in`.hrishikesh7665.killora

import android.content.Context

/**
 * Everything that happened during one close request, so every screen can show the same single,
 * meaningful summary instead of a message per step.
 *
 * @property closed labels of apps that were closed (verified when root was used, the best effort otherwise).
 * @property alreadyStopped labels of apps that were skipped because they were not running.
 * @property failed labels of apps that could not be force-stopped.
 * @property deepCandidates packages the optional deep force-stop pass should still handle.
 *   Empty when root already closed everything, so the Accessibility pass is skipped.
 */
data class CloseReport(
    val closed: List<String> = emptyList(),
    val alreadyStopped: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val deepCandidates: List<String> = emptyList()
) {
    /** True when the user needs to know about something that did not work. */
    val hasProblems: Boolean get() = failed.isNotEmpty()

    /** One line such as "Closed 3 apps · 1 already stopped · couldn't close: Maps". */
    fun summary(context: Context): String {
        val res = context.resources
        val parts = mutableListOf<String>()
        if (closed.isNotEmpty()) {
            parts += res.getQuantityString(R.plurals.close_summary_closed, closed.size, closed.size)
        }
        if (alreadyStopped.isNotEmpty()) {
            parts += res.getQuantityString(
                R.plurals.close_summary_already_stopped, alreadyStopped.size, alreadyStopped.size
            )
        }
        if (failed.isNotEmpty()) {
            parts += context.getString(R.string.close_summary_failed, failed.joinToString(", "))
        }
        return if (parts.isEmpty()) {
            context.getString(R.string.close_nothing_to_close)
        } else {
            parts.joinToString(context.getString(R.string.close_summary_separator))
        }
    }
}
