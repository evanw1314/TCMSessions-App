package ai.meridian.tcmsession

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max

/**
 * Custom-drawn (matching BandChartView's no-dependency approach) line chart for a
 * scalar aperiodic-corrected EEG metric — aperiodic exponent, or eye-movement
 * (EOG SEM) rate — plotted across a small number of ordered steps: session-halves
 * within one session, or analyzed sessions across a patient's history. Unlike BandChartView's
 * once-a-second band-power samples, this data is genuinely sparse — one value per
 * step, not a dense curve — so points are drawn as visible dots joined by straight
 * segments rather than a smooth line.
 *
 * Up to two series ("earlier half" / "later half", i.e. period 0/1) are drawn in
 * caller-supplied colors so the same period reads the same color everywhere this
 * view is used. A null value at a step (e.g. an eog rate with too little clean
 * signal to score — see EegHalfMetric.eogSemRatePerMin's docstring) is dropped
 * from that series rather than plotted as zero or breaking the line into pieces;
 * the line simply skips over the gap, matching BandChartView's NO_SIGNAL_VALUE
 * handling for the same reason.
 *
 * A FLAGGED point (see [Point.flagged]) is drawn hollow, and the legend gains a
 * "Low model fit" entry explaining it. Hollow rather than dropped: the number
 * exists and the session card shows it, so leaving it off here would be its own
 * misreport — the point is to mark it as one to read with caution.
 *
 * Deliberately has no tap-to-inspect band SELECTOR like BandChartView (there's
 * nothing to select between — both series are always shown); tapping anywhere
 * just snaps to the nearest plotted point across both series.
 */
class EegMetricChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** One value at one ordered step (stepIndex into the labels passed to [setData]).
     * [seriesIndex] is 0 ("earlier half") or 1 ("later half"). [value] null means
     * "not enough signal to score this step" and is dropped, not plotted as zero.
     * [flagged] marks a value from a poorly-fit model (see isLowFit) — drawn
     * hollow, not dropped. */
    data class Point(val stepIndex: Int, val seriesIndex: Int, val value: Double?, val flagged: Boolean = false)

    private data class PlotPoint(val stepIndex: Int, val value: Double, val flagged: Boolean)
    private data class Series(val label: String, val color: Int, val points: List<PlotPoint>)
    private data class TouchIndicator(val color: Int, val point: PlotPoint)

    private val density = resources.displayMetrics.density

    // Resolved from the active theme, same reasoning as BandChartView.
    private val primaryTextColor = MaterialColors.getColor(context, android.R.attr.textColorPrimary, Color.BLACK)
    private val secondaryTextColor = MaterialColors.getColor(context, android.R.attr.textColorSecondary, Color.GRAY)
    private val surfaceColor = MaterialColors.getColor(context, MaterialR.attr.colorSurface, Color.WHITE)
    private val gridColor = ColorUtils.setAlphaComponent(secondaryTextColor, 60)

    private val leftPad = 44f * density
    private val rightPad = 12f * density
    private val topPad = 30f * density
    private val bottomPad = 30f * density
    private val dotRadius = 3.5f * density
    // A touch larger than a filled dot: a hollow ring reads as smaller at the
    // same radius, and it has to stay findable at a glance.
    private val flaggedRadius = 4.5f * density

    private var stepLabels: List<String> = emptyList()
    private var series: List<Series> = emptyList()
    private var anyFlagged = false
    private var yMin = 0.0
    private var yMax = 1.0
    private var touchIndicator: TouchIndicator? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = surfaceColor
    }
    // Flagged points: surface-filled (covering the line through them) with a
    // ring in the series color, so the period still reads by color.
    private val hollowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = surfaceColor
    }
    private val flaggedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gridColor; strokeWidth = 1f }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = secondaryTextColor; strokeWidth = 1.5f }
    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textSize = 10f * density
        textAlign = Paint.Align.RIGHT
    }
    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryTextColor
        textSize = 11f * density
        textAlign = Paint.Align.LEFT
    }
    private val legendSwatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val indicatorGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(surfaceColor, 240)
    }
    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryTextColor
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    /** [stepLabels] is one short label per ordered step (e.g. "Jul 27" per session,
     * or "Baseline"/"Treatment" per half within one session); [points] carries the
     * values, and [seriesLabels]/[seriesColors] name/color each seriesIndex found
     * in [points] (missing entries fall back to a generic label / the primary text
     * color, but every caller in this app supplies both — see EEG_TREND_PERIOD_*
     * in Models.kt). */
    fun setData(
        stepLabels: List<String>,
        points: List<Point>,
        seriesLabels: Map<Int, String>,
        seriesColors: Map<Int, Int>,
    ) {
        this.stepLabels = stepLabels
        touchIndicator = null

        series = points.groupBy { it.seriesIndex }
            .toSortedMap()
            .mapNotNull { (idx, pts) ->
                val valid = pts.filter { it.value != null }
                    .sortedBy { it.stepIndex }
                    .map { PlotPoint(it.stepIndex, it.value!!, it.flagged) }
                if (valid.isEmpty()) null
                else Series(seriesLabels[idx] ?: "Series ${idx + 1}", seriesColors[idx] ?: primaryTextColor, valid)
            }
        anyFlagged = series.any { s -> s.points.any { it.flagged } }

        val allValues = points.mapNotNull { it.value }
        if (allValues.isEmpty()) {
            yMin = 0.0
            yMax = 1.0
        } else {
            val dataMin = allValues.min()
            val dataMax = allValues.max()
            val span = (dataMax - dataMin).let { if (it > 1e-6) it else max(1.0, abs(dataMax) * 0.2 + 0.1) }
            val pad = span * 0.15
            yMin = dataMin - pad
            yMax = dataMax + pad
        }

        invalidate()
    }

    private fun plotRect(): RectF = RectF(leftPad, topPad, width - rightPad, height - bottomPad)

    private fun xFor(stepIndex: Int, rect: RectF): Float {
        val lastStep = max(1, stepLabels.size - 1)
        if (stepLabels.size <= 1) return rect.left + rect.width() / 2f
        return rect.left + (stepIndex.toFloat() / lastStep.toFloat()) * rect.width()
    }

    private fun yFor(value: Double, rect: RectF): Float =
        rect.bottom - ((value - yMin) / (yMax - yMin)).toFloat() * rect.height().coerceAtLeast(1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_MOVE -> Unit
            else -> return super.onTouchEvent(event)
        }
        if (series.isEmpty()) return true

        val rect = plotRect()
        if (rect.width() <= 0f) return true

        // Nearest point by screen-space distance across every series, not just the
        // nearest step index — with two series drawn at the same x, this is what
        // makes "tap near the lower dot" reliably pick the lower series.
        var best: TouchIndicator? = null
        var bestDist = Float.MAX_VALUE
        series.forEach { s ->
            s.points.forEach { pt ->
                val x = xFor(pt.stepIndex, rect)
                val y = yFor(pt.value, rect)
                val dist = abs(x - event.x) + abs(y - event.y) * 0.25f // weight x more; steps matter most
                if (dist < bestDist) {
                    bestDist = dist
                    best = TouchIndicator(s.color, pt)
                }
            }
        }
        best?.let {
            touchIndicator = it
            invalidate()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (series.isEmpty()) {
            canvas.drawText("No analyzed data yet", w / 2f, h / 2f, noDataPaint)
            return
        }

        val rect = plotRect()

        val yTicks = 4
        for (i in 0..yTicks) {
            val v = yMin + (yMax - yMin) * i / yTicks
            val y = yFor(v, rect)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            canvas.drawText("%.2f".format(v), rect.left - 6f, y + 3f, yLabelPaint)
        }

        // X axis labels: at most 5 evenly-spaced steps get a label, however many
        // steps there are — avoids overlapping text once a patient has many
        // analyzed sessions, while a short list (2-5 steps) just labels every one.
        val labelCount = stepLabels.size.coerceAtMost(5).coerceAtLeast(1)
        val lastIdx = stepLabels.size - 1
        for (i in 0 until labelCount) {
            val stepIdx = if (labelCount == 1) 0 else (lastIdx * i / (labelCount - 1))
            val label = stepLabels.getOrNull(stepIdx) ?: continue
            val x = xFor(stepIdx, rect)
            // Center-aligned text at the first/last step overhangs past the view's
            // own edge and gets clipped there (a real bug seen on-device — the last
            // label rendered as an illegible sliver) — anchor those two to LEFT/RIGHT
            // instead so the full label stays inside the view's bounds.
            xLabelPaint.textAlign = when {
                stepLabels.size <= 1 -> Paint.Align.CENTER
                stepIdx == 0 -> Paint.Align.LEFT
                stepIdx == lastIdx -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(label, x, rect.bottom + 18f * density, xLabelPaint)
        }
        xLabelPaint.textAlign = Paint.Align.CENTER

        canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, axisPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, axisPaint)

        series.forEach { s ->
            linePaint.color = s.color
            dotPaint.color = s.color
            if (s.points.size > 1) {
                val path = Path()
                s.points.forEachIndexed { idx, pt ->
                    val x = xFor(pt.stepIndex, rect)
                    val y = yFor(pt.value, rect)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, linePaint)
            }
            s.points.forEach { pt ->
                val x = xFor(pt.stepIndex, rect)
                val y = yFor(pt.value, rect)
                if (pt.flagged) {
                    drawHollowDot(canvas, x, y, s.color)
                } else {
                    canvas.drawCircle(x, y, dotRadius, dotPaint)
                    canvas.drawCircle(x, y, dotRadius, dotRingPaint)
                }
            }
        }

        var legendX = rect.left
        val legendY = 14f * density
        val swatch = 9f * density
        series.forEach { s ->
            legendSwatchPaint.color = s.color
            canvas.drawRect(legendX, legendY - swatch, legendX + swatch, legendY, legendSwatchPaint)
            canvas.drawText(s.label, legendX + swatch + 4f, legendY, legendTextPaint)
            legendX += swatch + 4f + legendTextPaint.measureText(s.label) + 14f * density
        }
        if (anyFlagged) {
            // Neutral ring: the entry explains the SHAPE, which means the same
            // thing in either series' color.
            drawHollowDot(canvas, legendX + flaggedRadius, legendY - swatch / 2f, secondaryTextColor)
            canvas.drawText(LOW_FIT_LABEL, legendX + flaggedRadius * 2 + 4f, legendY, legendTextPaint)
        }

        touchIndicator?.let { ind ->
            val x = xFor(ind.point.stepIndex, rect)
            val y = yFor(ind.point.value, rect)

            indicatorGuidePaint.color = ind.color
            canvas.drawLine(x, rect.top, x, rect.bottom, indicatorGuidePaint)

            val stepLabel = stepLabels.getOrNull(ind.point.stepIndex) ?: ""
            val label = "$stepLabel   %.2f".format(ind.point.value) +
                if (ind.point.flagged) " · ${LOW_FIT_LABEL.lowercase()}" else ""
            val boxPaddingH = 8f * density
            val boxPaddingV = 5f * density
            val boxWidth = tooltipTextPaint.measureText(label) + boxPaddingH * 2
            val boxHeight = tooltipTextPaint.textSize + boxPaddingV * 2
            val boxLeft = (x - boxWidth / 2f).coerceIn(0f, w - boxWidth)
            val r = if (ind.point.flagged) flaggedRadius else dotRadius
            var boxTop = y - r - 8f * density - boxHeight
            if (boxTop < 0f) boxTop = y + r + 8f * density
            val boxRight = boxLeft + boxWidth
            val boxBottom = boxTop + boxHeight

            val corner = 6f * density
            tooltipBorderPaint.color = ind.color
            canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, corner, corner, tooltipBgPaint)
            canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, corner, corner, tooltipBorderPaint)
            val textBaselineY = boxTop + boxHeight / 2f - (tooltipTextPaint.descent() + tooltipTextPaint.ascent()) / 2f
            canvas.drawText(label, (boxLeft + boxRight) / 2f, textBaselineY, tooltipTextPaint)
        }
    }

    private fun drawHollowDot(canvas: Canvas, x: Float, y: Float, ringColor: Int) {
        flaggedRingPaint.color = ringColor
        canvas.drawCircle(x, y, flaggedRadius, hollowFillPaint)
        canvas.drawCircle(x, y, flaggedRadius, flaggedRingPaint)
    }

    private companion object {
        // Short: it shares one legend row with both series' labels.
        const val LOW_FIT_LABEL = "Poor model match"
    }
}
