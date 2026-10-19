package `in`.hrishikesh7665.killora

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.toColorInt

/**
 * Arc gauge with a blue-to-purple progress ring.
 *
 * The stroke width scales with the view's size, so the same gauge looks right small (the 46dp
 * status strip) or large (the closing overlay). Everything that depends on size is computed in
 * [onSizeChanged], so [onDraw] allocates nothing.
 */
class RamGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themedTrackColor(context)
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcBounds = RectF()
    private val minStrokePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, MIN_STROKE_DP, context.resources.displayMetrics
    )

    var percent: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate()
        }

    private fun themedTrackColor(context: Context): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(R.attr.gaugeTrack, typedValue, true)) {
            typedValue.data
        } else {
            FALLBACK_TRACK_COLOR.toColorInt()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val strokeWidth = (minOf(w, h) * STROKE_FRACTION).coerceAtLeast(minStrokePx)
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth

        val pad = strokeWidth / 2f + 1f
        arcBounds.set(pad, pad, w - pad, h - pad)
        progressPaint.shader = LinearGradient(
            arcBounds.left, arcBounds.top, arcBounds.right, arcBounds.bottom,
            GRADIENT_START.toColorInt(), GRADIENT_END.toColorInt(),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_TOTAL, false, trackPaint)
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_TOTAL * (percent / 100f), false, progressPaint)
    }

    private companion object {
        const val MIN_STROKE_DP = 3f
        const val STROKE_FRACTION = 0.22f
        const val START_ANGLE = 150f
        const val SWEEP_TOTAL = 240f
        const val FALLBACK_TRACK_COLOR = "#1C2740"
        const val GRADIENT_START = "#3EA6FF"
        const val GRADIENT_END = "#8C6CFF"
    }
}
