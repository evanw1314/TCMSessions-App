package ai.meridian.tcmsession

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One minute's band shares as a pie: the session card's before/after pies
 * (BandShares). Custom-drawn like BandChartView, with no charting dependency.
 * Slices follow BAND_ORDER, lowest band first, clockwise from 12 o'clock, in
 * the same bandColor as the chart and tables, so a color means the same band
 * everywhere on screen. The whole-number percent is printed on any slice big
 * enough to hold it; the table under the pies has every number. Square: as
 * tall as it is wide, up to [MAX_SIZE_DP].
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var percents: List<Int> = emptyList()
    private val oval = RectF()

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Slice edges in the card's own surface color, so neighbouring slices
    // stay apart in both themes.
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = MaterialColors.getColor(context, MaterialR.attr.colorSurface, Color.WHITE)
    }
    // White with a soft dark shadow reads on every band color in both themes.
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(2.5f * density, 0f, 0f, Color.argb(170, 0, 0, 0))
    }

    /** Whole percents in BAND_ORDER that add up to 100 (BandShares.wholePercents). */
    fun setPercents(values: List<Int>) {
        percents = values
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, min(width, (MAX_SIZE_DP * density).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = percents.sum()
        if (total <= 0) return
        val r = (min(width, height) - edgePaint.strokeWidth) / 2f
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)

        var start = -90f
        percents.forEachIndexed { i, p ->
            if (p <= 0) return@forEachIndexed
            val sweep = 360f * p / total
            slicePaint.color = bandColor(BAND_ORDER[i])
            canvas.drawArc(oval, start, sweep, true, slicePaint)
            canvas.drawArc(oval, start, sweep, true, edgePaint)
            start += sweep
        }
        // Labels after every slice, so no later slice's edge is drawn over one.
        start = -90f
        val baselineShift = (labelPaint.descent() + labelPaint.ascent()) / 2f
        percents.forEach { p ->
            val sweep = 360f * p / total
            if (p >= MIN_LABELLED_PERCENT) {
                val mid = Math.toRadians((start + sweep / 2f).toDouble())
                val x = cx + r * LABEL_RADIUS * cos(mid).toFloat()
                val y = cy + r * LABEL_RADIUS * sin(mid).toFloat() - baselineShift
                canvas.drawText("$p%", x, y, labelPaint)
            }
            start += sweep
        }
    }

    companion object {
        private const val MAX_SIZE_DP = 150
        // Below this a slice is too thin for its label; the table has it.
        private const val MIN_LABELLED_PERCENT = 6
        private const val LABEL_RADIUS = 0.64f
    }
}
