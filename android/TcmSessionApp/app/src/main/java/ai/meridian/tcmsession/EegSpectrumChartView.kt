package ai.meridian.tcmsession

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.log10

/**
 * One session-half's power spectrum with specparam's fit drawn over it — the
 * "look at the picture" counterpart to the exponent/r² numbers above it on the
 * EEG analysis card. Custom-drawn, same no-dependency approach as
 * EegMetricChartView. Three lines, all straight from the server's stored arrays
 * (see EegSpectrum), none recomputed here:
 *
 *  - Recorded (solid): the spectrum the headband measured.
 *  - Model fit (thin): the aperiodic background plus the fitted peaks. r² is how
 *    closely this tracks the solid line, so a poor fit shows up as the two
 *    coming apart — the low-fit badge becomes something you can check by eye.
 *  - Aperiodic (dashed): the background alone. Both axes are log-scaled, so it
 *    is a straight line whose steepness is the aperiodic exponent, and a bump
 *    the model counts as a peak shows as the solid line rising above it.
 *    (specparam decides what counts — not every bump is one; see
 *    SessionDetailActivity.spectrumLegendView.)
 *
 * The power axis carries no numbers on purpose: in log10(V²/Hz) they read as
 * about -12, which tells the practitioner nothing, and the shape is the point.
 * Both halves of a session are drawn on one SHARED power range (the caller
 * passes it — see [powerRangeOf]) so they can be compared by eye; autoscaling
 * each half on its own would make a half with less power look the same as one
 * with more.
 *
 * Not interactive — it's a picture to read, not a series to scrub.
 */
class EegSpectrumChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val recordedColor = ContextCompat.getColor(context, R.color.text_primary)
    private val modelColor = ContextCompat.getColor(context, R.color.accent)
    private val aperiodicColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val axisTextColor = ContextCompat.getColor(context, R.color.text_muted)

    private val leftPad = 18f * density // room for the rotated power-axis label
    private val rightPad = 8f * density
    private val topPad = 26f * density // legend
    private val bottomPad = 24f * density // frequency ticks

    private var spectrum: EegSpectrum? = null
    private var yMin = 0.0
    private var yMax = 1.0

    private val recordedPaint = linePaint(recordedColor, 2f)
    private val modelPaint = linePaint(modelColor, 1.25f)
    private val aperiodicPaint = linePaint(aperiodicColor, 1.5f).apply {
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        // Square dash ends: round caps grow every dash by half the stroke
        // width at each end, shrinking the 4dp gaps the model line has to
        // show through (it's drawn underneath — see onDraw).
        strokeCap = Paint.Cap.BUTT
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(axisTextColor, 50)
        strokeWidth = 1f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = axisTextColor; strokeWidth = 1.5f }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = axisTextColor
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10.5f * density
        textAlign = Paint.Align.LEFT
    }

    private fun linePaint(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    /** [powerRange] is in the same log10 units as [spectrum] and should be the
     * range across BOTH halves of the session — see the class docstring. */
    fun setData(spectrum: EegSpectrum, powerRange: ClosedFloatingPointRange<Double>) {
        this.spectrum = spectrum
        yMin = powerRange.start
        yMax = powerRange.endInclusive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = spectrum ?: return
        val freqs = s.freqsHz
        // specparam's grid starts at the bandpass floor (1Hz), so a non-positive
        // first frequency means malformed data, not a real spectrum to draw.
        if (freqs.size < 2 || freqs.first() <= 0.0 || yMax <= yMin) return

        val rect = RectF(leftPad, topPad, width - rightPad, height - bottomPad)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val logFMin = log10(freqs.first())
        val logFSpan = log10(freqs.last()) - logFMin
        fun xFor(f: Double) = rect.left + ((log10(f) - logFMin) / logFSpan).toFloat() * rect.width()
        fun yFor(v: Double) = rect.bottom - ((v - yMin) / (yMax - yMin)).toFloat() * rect.height()

        // Frequency grid at round numbers — on a log axis, evenly spaced Hz
        // ticks would bunch up at the high end and say nothing at the low one.
        FREQ_TICKS_HZ.filter { it >= freqs.first() && it <= freqs.last() }.forEachIndexed { i, f ->
            val x = xFor(f)
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            val label = if (f == FREQ_TICKS_HZ.last()) "${f.toInt()} Hz" else "${f.toInt()}"
            val half = tickLabelPaint.measureText(label) / 2f
            canvas.drawText(label, x.coerceIn(half, width - half), rect.bottom + 14f * density, tickLabelPaint)
        }
        canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, axisPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, axisPaint)

        // Power-axis label, rotated to run up the left edge.
        canvas.save()
        canvas.rotate(-90f, 11f * density, rect.centerY())
        canvas.drawText("power (log scale)", 11f * density, rect.centerY(), tickLabelPaint)
        canvas.restore()

        fun pathOf(values: List<Double>): Path = Path().apply {
            for (i in 0 until minOf(freqs.size, values.size)) {
                val x = xFor(freqs[i])
                val y = yFor(values[i])
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        canvas.save()
        canvas.clipRect(rect)
        // Wherever the model found no peak, the full model and the aperiodic
        // line are the SAME curve, so whichever is drawn second hides the
        // other. Dashed goes on top because its gaps let the model line show
        // through, keeping both visible. (With the model drawn last, the dashed line vanished everywhere below 20Hz in
        // a half whose only peaks were beta and gamma.) Recorded goes first so
        // both fits read against it.
        canvas.drawPath(pathOf(s.logPower), recordedPaint)
        canvas.drawPath(pathOf(s.logModelFit), modelPaint)
        canvas.drawPath(pathOf(s.logAperiodicFit), aperiodicPaint)
        canvas.restore()

        // Legend: a short sample of each line's own stroke, dash included.
        var legendX = rect.left
        val legendBaseline = 14f * density
        val swatchY = legendBaseline - 3.5f * density
        val swatchLen = 16f * density
        // Plain names, same as spectrumLegendView's explanation of them.
        listOf("Recording" to recordedPaint, "Model" to modelPaint, "Background" to aperiodicPaint)
            .forEach { (label, paint) ->
                canvas.drawLine(legendX, swatchY, legendX + swatchLen, swatchY, paint)
                canvas.drawText(label, legendX + swatchLen + 4f * density, legendBaseline, legendTextPaint)
                legendX += swatchLen + 4f * density + legendTextPaint.measureText(label) + 12f * density
            }
    }

    companion object {
        private val FREQ_TICKS_HZ = listOf(2.0, 5.0, 10.0, 20.0, 40.0)

        /** One power range covering every line of every given spectrum, padded
         * slightly so nothing sits on the plot edge. Null when there's nothing
         * to range over. */
        fun powerRangeOf(spectra: List<EegSpectrum>): ClosedFloatingPointRange<Double>? {
            val values = spectra.flatMap { it.logPower + it.logModelFit + it.logAperiodicFit }
            if (values.isEmpty()) return null
            val lo = values.min()
            val hi = values.max()
            val pad = ((hi - lo).takeIf { it > 1e-9 } ?: 1.0) * 0.05
            return (lo - pad)..(hi + pad)
        }
    }
}
