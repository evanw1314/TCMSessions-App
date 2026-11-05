package ai.meridian.tcmsession

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import ai.meridian.tcmsession.databinding.ActivitySessionDetailBinding

class SessionDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySessionDetailBinding

    // Index-aligned with bandSelectSpinner's adapter; null = "None" (tap-to-inspect off).
    private val bandSelectLabels = listOf("None", "Delta", "Theta", "Alpha", "Beta", "Gamma")
    private val bandSelectKeys = listOf(null, "delta", "theta", "alpha", "beta", "gamma")

    private var sessionId: Int = -1
    private var patientName: String = "Patient"
    private var loadedSession: SessionDetail? = null
    // Set when PatientDetailActivity opened this in self-view (a patient
    // viewing their own session) — hides every write action (edit note,
    // delete, AI summary; that endpoint is practitioner-only server-side
    // regardless) and makes the note field plain read-only text.
    private var isSelfView: Boolean = false
    // Loads, the EEG analysis and the AI summary live here so a rotation
    // doesn't cancel them; see SessionDetailViewModel.
    private val model: SessionDetailViewModel by viewModels()
    // What this Activity instance has drawn, so a re-emitted state (every
    // return to the foreground) doesn't redraw over the user's typing.
    private var renderedSession: SessionDetail? = null
    private var renderedAnalysis: EegAnalysisResult? = null
    // Whether the note field has been filled from the server. Saved across
    // recreation: once filled, the EditText restores what the user was
    // typing, so the server's copy mustn't overwrite it; until then (a
    // rotation before the load finished) it still needs filling, or Save
    // would send an empty note.
    private var notePopulated = false
    // Last rendered analysis timestamp, kept so a FAILED re-analysis can say
    // so without erasing the fact that earlier results are still on screen.
    private var lastAnalyzedAtText: String? = null
    // Threshold for flagging a poorly-fit half, DEFINED SERVER-SIDE
    // (eeg.interpretation.LOW_FIT_R_SQUARED) and shipped in every analysis response so
    // the card flags exactly the halves ai_summary hedges in prose; two
    // separate thresholds would put contradictory claims on one screen.
    //
    // Null, with NO hardcoded fallback, when the server didn't send one: a
    // server that omits it hedges its summary at its own value, so guessing
    // here would recreate that disagreement. A client shouldn't assert a
    // number it can't source; it just doesn't draw the flag.
    private var lowFitRSquared: Double? = null
    // True when the rendered analysis predates the electrode check (and the
    // deflection count that shipped with it), so a missing deflection rate
    // reads as "not recorded" rather than "not measured".
    private var loadedElectrodeFindingsMissing = false
    // True when every electrode was flagged: empty peaks then mean "not
    // evaluable" (see peaksTable).
    private var loadedAllElectrodesFlagged = false

    private val saveCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val session = loadedSession
        if (uri != null && session != null) {
            try {
                ExportUtil.writeSessionCsvTo(this, uri, session, patientName)
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notePopulated = savedInstanceState?.getBoolean(STATE_NOTE_POPULATED) ?: false
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bandSelectSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, bandSelectLabels).apply {
                // Only the open dropdown list needs more room to tap — the closed
                // spinner button keeps the compact system default.
                setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
        binding.bandSelectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.bandChartView.setSelectedBand(bandSelectKeys[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sessionId = intent.getIntExtra("session_id", -1)
        patientName = intent.getStringExtra("patient_name") ?: "Patient"
        isSelfView = intent.getBooleanExtra("self_view", false)
        if (isSelfView) {
            binding.noteEditText.isEnabled = false
            binding.saveNoteButton.visibility = View.GONE
            binding.aiOverviewCard.visibility = View.GONE // summarize is practitioner-only server-side
            binding.eegAnalysisCard.visibility = View.GONE // analyze/eeg-metrics are practitioner-only server-side
            binding.dangerZoneCard.visibility = View.GONE // delete
        }
        // The stored analysis is a separate, silent load: a failure there
        // leaves the card at its "tap Analyze" default rather than taking
        // down the screen. Skipped for self-view (card hidden above; the
        // endpoint is practitioner-only anyway).
        model.start(sessionId, loadStoredAnalysis = !isSelfView)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.session.collect(::onSessionState) }
                launch { model.analysis.collect(::onAnalysisState) }
                launch { model.summary.collect(::onSummaryState) }
            }
        }

        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.saveNoteButton.setOnClickListener { saveNote() }
        binding.exportButton.setOnClickListener { exportCsv() }
        binding.deleteSessionButton.setOnClickListener { confirmDelete() }
        binding.summarizeSessionButton.setOnClickListener { model.summarize() }
        binding.analyzeEegButton.setOnClickListener { model.analyze() }
    }

    private fun onSessionState(state: SessionDetailViewModel.SessionLoad) {
        when (state) {
            SessionDetailViewModel.SessionLoad.Loading -> Unit
            is SessionDetailViewModel.SessionLoad.Failed -> {
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                finish()
            }
            is SessionDetailViewModel.SessionLoad.Loaded -> {
                if (state.session === renderedSession) return
                renderedSession = state.session
                loadedSession = state.session
                render(state.session)
                // Content starts hidden behind loadingIndicator (see the layout's
                // default visibilities) — reveal it now that there's something to show.
                binding.loadingIndicator.visibility = View.GONE
                binding.contentScrollView.visibility = View.VISIBLE
            }
        }
    }

    /** The analysis card's state. Analyze IS the compute step server-side
     * (mne filtering + specparam fit), on demand only, for a first analysis
     * and a re-analysis alike.
     *
     * A failed run's message is shown as-is: a 404 usually means no raw EEG
     * was ever uploaded for this session (best-effort upload — see
     * SessionRecorder.uploadRawEegBestEffort) or the blob is gone, which the
     * server words differently. On a re-analysis the earlier breakdown stays
     * rendered below, so the status keeps the earlier timestamp rather than
     * reading as though the error described those numbers. */
    private fun onAnalysisState(state: SessionDetailViewModel.Analysis) {
        state.result?.let { if (it !== renderedAnalysis) { renderedAnalysis = it; renderEegAnalysis(it) } }
        // Off while a run is going: a second concurrent run would race the
        // first to replace the same half rows.
        binding.analyzeEegButton.isEnabled = !state.inFlight
        when {
            state.inFlight -> binding.eegAnalysisStatusText.text = "Analyzing — this takes a few seconds..."
            state.error != null -> {
                val previous = lastAnalyzedAtText
                binding.eegAnalysisStatusText.text =
                    if (previous != null) "$previous — re-analysis failed: ${state.error}" else state.error
            }
        }
    }

    private fun onSummaryState(state: SessionDetailViewModel.Summary) {
        state.text?.let { binding.sessionSummaryText.text = it }
        binding.summarizeSessionButton.isEnabled = !state.inFlight
    }

    /** Renders either state of the EEG analysis card: not-yet-analyzed (button
     * reads "Analyze EEG" from the layout, alongside that layout's default
     * explainer text) or analyzed (button stays live, relabeled "Re-analyze",
     * and a per-half breakdown replaces the status text).
     *
     * The button stays after an analysis because the server keeps the raw
     * EEG and recomputes on every call: re-analyzing is how an already-analyzed
     * session picks up pipeline improvements and new output fields. */
    private fun renderEegAnalysis(result: EegAnalysisResult) {
        if (result.analyzedAt == null || result.halves.isEmpty()) return

        // Deliberately does NOT touch isEnabled — onAnalysisState owns that.
        lowFitRSquared = result.lowFitRSquared
        loadedElectrodeFindingsMissing = result.electrodeFindings == null
        loadedAllElectrodesFlagged = result.allElectrodesFlagged == true
        // The pies' eye-activity warning comes from this same analysis; a
        // no-op until the session itself has loaded, whose render() draws it.
        eyeActivityDiffers = result.eyeActivityDiffers == true
        renderBandShareCard()
        binding.analyzeEegButton.text = "Re-analyze"
        lastAnalyzedAtText = "Analyzed " + formatTimestamp(result.analyzedAt)
        binding.eegAnalysisStatusText.text = lastAnalyzedAtText

        val container = binding.eegAnalysisResultsContainer
        container.removeAllViews()
        container.addView(sessionQualityView(result))
        electrodeWarnings(result).forEach { container.addView(warningView(it.toText())) }
        eyeActivityWarning(result)?.let { container.addView(warningView(it.toText())) }
        // One range for both halves' charts, so they read against each other.
        val powerRange = EegSpectrumChartView.powerRangeOf(result.halves.mapNotNull { it.spectrum })
        if (powerRange != null) container.addView(spectrumLegendView())
        result.halves.forEachIndexed { index, half ->
            if (index > 0) {
                container.addView(View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { topMargin = dp(8); bottomMargin = dp(8) }
                    setBackgroundColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.border))
                })
            }
            container.addView(eegHalfView(half, powerRange))
        }
        container.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Recording-wide context for the numbers below it: how the halves were
     * split, what sample rate the headband actually achieved, how much of
     * the signal is interpolation rather than measurement, and whether any
     * channels were dropped.
     *
     * The split line comes first because it is the one most able to
     * mislead: "First/Second half" and "Baseline/Treatment" render almost
     * identically but mean very different things, and only the latter is a
     * before/after comparison. */
    private fun sessionQualityView(result: EegAnalysisResult): View {
        val lines = mutableListOf<String>()

        // The half labels are the only carrier of which split ran - see
        // eeg.analysis.analyze_session_eeg's baseline-cutoff fallback.
        if (result.halves.any { it.half == "baseline" }) {
            lines += "Split at the marked baseline end, so the halves are before and after that point."
        } else if (loadedSession?.baselineDurationSeconds != null) {
            // The pipeline also falls back to the midpoint when a baseline
            // WAS marked but lands outside the uploaded raw EEG (a late
            // headband connect, or a truncated best-effort upload). Saying
            // "no baseline was marked" here would tell the practitioner they
            // didn't do something they did.
            lines += "A baseline was marked, but it falls outside the uploaded raw EEG and couldn't " +
                "be used. The halves are split at the recording's midpoint instead, so they are not " +
                "a before/after comparison."
        } else {
            lines += "The halves are split at the recording's midpoint rather than at a marked " +
                "baseline, so they are two arbitrary stretches of one session, not a before/after " +
                "comparison."
        }

        result.observedSampleRateHz?.let {
            lines += "Recorded at %.1f readings per second (Hz).".format(it)
        }
        result.interpolatedSampleFraction?.let {
            // Fraction of (sample, channel) READINGS, not of samples - with
            // four channels, one dead electrode alone is 25%. Also avoids
            // the word "interpolated": a channel with too few good samples
            // is left as a gap rather than interpolated at all, which is
            // exactly the case that dominates this number.
            //
            // Worded as "empty", not "bad contact", and the limit spelled out
            // right beside it: an electrode lifted off the skin keeps sending
            // numbers, so it never shows up here. Session 24 held one side off
            // the forehead for two minutes and this read 0.0%, which on its
            // own reads as "contact was fine".
            // Deliberately NOT "a low-fit warning will show it": in session
            // 24 the eyes-open baseline was already low-fit, so the flag was
            // on in both halves and didn't single out the lift.
            lines += ("%.1f%% of sensor readings arrived empty. That only counts gaps the " +
                "headband reports: a sensor lifted off the skin still sends readings, so it isn't " +
                "counted here. A lifted sensor may show up as the model not matching, or as many " +
                "windows dropped for movement, but open eyes or muscle tension cause those too.").format(it * 100)
        }
        val excluded = result.excludedChannelIndices
        val findings = result.electrodeFindings
        when {
            excluded == null -> lines += "Which sensors were used: not recorded for this analysis."
            // Analyzed before the electrode check existed: the only thing
            // that excluded channels then was the eye correction, now off, so
            // a re-analysis will change these numbers.
            findings == null && excluded.isNotEmpty() -> lines += "The forehead sensors were left out by the " +
                "old eye-movement correction, which has since been switched off. Tap Re-analyze to update."
            findings == null -> lines += "All four sensors used. The loose-sensor check didn't exist when this " +
                "was analyzed - tap Re-analyze to run it."
            // Exclusions and warnings get their own warning block below; this
            // line only speaks when there's nothing to warn about. Says what
            // the check can't do, the same way the readings line above does:
            // session 24's lifted electrode only showed as a warning.
            findings.isEmpty() -> lines += "All four sensors used. The loose-sensor check found no signs of a " +
                "loose sensor, though it can't catch every one."
        }

        return TextView(this).apply {
            text = lines.joinToString("\n")
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
            setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_muted))
        }
    }

    /** The electrode check's result as warnings, empty when there's nothing
     * to warn about (sessionQualityView covers the quiet cases). Wording in
     * EegWarnings, shared with the trend tab. Three cases, which can combine:
     * - some electrodes excluded: which, why, and what the numbers now come
     *   from;
     * - every electrode flagged, so none could be excluded: the numbers are
     *   unreliable;
     * - an electrode much noisier than its partner: named, with the other
     *   causes that produce the same sign, and NOT excluded. */
    private fun electrodeWarnings(result: EegAnalysisResult): List<PlainWarning> {
        val findings = result.electrodeFindings ?: return emptyList()
        val excluded = result.excludedChannelIndices.orEmpty()
        val warnings = mutableListOf<PlainWarning>()
        if (result.allElectrodesFlagged == true) {
            warnings += EegWarnings.allSensorsFlagged()
        } else if (excluded.isNotEmpty()) {
            val kept = ELECTRODE_NAMES.filterIndexed { i, _ -> i !in excluded }
            warnings += EegWarnings.sensorsLeftOut(findings.filter { it.sign != "much_noisier_than_partner" }, kept)
        }
        findings.filter { it.sign == "much_noisier_than_partner" && !it.excluded }.forEach {
            warnings += EegWarnings.noisierThanPartner(it)
        }
        return warnings
    }

    /** Shown when the server's eye-activity rule (eeg.interpretation.eye_activity_differs,
     * the same one ai_summary uses) trips. Null (not checked) says nothing
     * rather than implying the halves matched. */
    private fun eyeActivityWarning(result: EegAnalysisResult): PlainWarning? =
        if (result.eyeActivityDiffers == true) EegWarnings.eyeActivity() else null

    private fun warningView(message: CharSequence): View =
        TextView(this).apply {
            text = message
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.warning_bg))
            setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.warning))
        }

    /** One half's window tally. Deliberately three numbers rather than one
     * "rejected" total: empty readings means the headband sent NaN (not the
     * same as "off the skin" - a lifted electrode still sends numbers;
     * see the readings line in sessionQualityView), movement means large
     * amplitude swings (moving, clenching, or a loose electrode being
     * handled), and "kept with fewer
     * channels" isn't a rejection at all - those windows ARE in the
     * spectrum, just measured by less of the headband. Summing them would
     * hide which of those actually happened, and they call for different
     * responses from whoever ran the session. */
    private fun windowTallyText(half: EegHalfMetric): String {
        val badContact = half.epochsRejectedBadContact
            ?: return "Windows: ${half.epochCount} kept - drop detail not recorded for this analysis"
        return "Windows: ${half.epochCount} kept, $badContact dropped (empty readings), " +
            "${half.epochsRejectedAmplitude ?: 0} dropped (movement), " +
            "${half.epochsPartialContact ?: 0} kept with fewer sensors"
    }

    /** One half's block: exponent/offset/fit-quality/eye-movement text, then a
     * small peaks table — one row per real oscillation found, or a single
     * "none detected" line ("none" is itself an informative result — see
     * docs/eeg-aperiodic-correction.md — not an omission to explain). A
     * table, not a text line, so a half with several peaks reads as rows
     * instead of a run-on sentence. */
    private fun eegHalfView(half: EegHalfMetric, powerRange: ClosedFloatingPointRange<Double>?): View {
        // Counted from the signal, independent of patient_dozed/
        // alertness_rating self-report — see eogSemRatePerMin's docstring.
        // Not labelled an arousal marker: deliberate eye movement produces
        // these just as readily as drowsiness (sessions 26/31). Null means
        // "not enough clean signal," not a rate of zero, so it's worded as its
        // own sentence rather than a blank/zero value.
        val eogText = if (half.eogSemRatePerMin != null) {
            "Slow eye movements: %.1f per minute".format(half.eogSemRatePerMin)
        } else {
            "Slow eye movements: not counted (not enough clean signal)"
        }
        // Not called a blink count alone: on this headband blinks, eye
        // movements and a hand on the headband all produce these (see
        // eeg.artifacts.DEFLECTION_*). An analysis from before the field
        // existed is told apart by its missing electrode check, which
        // arrived in the same change.
        val deflectionText = when {
            half.deflectionRatePerMin != null ->
                "Blinks and eye or head movements: %.1f per minute".format(half.deflectionRatePerMin)
            loadedElectrodeFindingsMissing -> "Blinks and eye or head movements: not recorded for this analysis"
            loadedAllElectrodesFlagged -> "Blinks and eye or head movements: not counted (every sensor flagged)"
            else -> "Blinks and eye or head movements: not counted (too little usable signal)"
        }
        val halfLabel = half.half.replaceFirstChar { it.uppercase() }
        // The precise terms, kept for a specialist rather than deleted - see
        // EegWording.kt. Muted and smaller so the plain lines above lead.
        val detailsText = "Details: aperiodic exponent %.2f, offset %.2f, fit r² %.3f, %d epochs"
            .format(half.aperiodicExponent, half.aperiodicOffset, half.rSquared, half.epochCount)

        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(TextView(this@SessionDetailActivity).apply {
                text = "$halfLabel half\n" +
                    "${STEEPNESS.replaceFirstChar { it.uppercase() }}: %.2f\n".format(half.aperiodicExponent) +
                    eogText + "\n" + deflectionText + "\n" + windowTallyText(half)
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_secondary))
            })
            addView(TextView(this@SessionDetailActivity).apply {
                text = detailsText
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
                setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_muted))
            })
            addView(spectrumView(half, powerRange))
            if (isLowFit(half.rSquared, lowFitRSquared)) addView(lowFitWarningView(half.rSquared))
            addView(peaksTable(half.peaks, peaksEvaluable = !loadedAllElectrodesFlagged))
        }
    }

    /** How to read the spectrum charts, said once above both halves rather
     * than repeated under each. Describes the MODEL and the recording, never
     * the patient — the same line the rest of this card and ai_summary's
     * prompts hold. Says the peaks are what the table measures rather than
     * that every bump is one: specparam decides what counts as a peak, and
     * a bump it rejected is noise by its own test. */
    private fun spectrumLegendView(): View =
        TextView(this).apply {
            text = "How to read each half's chart: the solid line is the recording, from slow " +
                "activity on the left to fast on the right. The dashed line is the steady background, " +
                "and how steeply it falls is the $STEEPNESS. Some bumps of the solid line above the " +
                "dashed one are rhythms; the table lists the ones the app counts as real, the strongest " +
                "in each band. An alpha rhythm (8-13 Hz) usually appears with the eyes closed and fades " +
                "when they open; how strong it is here isn't a reliable measure of anything. The thin " +
                "line is the app's model; where it comes apart from the solid line, the model doesn't " +
                "match the recording well.\n\n" +
                "The $STEEPNESS is most reliable compared with the other half of the same session; it " +
                "varies more from one session to the next.\n\n" +
                "Details: solid = power spectrum, dashed = aperiodic fit (its slope is the exponent), " +
                "thin = full specparam model (r² measures its match)."
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
            setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_muted))
        }

    /** This half's spectrum with the fit drawn over it, or one line saying it
     * isn't stored. Null means the analysis predates the field, not that
     * there is nothing to show — same "not recorded, never a false empty"
     * wording as the window tally, naming the fix since the button is right
     * there on the card. */
    private fun spectrumView(half: EegHalfMetric, powerRange: ClosedFloatingPointRange<Double>?): View {
        val spectrum = half.spectrum
        if (spectrum == null || powerRange == null) {
            return TextView(this).apply {
                text = "Chart: not recorded for this analysis - tap Re-analyze to add it."
                textSize = 12f
                setPadding(0, dp(4), 0, dp(4))
                setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_muted))
            }
        }
        return EegSpectrumChartView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(170)
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
            setData(spectrum, powerRange)
        }
    }

    /** Shown under a half whose specparam fit is poor. Names the steepness
     * AND the rhythms because r² measures the whole decomposition, not just
     * the aperiodic part, so presenting the peaks as unaffected would be an
     * overclaim. Wording in EegWarnings.lowFit. */
    private fun lowFitWarningView(rSquared: Double): View =
        TextView(this).apply {
            text = EegWarnings.lowFit(rSquared, lowFitRSquared, withRhythms = true).toText()
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.warning_bg))
            setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.warning))
        }

    /** Header + one row per detected peak (band, center frequency, power,
     * bandwidth); an empty list renders a single "none detected" line instead
     * of a header over nothing. Band names color-matched to bandColor, same
     * "a color means the same band everywhere" convention as
     * renderBandStatsTable/BandChartView. */
    private fun peaksTable(peaks: List<OscillationPeak>, peaksEvaluable: Boolean = true): View {
        if (peaks.isEmpty()) {
            return TextView(this).apply {
                // With every electrode flagged the pipeline reports no peaks
                // at all (the interference lines crowd real ones out), so an
                // empty list there is "not evaluable", not a finding.
                text = if (peaksEvaluable) "Rhythms: none found"
                else "Rhythms: can't be checked - every sensor shows signs of a loose fit"
                textSize = 12.5f
                setPadding(0, dp(4), 0, 0)
                setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_secondary))
            }
        }

        val headerColor = MaterialColors.getColor(this, android.R.attr.textColorSecondary, Color.GRAY)
        val cellPadding = dp(6)
        fun cell(text: String, textColor: Int = headerColor, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                textSize = 11.5f
                setTextColor(textColor)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }

        val table = android.widget.TableLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            setStretchAllColumns(true)
        }
        table.addView(TableRow(this).apply {
            addView(cell("Rhythm", headerColor, bold = true))
            addView(cell("Hz", headerColor, bold = true))
            addView(cell("Strength", headerColor, bold = true))
            addView(cell("Width", headerColor, bold = true))
        })
        peaks.forEach { peak ->
            table.addView(TableRow(this).apply {
                addView(cell(peak.band.replaceFirstChar { it.uppercase() }, bandColor(peak.band), bold = true))
                addView(cell("%.1f".format(peak.centerFrequency)))
                addView(cell("%.2f".format(peak.power)))
                addView(cell("%.1f".format(peak.bandwidth)))
            })
        }
        return table
    }

    // From the EEG analysis once it loads (practitioner only): the pies warn
    // when blinking or movement differs between the analysis's halves.
    private var eyeActivityDiffers = false

    /** The before/after band-share card (BandShares): two one-minute pies,
     * the change between them, and what they can't tell you. Shown to the
     * practitioner and the patient alike, in wording for each
     * (BandShareWords). Called from render() and again when the EEG analysis
     * loads, since either can land first. */
    private fun renderBandShareCard() {
        val session = loadedSession ?: return
        val container = binding.bandShareContainer
        container.removeAllViews()
        val result = BandShares.compute(session.samples, session.baselineDurationSeconds, session.treatmentEndSeconds)
        val words = BandShareWords(forPatient = isSelfView)
        binding.bandShareTitle.text = words.heading(result)
        if (result == null) {
            container.addView(explainerView("No band readings were recorded for this session."))
            return
        }
        if (!result.overlapping) {
            container.addView(pieRow(result, words))
            if (result.comparable) container.addView(bandShareTable(result, words))
        }
        words.warnings(result, eyeActivityDiffers).forEach { container.addView(warningView(it.toText())) }
        container.addView(explainerView(words.explainer(result).toText()))
    }

    /** The two pies side by side, each titled and captioned, or a "not enough
     * clean signal" slot where one can't be drawn. One spoken description
     * covers the row, since TalkBack can't read a drawing. */
    private fun pieRow(result: BandShares.Result, words: BandShareWords): View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            isFocusable = true
        }
        val spoken = mutableListOf<String>()
        listOf(true, false).forEach { isBefore ->
            val window = if (isBefore) result.before else result.after
            val percents = window.percents?.let { BandShares.wholePercents(it) }
            val title = words.title(isBefore, result)
            val caption = words.caption(isBefore, result)
            row.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { if (isBefore) marginEnd = dp(6) else marginStart = dp(6) }
                if (percents != null) {
                    addView(PieChartView(this@SessionDetailActivity).apply { setPercents(percents) })
                } else {
                    addView(TextView(this@SessionDetailActivity).apply {
                        text = "Not enough clean signal"
                        textSize = 12f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_muted))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(120),
                        )
                    })
                }
                addView(TextView(this@SessionDetailActivity).apply {
                    text = title
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
                addView(TextView(this@SessionDetailActivity).apply {
                    text = caption
                    textSize = 11.5f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_secondary))
                })
            })
            spoken += "$title, ${caption.replace("\n", ", ")}: " + (
                percents?.let { p -> BAND_ORDER.indices.joinToString(", ") { "${BAND_ORDER[it]} ${p[it]} percent" } }
                    ?: "not enough clean signal"
                )
        }
        row.contentDescription = spoken.joinToString(". ")
        return row
    }

    /** Band | before | after | change, in the same whole percents the pies
     * print, with the change worked out from those numbers so it adds up on
     * screen. Band names color-matched, like the other tables here. Neutral
     * on purpose: no color or bold marks a change as big, since the text
     * below says what a big change can and can't mean. */
    private fun bandShareTable(result: BandShares.Result, words: BandShareWords): View {
        val before = BandShares.wholePercents(result.before.percents!!)
        val after = BandShares.wholePercents(result.after.percents!!)
        val changes = result.pointChanges()!!
        val headerColor = MaterialColors.getColor(this, android.R.attr.textColorSecondary, Color.GRAY)
        val primaryColor = MaterialColors.getColor(this, android.R.attr.textColorPrimary, Color.BLACK)
        val cellPadding = dp(6)
        fun cell(text: String, textColor: Int = primaryColor, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                textSize = 12.5f
                setTextColor(textColor)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }

        val table = android.widget.TableLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            setStretchAllColumns(true)
        }
        table.addView(TableRow(this).apply {
            addView(cell("Band", headerColor, bold = true))
            addView(cell(words.title(true, result), headerColor, bold = true))
            addView(cell(words.title(false, result), headerColor, bold = true))
            addView(cell("Change (points)", headerColor, bold = true))
        })
        BAND_ORDER.forEachIndexed { i, band ->
            table.addView(TableRow(this).apply {
                addView(cell(band.replaceFirstChar { it.uppercase() }, bandColor(band), bold = true))
                addView(cell("${before[i]}%"))
                addView(cell("${after[i]}%"))
                addView(cell(if (changes[i] > 0) "+${changes[i]}" else "${changes[i]}"))
            })
        }
        return table
    }

    /** The pies' how-to-read text. text_secondary rather than muted: for a
     * patient this is the main text on the card, not a footnote. */
    private fun explainerView(text: CharSequence): View =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.text_secondary))
        }

    private fun exportCsv() {
        val session = loadedSession ?: return
        AlertDialog.Builder(this)
            .setItems(arrayOf("Save to device", "Share...")) { _, which ->
                if (which == 0) {
                    saveCsvLauncher.launch(ExportUtil.sessionCsvFilename(session, patientName))
                } else {
                    ExportUtil.shareSessionCsv(this, session, patientName)
                }
            }
            .show()
    }

    /** The safeguard against an accidental tap: the practitioner must type the
     * exact word DELETE before the confirm button is even enabled. The delete
     * itself is a soft delete server-side (sessions.py) — recoverable from the
     * database directly — but the app has no undo, so this is the only guard
     * against a slip of the thumb. */
    private fun confirmDelete() {
        val session = loadedSession ?: return
        val input = android.widget.EditText(this).apply {
            hint = "Type DELETE to confirm"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete this session?")
            .setMessage(
                "This removes the ${session.modality} session on " +
                    "${formatSessionRange(session.startedAt, session.endedAt)} for " +
                    "$patientName from the app. This cannot be undone from within the app.\n\n" +
                    "Type DELETE to confirm."
            )
            .setView(input)
            .setPositiveButton("Delete", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirmButton.isEnabled = false
            confirmButton.setTextColor(ContextCompat.getColor(this, R.color.critical))
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    confirmButton.isEnabled = s?.toString() == "DELETE"
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            confirmButton.setOnClickListener {
                dialog.dismiss()
                performDelete()
            }
        }
        dialog.show()
    }

    private fun performDelete() {
        binding.deleteSessionButton.isEnabled = false
        lifecycleScope.launch {
            try {
                ApiClient(this@SessionDetailActivity).deleteSession(sessionId)
                Toast.makeText(this@SessionDetailActivity, "Session deleted", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@SessionDetailActivity, "Failed to delete: ${e.message}", Toast.LENGTH_LONG).show()
                binding.deleteSessionButton.isEnabled = true
            }
        }
    }

    private fun saveNote() {
        val note = binding.noteEditText.text.toString().trim().ifBlank { null }
        binding.saveNoteButton.isEnabled = false
        lifecycleScope.launch {
            try {
                ApiClient(this@SessionDetailActivity).updateSessionNote(sessionId, note)
                Toast.makeText(this@SessionDetailActivity, "Note saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SessionDetailActivity, "Failed to save note: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.saveNoteButton.isEnabled = true
            }
        }
    }

    private fun render(session: SessionDetail) {
        binding.modalityText.text = session.modality
        binding.dateText.text = formatSessionRange(session.startedAt, session.endedAt) + arousalSuffix(session)

        binding.bandChartView.setData(session.samples, session.markers)
        binding.bandChartView.contentDescription = chartAccessibilityDescription(session)
        renderBandStatsTable(session)
        renderBandShareCard()
        renderMarkerChips(session.markers)

        if (!notePopulated) {
            binding.noteEditText.setText(session.practitionerNote ?: "")
            notePopulated = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_NOTE_POPULATED, notePopulated)
    }

    private companion object {
        const val STATE_NOTE_POPULATED = "note_populated"
    }

    /** Appended to the date/duration line — the one place in this screen a
     * reviewer would look to decide whether to trust the band-power chart
     * below as more than "the patient relaxed or dozed off." */
    private fun arousalSuffix(session: SessionDetail): String {
        val parts = mutableListOf<String>()
        if (session.patientDozed == true) parts.add("dozed off")
        session.alertnessRating?.let { parts.add("alertness $it/7") }
        session.baselineDurationSeconds?.let { parts.add("baseline %d:%02d".format(it / 60, it % 60)) }
        session.treatmentEndSeconds?.let { parts.add("treatment done %d:%02d".format(it / 60, it % 60)) }
        return if (parts.isEmpty()) "" else "  ·  " + parts.joinToString(", ")
    }

    /** One chip per marker ("mm:ss  label"), matching the wireframe's marker-chip
     * component; falls back to a plain "(none)" text when there are no markers. */
    private fun renderMarkerChips(markers: List<SessionMarker>) {
        binding.markersChipGroup.removeAllViews()
        binding.markersChipGroup.visibility = if (markers.isEmpty()) View.GONE else View.VISIBLE
        binding.markersEmptyText.visibility = if (markers.isEmpty()) View.VISIBLE else View.GONE

        val chipBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_3))
        val chipTextColor = MaterialColors.getColor(this, android.R.attr.textColorSecondary, Color.GRAY)

        markers.forEach { marker ->
            val chip = Chip(this).apply {
                text = "%02d:%02d  %s".format(marker.offsetSeconds / 60, marker.offsetSeconds % 60, marker.label)
                isClickable = false
                isCheckable = false
                chipBackgroundColor = chipBg
                setTextColor(chipTextColor)
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
            }
            binding.markersChipGroup.addView(chip)
        }
    }

    /** BandChartView is a custom Canvas view with no text a screen reader can pick
     * up on its own — this gives TalkBack a spoken equivalent (band averages +
     * marker count) so the chart isn't silent for a sighted-only interaction. The
     * stats table below has the precise numbers (avg/min/max per band); this is
     * deliberately a shorter spoken summary, not a duplicate of that table. */
    private fun chartAccessibilityDescription(session: SessionDetail): String {
        val byBand = session.samples.groupBy { it.band }
        val bandSummaries = BAND_ORDER.mapNotNull { band ->
            val values = byBand[band]?.map { it.value }?.filter { it != NO_SIGNAL_VALUE } ?: emptyList()
            if (values.isEmpty()) null else "$band averaging %.2f".format(values.average())
        }
        val bandsText = if (bandSummaries.isEmpty()) "no signal recorded" else bandSummaries.joinToString(", ")
        val markerCount = session.markers.size
        val markerText = if (markerCount == 0) "no markers" else
            "$markerCount marker${if (markerCount == 1) "" else "s"}"
        return "Band power chart over the session: $bandsText. $markerText. " +
            "Exact averages, minimums, and maximums are in the table below."
    }

    /** Builds the band-power stats grid: header row + one row per band (BAND_ORDER),
     * band name color-matched to its line in BandChartView so the two views read as
     * one system. Bands with no signal at all get a single merged message cell instead
     * of three "—" cells. */
    private fun renderBandStatsTable(session: SessionDetail) {
        val table = binding.bandStatsTable
        table.removeAllViews()

        val cellPadding = (8 * resources.displayMetrics.density).toInt()
        val headerColor = MaterialColors.getColor(this, android.R.attr.textColorSecondary, Color.GRAY)
        val primaryColor = MaterialColors.getColor(this, android.R.attr.textColorPrimary, Color.BLACK)

        fun cell(text: String, textColor: Int = primaryColor, bold: Boolean = false, span: Int = 1): TextView =
            TextView(this).apply {
                this.text = text
                setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
                textSize = 13f
                setTextColor(textColor)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                // No-arg ctor leaves column = -1 ("auto-place next"); an explicit
                // column index here would pin every cell to that same column instead.
                layoutParams = TableRow.LayoutParams().apply { this.span = span }
            }

        table.addView(TableRow(this).apply {
            addView(cell("Band", headerColor, bold = true))
            addView(cell("Avg", headerColor, bold = true))
            addView(cell("Min", headerColor, bold = true))
            addView(cell("Max", headerColor, bold = true))
        })

        val byBand = session.samples.groupBy { it.band }
        BAND_ORDER.forEach { band ->
            val allValues = byBand[band]?.map { it.value } ?: emptyList()
            val values = allValues.filter { it != NO_SIGNAL_VALUE }
            val excluded = allValues.size - values.size
            val bandLabel = band.replaceFirstChar { it.uppercase() } +
                if (excluded > 0 && values.isNotEmpty()) "\n($excluded excluded)" else ""

            table.addView(TableRow(this).apply {
                addView(cell(bandLabel, bandColor(band), bold = true))
                when {
                    allValues.isEmpty() -> addView(cell("no data", headerColor, span = 3))
                    values.isEmpty() -> addView(cell("no signal for entire session", headerColor, span = 3))
                    else -> {
                        addView(cell("%.2f".format(values.average())))
                        addView(cell("%.2f".format(values.min())))
                        addView(cell("%.2f".format(values.max())))
                    }
                }
            })
        }
    }
}
