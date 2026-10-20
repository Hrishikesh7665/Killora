package `in`.hrishikesh7665.killora

import android.app.Activity
import android.graphics.Rect
import android.view.View
import androidx.annotation.DimenRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Layout helpers that make one set of screens fit any display: phones, tablets, foldables, both
 * orientations, notches and gesture bars, split-screen windows.
 */
object EdgeToEdge {

    /**
     * Call right after `setContentView`.
     *
     * @param content the screen's root view, or for a scrolling screen the single child of the
     *   scroll view. Its own XML padding is kept; everything below is added on top of it.
     * @param maxWidthRes dimen for the widest the content may get. On a bigger window the content
     *   stays that wide and centered instead of stretching across a tablet.
     */
    fun setUp(activity: Activity, content: View, @DimenRes maxWidthRes: Int) {
        styleSystemBars(activity)
        padForInsets(content, maxWidthRes)
    }

    /** Dark icons on the light theme, light icons on the dark ones. Without this, a light theme
     *  can end up with white clock and battery icons on a near-white status bar. */
    private fun styleSystemBars(activity: Activity) {
        val light = ThemeManager.resolveEffective(activity) == ThemeMode.LIGHT
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    /**
     * Keeps [content] clear of the status bar, navigation bar, display cutout and on-screen
     * keyboard, and centers it once the window is wider than [maxWidthRes].
     *
     * The width limit is applied right away, not only when insets arrive, because on older
     * Android versions a window that is not edge-to-edge never delivers insets to this view.
     */
    fun padForInsets(content: View, @DimenRes maxWidthRes: Int) {
        val baseStart = content.paddingStart
        val baseTop = content.paddingTop
        val baseEnd = content.paddingEnd
        val baseBottom = content.paddingBottom
        val maxWidthPx = content.resources.getDimensionPixelSize(maxWidthRes)

        content.setPaddingRelative(
            baseStart + extraSidePadding(content, maxWidthPx), baseTop,
            baseEnd + extraSidePadding(content, maxWidthPx), baseBottom
        )

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val extra = extraSidePadding(view, maxWidthPx)
            view.setPaddingRelative(
                baseStart + extra + (if (rtl) bars.right else bars.left),
                baseTop + bars.top,
                baseEnd + extra + (if (rtl) bars.left else bars.right),
                baseBottom + bars.bottom
            )
            // Not consumed: siblings such as the Snackbar still need to see the insets.
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
    }

    /** Padding that centers content of at most [maxWidthPx] in the current window. Uses the
     *  configuration width, which follows the window in split-screen and freeform modes. */
    private fun extraSidePadding(view: View, maxWidthPx: Int): Int {
        val resources = view.resources
        val windowWidthPx = (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt()
        return if (windowWidthPx > maxWidthPx) (windowWidthPx - maxWidthPx) / 2 else 0
    }

    /**
     * Gives [list] one column on a phone in portrait and more on wide windows (see
     * the integers.xml files under res), with an even gap between columns. A single column behaves
     * exactly like a plain vertical list.
     */
    fun configureList(list: RecyclerView) {
        val context = list.context
        val columns = context.resources.getInteger(R.integer.list_columns).coerceAtLeast(1)
        if (columns == 1) {
            list.layoutManager = LinearLayoutManager(context)
            return
        }
        list.layoutManager = GridLayoutManager(context, columns)
        list.addItemDecoration(
            ColumnGap(context.resources.getDimensionPixelSize(R.dimen.list_column_gap), columns)
        )
    }

    /** Splits the gap between neighbouring columns evenly, so every card is the same width. */
    private class ColumnGap(private val gap: Int, private val columns: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val params = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
            val start = if (params.spanIndex == 0) 0 else gap / 2
            val end = if (params.spanIndex == columns - 1) 0 else gap / 2
            if (parent.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                outRect.left = end
                outRect.right = start
            } else {
                outRect.left = start
                outRect.right = end
            }
        }
    }
}
