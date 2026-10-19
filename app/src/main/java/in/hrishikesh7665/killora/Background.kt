package `in`.hrishikesh7665.killora

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Tiny helper for "do slow work off the main thread, then touch the UI".
 *
 * Callers must still check that their Activity is alive before touching views in the
 * [main] callback, because the work can finish after the screen has gone.
 */
object Background {
    private val pool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runs [block] on a worker thread. */
    fun io(block: () -> Unit) {
        pool.execute(block)
    }

    /** Runs [block] on the main thread. */
    fun main(block: () -> Unit) {
        mainHandler.post(block)
    }
}
