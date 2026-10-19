package `in`.hrishikesh7665.killora

import android.content.Context

/**
 * The user's saved Quick List: the apps closed by one tap from the app, the home-screen
 * shortcut and the widget.
 *
 * It is a persistent collection that is only ever changed by an explicit user action. Adding
 * merges into the existing list (nothing is replaced), order is preserved, and duplicates are
 * ignored. Apps that were uninstalled stay in the list so that reinstalling them just works;
 * closing simply skips an app that is not there.
 */
object QuickList {

    /** Outcome of [add], for a precise confirmation message. */
    data class AddResult(val added: Int, val alreadyPresent: Int)

    @Synchronized
    fun load(context: Context): List<String> = Prefs.getQuickList(context)

    /** Appends every package not already saved, keeping existing entries and their order. */
    @Synchronized
    fun add(context: Context, packages: Collection<String>): AddResult {
        val requested = packages.distinct()
        val current = Prefs.getQuickList(context)
        val fresh = requested.filterNot { it in current }
        if (fresh.isNotEmpty()) Prefs.saveQuickList(context, current + fresh)
        return AddResult(added = fresh.size, alreadyPresent = requested.size - fresh.size)
    }

    @Synchronized
    fun remove(context: Context, packageName: String) {
        Prefs.saveQuickList(context, Prefs.getQuickList(context).filterNot { it == packageName })
    }

    @Synchronized
    fun clear(context: Context) {
        Prefs.saveQuickList(context, emptyList())
    }

    /** Restores a previous state, used by Undo. */
    @Synchronized
    fun replaceAll(context: Context, packages: List<String>) {
        Prefs.saveQuickList(context, packages.distinct())
    }
}
