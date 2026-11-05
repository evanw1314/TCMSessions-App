package ai.meridian.tcmsession

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SessionDetailActivity's network state, kept across rotation. The EEG
 * analysis is a multi-second (sometimes minute-long) server run; in the
 * Activity's own scope a rotation cancelled the wait and dropped the result.
 * Short actions (save note, delete) stay in the Activity.
 */
class SessionDetailViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface SessionLoad {
        data object Loading : SessionLoad
        data class Loaded(val session: SessionDetail) : SessionLoad
        data class Failed(val message: String?) : SessionLoad
    }

    /** [result] is the latest analysis to show, from a stored reread or a
     * run. [error] is set when the latest run failed; [result] then still
     * holds the earlier one, which stays on screen. */
    data class Analysis(
        val result: EegAnalysisResult? = null,
        val inFlight: Boolean = false,
        val error: String? = null,
    )

    data class Summary(val text: String? = null, val inFlight: Boolean = false)

    private val _session = MutableStateFlow<SessionLoad>(SessionLoad.Loading)
    val session: StateFlow<SessionLoad> = _session.asStateFlow()

    private val _analysis = MutableStateFlow(Analysis())
    val analysis: StateFlow<Analysis> = _analysis.asStateFlow()

    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    private val api get() = ApiClient(getApplication())
    private var sessionId = -1
    // The stored reread can arrive after a quick tap. It never replaces a
    // run's result, but if the run fails it is still the breakdown to show.
    private var storedResult: EegAnalysisResult? = null
    private var runSucceeded = false

    /** Idempotent: a recreated Activity calls this again and gets the state
     * already loaded or loading. */
    fun start(sessionId: Int, loadStoredAnalysis: Boolean) {
        if (this.sessionId == sessionId) return
        this.sessionId = sessionId
        viewModelScope.launch {
            _session.value = try {
                SessionLoad.Loaded(api.getSession(sessionId))
            } catch (e: Exception) {
                SessionLoad.Failed(e.message)
            }
        }
        if (loadStoredAnalysis) {
            viewModelScope.launch {
                try {
                    val stored = api.getEegMetrics(sessionId)
                    storedResult = stored
                    if (!runSucceeded) _analysis.update { if (it.inFlight) it else it.copy(result = stored) }
                } catch (e: Exception) {
                    // Silent: the card keeps its "tap Analyze" default.
                }
            }
        }
    }

    /** POST /eeg/analyze. Ignored while a run is already going: two
     * concurrent runs would race to replace the same half rows. */
    fun analyze() {
        if (_analysis.value.inFlight) return
        _analysis.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            try {
                val result = api.analyzeEeg(sessionId)
                runSucceeded = true
                _analysis.update { it.copy(result = result, inFlight = false) }
            } catch (e: Exception) {
                _analysis.update {
                    it.copy(result = it.result ?: storedResult, inFlight = false, error = e.message ?: "unknown error")
                }
            }
        }
    }

    /** On-demand only — never called automatically (see ApiClient.summarizeSession). */
    fun summarize() {
        if (_summary.value.inFlight) return
        _summary.value = Summary(text = "Generating...", inFlight = true)
        viewModelScope.launch {
            _summary.value = try {
                Summary(text = api.summarizeSession(sessionId))
            } catch (e: Exception) {
                Summary(text = e.message ?: "Failed to generate summary")
            }
        }
    }
}
