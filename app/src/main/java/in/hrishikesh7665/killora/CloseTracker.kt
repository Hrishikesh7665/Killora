package `in`.hrishikesh7665.killora

/**
 * Remembers which packages the user just asked to close, so the screen can untick the ones that
 * really did stop once the whole close request (including the Accessibility "deep" pass) is over.
 *
 * Lifecycle: [begin] when the user taps Close, [finish] when everything has ended (nothing more
 * to do, a prompt was dismissed, or [OverlayService] stopped). Until [finish], [drainIfIdle]
 * hands out nothing, so a background refresh can never untick apps while they are still being
 * stopped one by one.
 *
 * It lives in memory only: a selection is a transient UI state. An app that is still running
 * after the request simply stays ticked.
 */
object CloseTracker {

    private val pending = LinkedHashSet<String>()
    private var busy = false

    /** Set by the visible screen; called on the main thread when a request has ended so the
     *  lists can update straight away instead of waiting for the next refresh tick. */
    @Volatile
    var onFinished: (() -> Unit)? = null

    @Synchronized
    fun begin(packages: Collection<String>) {
        pending.addAll(packages)
        busy = true
    }

    /** Marks the request as over. The tracked packages stay until [drainIfIdle] collects them. */
    fun finish() {
        synchronized(this) { busy = false }
        onFinished?.invoke()
    }

    /** Returns and clears the tracked packages, or an empty set while a request is in flight. */
    @Synchronized
    fun drainIfIdle(): Set<String> {
        if (busy || pending.isEmpty()) return emptySet()
        val out = pending.toSet()
        pending.clear()
        return out
    }
}
