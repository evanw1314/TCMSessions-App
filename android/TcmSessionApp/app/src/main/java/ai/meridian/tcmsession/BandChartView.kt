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
 * Custom-drawn (no charting dependency, matching this project's minimal
 * dependency set) band-power-vs-time line chart with acupuncture/session
 * markers overlaid as vertical dashed lines, so a practitioner can see a
 * band spike/dip line up against a specific marker.
 *
 * NO_SIGNAL_VALUE samples (see Models.kt) are dropped before plotting —
 * lines connect the surrounding valid points rather than dipping to the
 * sentinel or breaking into disconnected micro-segments per missed tick.
 *
 * Tap-to-inspect (Desmos-style): call [setSelectedBand] with a band name to
 * make that band's line tappable — tapping anywhere horizontally snaps to
 * the nearest recorded point on that line and shows a "mm:ss  value" tooltip.
 * [setSelectedBand]\(null\) ("None") turns this off.
 */
class BandChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Series(val band: String, val color: Int, val points: List<Pair<Int, Double>>)
    private data class TouchIndicator(val band: String, val offsetSeconds: Int, val value: Double)

    private val density = resources.displayMetrics.density

    // Resolved from the active theme (Theme.MaterialComponents.DayNight) rather than
    // hardcoded — fixed dark-gray text/lines read fine in light mode but are nearly
    // invisible against a dark-mode background, so these need to flip with the theme.
    private val primaryTextColor = MaterialColors.getColor(context, android.R.attr.textColorPrimary, Color.BLACK)
    private val secondaryTextColor = MaterialColors.getColor(context, android.R.attr.textColorSecondary, Color.GRAY)
    // Deliberately Material's colorSurface (a "card" floating above the page), not
    // android:colorBackground (the page itself) — the tap-to-inspect tooltip should
    // read as a small card over the chart, matching the wireframe's surface/bg split.
    private val surfaceColor = MaterialColors.getColor(context, MaterialR.attr.colorSurface, Color.WHITE)
    private val gridColor = ColorUtils.setAlphaComponent(secondaryTextColor, 60)
    private val markerLineColor = ColorUtils.setAlphaComponent(primaryTextColor, 160)

    private val leftPad = 44f * density
    private val rightPad = 8f * density
    private val topPad = 34f * density
    private val bottomPad = 24f * density

    private var series: List<Series> = emptyList()
    private var markerPoints: List<SessionMarker> = emptyList()
    private var maxOffsetSeconds: Int = 1
    private var yMin: Double = 0.0
    private var yMax: Double = 1.0

    private var selectedBand: String? = null
    private var touchIndicator: TouchIndicator? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = 1f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        strokeWidth = 1.5f
    }
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
    private val markerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = markerLineColor
        strokeWidth = 1.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryTextColor
        textSize = 9f * density
        textAlign = Paint.Align.LEFT
    }
    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val indicatorGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val indicatorDotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val indicatorDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = surfaceColor
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

    fun setData(samples: List<SessionSample>, markers: List<SessionMarker>) {
        markerPoints = markers.sortedBy { it.offsetSeconds }
        touchIndicator = null

        val byBand = samples.groupBy { it.band }
        series = BAND_ORDER.mapNotNull { band ->
            val points = byBand[band]
                ?.filter { it.value != NO_SIGNAL_VALUE }
                ?.sortedBy { it.offsetSeconds }
                ?.map { it.offsetSeconds to it.value }
                ?: emptyList()
            if (points.isEmpty()) null else Series(band, bandColor(band), points)
        }

        maxOffsetSeconds = max(1, samples.maxOfOrNull { it.offsetSeconds } ?: 1)

        val validValues = samples.filter { it.value != NO_SIGNAL_VALUE }.map { it.value }
        if (validValues.isEmpty()) {
            yMin = 0.0
            yMax = 1.0
        } else {
            val dataMin = validValues.min()
            val dataMax = validValues.max()
            val span = (dataMax - dataMin).let { if (it > 1e-6) it else 1.0 }
            val pad = span * 0.1
            yMin = (dataMin - pad).coerceAtLeast(0.0)
            yMax = dataMax + pad
        }

        invalidate()
    }

    /** [band] is one of alpha|beta|delta|theta|gamma to make that line tappable, or null ("None") to disable tapping. */
    fun setSelectedBand(band: String?) {
        selectedBand = band
        touchIndicator = null
        invalidate()
    }

    private fun plotRect(): RectF = RectF(leftPad, topPad, width - rightPad, height - bottomPad)

    private fun xFor(offsetSeconds: Int, rect: RectF): Float =
        rect.left + (offsetSeconds.toFloat() / maxOffsetSeconds.toFloat()) * rect.width().coerceAtLeast(1f)

    private fun yFor(value: Double, rect: RectF): Float =
        rect.bottom - ((value - yMin) / (yMax - yMin)).toFloat() * rect.height().coerceAtLeast(1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val band = selectedBand ?: return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the gesture from the enclosing ScrollView for its duration —
                // otherwise a mostly-vertical drag gets intercepted as a page scroll
                // partway through and the indicator stops following the finger.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_MOVE -> Unit
            else -> return super.onTouchEvent(event)
        }

        val s = series.find { it.band == band } ?: return true
        if (s.points.isEmpty()) return true

        val rect = plotRect()
        if (rect.width() <= 0f) return true
        val touchFraction = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val touchOffsetSeconds = touchFraction * maxOffsetSeconds
        val nearest = s.points.minByOrNull { abs(it.first - touchOffsetSeconds) } ?: return true

        touchIndicator = TouchIndicator(band, nearest.first, nearest.second)
        invalidate()
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
            canvas.drawText("No signal data recorded", w / 2f, h / 2f, noDataPaint)
            return
        }

        val rect = plotRect()

        // Horizontal gridlines + Y axis labels.
        val yTicks = 4
        for (i in 0..yTicks) {
            val v = yMin + (yMax - yMin) * i / yTicks
            val y = yFor(v, rect)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            canvas.drawText("%.2f".format(v), rect.left - 6f, y + 3f, yLabelPaint)
        }

        // X axis time labels (mm:ss).
        val xTicks = 4
        for (i in 0..xTicks) {
            val secs = maxOffsetSeconds * i / xTicks
            val x = xFor(secs, rect)
            canvas.drawText("%d:%02d".format(secs / 60, secs % 60), x, rect.bottom + 18f * density, xLabelPaint)
        }

        // Axis lines.
        canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, axisPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, axisPaint)

        // Session/acupuncture markers as vertical dashed lines with a short label.
        markerPoints.forEach { marker ->
            val x = xFor(marker.offsetSeconds, rect)
            canvas.drawLine(x, rect.top, x, rect.bottom, markerLinePaint)
            canvas.drawText(marker.label, x + 3f, rect.top - 4f, markerTextPaint)
        }

        // One polyline per band.
        series.forEach { s ->
            linePaint.color = s.color
            val path = Path()
            s.points.forEachIndexed { idx, (offset, value) ->
                val x = xFor(offset, rect)
                val y = yFor(value, rect)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        // Legend row above the plot area.
        var legendX = rect.left
        val legendY = 14f * density
        val swatch = 9f * density
        series.forEach { s ->
            legendSwatchPaint.color = s.color
            canvas.drawRect(legendX, legendY - swatch, legendX + swatch, legendY, legendSwatchPaint)
            canvas.drawText(s.band, legendX + swatch + 4f, legendY, legendTextPaint)
            legendX += swatch + 4f + legendTextPaint.measureText(s.band) + 12f * density
        }

        // Tap-to-inspect indicator (Desmos-style): drop line + dot on the curve + a
        // "mm:ss  value" tooltip, clamped to stay on-screen.
        touchIndicator?.let { ind ->
            val color = bandColor(ind.band)
            val x = xFor(ind.offsetSeconds, rect)
            val y = yFor(ind.value, rect)

            indicatorGuidePaint.color = color
            canvas.drawLine(x, rect.top, x, rect.bottom, indicatorGuidePaint)

            indicatorDotFillPaint.color = color
            canvas.drawCircle(x, y, 4.5f * density, indicatorDotFillPaint)
            canvas.drawCircle(x, y, 4.5f * density, indicatorDotRingPaint)

            val label = "%d:%02d   %.3f".format(ind.offsetSeconds / 60, ind.offsetSeconds % 60, ind.value)
            val boxPaddingH = 8f * density
            val boxPaddingV = 5f * density
            val boxWidth = tooltipTextPaint.measureText(label) + boxPaddingH * 2
            val boxHeight = tooltipTextPaint.textSize + boxPaddingV * 2
            var boxLeft = (x - boxWidth / 2f).coerceIn(0f, w - boxWidth)
            var boxTop = y - 4.5f * density - 8f * density - boxHeight
            if (boxTop < 0f) boxTop = y + 4.5f * density + 8f * density
            val boxRight = boxLeft + boxWidth
            val boxBottom = boxTop + boxHeight

            val corner = 6f * density
            tooltipBorderPaint.color = color
            canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, corner, corner, tooltipBgPaint)
            canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, corner, corner, tooltipBorderPaint)
            val textBaselineY = boxTop + boxHeight / 2f - (tooltipTextPaint.descent() + tooltipTextPaint.ascent()) / 2f
            canvas.drawText(label, (boxLeft + boxRight) / 2f, textBaselineY, tooltipTextPaint)
        }
    }
}
