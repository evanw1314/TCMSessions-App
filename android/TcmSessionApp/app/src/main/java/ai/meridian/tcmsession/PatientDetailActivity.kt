package ai.meridian.tcmsession

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import ai.meridian.tcmsession.databinding.ActivityPatientDetailBinding

/** A tabbed patient profile — Overview / TCM sessions / Notes / EEG trend —
 * opened from PatientListActivity. Headband assignment isn't offered here
 * yet; the server endpoints exist but no screen calls them.
 *
 * Also doubles as the patient's own self-view: the exact same screen a
 * patient lands on after signing in as themselves (LoginActivity.goToRoleHome),
 * with isSelfView swapping every data source for its self-scoped "/me" equivalent
 * and hiding every WRITE action a patient shouldn't have —
 * removing themselves, starting a session, adding/deleting a note, or
 * triggering an AI summary (that endpoint stays practitioner-only). Reading
 * (sessions, notes, export) stays available either way. */
class PatientDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPatientDetailBinding

    // True when a patient is viewing their own record rather than a
    // practitioner viewing one of theirs — see the class doc above.
    private var isSelfView: Boolean = false

    private var patientId: Int = -1
    private var patientName: String = "Patient"
    // Null for a name-only patient with no account assigned yet — see
    // renderEmail()/showAssignEmailDialog(). Always non-null in self-view
    // (a logged-in patient always has an email — see models.User's docstring).
    private var patientEmail: String? = null
    private var headbandDeviceId: String? = null
    private var lastSessionAt: String? = null

    private val sessionAdapter = SessionAdapter { row ->
        when (row) {
            is SessionRow.Existing -> openSessionDetail(row.summary)
            is SessionRow.Ongoing -> openNewSession() // same patient + phase != IDLE => SessionRecorder reattaches
        }
    }
    // isSelfView is read at click time (not captured at construction, when
    // it's always still false) — a patient can never delete a note, so
    // tapping one in self-view is a no-op instead of confirmDeleteNote.
    private val noteAdapter = NoteAdapter { note -> if (!isSelfView) confirmDeleteNote(note) }

    // Details fetched by exportAll(), consumed once saveZipLauncher's picker returns.
    private var pendingZipDetails: List<SessionDetail>? = null

    // Gate the loading spinners to the very FIRST fetch of each tab's data —
    // a background refresh (onResume with rows already on screen, or a repeat
    // tab switch onto a genuinely-empty Notes tab, see showPane()) shouldn't
    // yank the list away and replace it with a spinner every time.
    private var sessionsLoadedOnce = false
    private var notesLoadedOnce = false
    private var eegTrendLoadedOnce = false

    // Metric currently plotted on eegTrendChartView — toggled by
    // eegTrendMetricSpinner, re-rendered from the already-fetched points
    // (see renderEegTrendChart) rather than refetching per switch.
    private enum class EegTrendMetric { EXPONENT, EOG_RATE }
    private var selectedEegTrendMetric = EegTrendMetric.EXPONENT
    private var loadedEegTrendPoints: List<EegTrendPoint> = emptyList()

    private val saveZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val details = pendingZipDetails
        pendingZipDetails = null
        if (uri != null && details != null) {
            try {
                ExportUtil.writeAllSessionsZipTo(this, uri, details, patientName)
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isSelfView = intent.getBooleanExtra("self_view", false)
        patientId = intent.getIntExtra("patient_id", -1)
        // In self-view none of these come from an Intent extra (there's no
        // practitioner screen handing them off) — loadMyProfile() fills them
        // in once GET /me/profile returns; AppConfig's cached name is just
        // what's shown until then.
        patientName = intent.getStringExtra("patient_name") ?: AppConfig.userName(this).ifBlank { "Patient" }
        patientEmail = intent.getStringExtra("patient_email")
        headbandDeviceId = intent.getStringExtra("headband_device_id")
        lastSessionAt = intent.getStringExtra("last_session_at")

        binding.patientNameText.text = patientName
        renderEmail()
        if (isSelfView) {
            binding.backButton.visibility = View.GONE
            binding.signOutButton.visibility = View.VISIBLE
            binding.signOutButton.setOnClickListener { signOut() }
            binding.aiOverviewCard.visibility = View.GONE
            binding.removePatientCard.visibility = View.GONE
            binding.newSessionButton.visibility = View.GONE
            binding.noteComposerContainer.visibility = View.GONE
            loadMyProfile()
        } else {
            binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        binding.detailTabLayout.addTab(binding.detailTabLayout.newTab().setText("Overview"))
        binding.detailTabLayout.addTab(binding.detailTabLayout.newTab().setText("TCM sessions"))
        binding.detailTabLayout.addTab(binding.detailTabLayout.newTab().setText("Notes"))
        // EEG trend is practitioner-only server-side (GET /patients/{id}/eeg-trend
        // — see routers/eeg.py), same gating as the single-session EEG
        // analysis card — never added in self-view, so position 3 never exists
        // (and is never selectable) there.
        if (!isSelfView) {
            binding.detailTabLayout.addTab(binding.detailTabLayout.newTab().setText("EEG Trend"))
            binding.eegTrendMetricSpinner.adapter =
                android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    listOf("Background steepness", "Slow eye movements"),
                ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
            binding.eegTrendMetricSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedEegTrendMetric = if (position == 0) EegTrendMetric.EXPONENT else EegTrendMetric.EOG_RATE
                    renderEegTrendChart(loadedEegTrendPoints)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        binding.detailTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPane(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.sessionRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sessionRecyclerView.adapter = sessionAdapter
        binding.newSessionButton.setOnClickListener { openNewSession() }
        binding.exportAllButton.setOnClickListener { exportAll() }

        binding.noteRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.noteRecyclerView.adapter = noteAdapter
        binding.addNoteButton.setOnClickListener { addNote() }

        binding.summarizeButton.setOnClickListener { summarizePatient() }
        binding.removePatientButton.setOnClickListener { confirmRemovePatient() }

        binding.tileLastSessionValue.text = lastSessionAt?.let { formatTimestamp(it) } ?: "None yet"
        loadSessions()
    }

    override fun onResume() {
        super.onResume()
        // Sessions may have changed (a new one recorded, one deleted) since we
        // were last visible.
        loadSessions()
        if (binding.detailTabLayout.selectedTabPosition == 2) loadNotes()
    }

    // Self-view only (signOutButton is gone otherwise) — mirrors
    // PatientListActivity.signOut(): clears the cached login, not the saved
    // server address, so switching networks doesn't require digging into
    // Android's app-storage settings to force a fresh login screen.
    private fun signOut() {
        AppConfig.clearSession(this)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    /** Self-view only — fills in the header/tiles this screen would
     * otherwise get from PatientListActivity's Intent extras (see onCreate).
     * GET /me/profile is unscoped to a single practitioner (see its
     * docstring server-side), so lastSessionAt/headbandDeviceId here reflect
     * this patient's whole record, not one practitioner's slice of it. */
    private fun loadMyProfile() {
        lifecycleScope.launch {
            try {
                val profile = ApiClient(this@PatientDetailActivity).myProfile()
                patientName = profile.name
                patientEmail = profile.email
                headbandDeviceId = profile.headbandDeviceId
                lastSessionAt = profile.lastSessionAt
                binding.patientNameText.text = patientName
                renderEmail()
                binding.tileLastSessionValue.text = lastSessionAt?.let { formatTimestamp(it) } ?: "None yet"
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPane(position: Int) {
        binding.overviewPane.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.tcmPane.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.notesPane.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.eegTrendPane.visibility = if (position == 3) View.VISIBLE else View.GONE
        if (position == 2 && noteAdapter.itemCount == 0) loadNotes()
        if (position == 3 && !eegTrendLoadedOnce) loadEegTrend()
    }

    // ---- Email assignment ----

    /** Shows the assigned email, or (practitioner view only) an "Assign
     * email" prompt when this patient doesn't have one yet (added by name
     * only — see PatientListActivity.showAddPatientDialog). An
     * already-assigned email isn't tappable: the server only allows setting
     * it once (see ApiClient.assignPatientEmail).
     *
     * Self-view never reaches the null branch in practice — a patient is
     * always logged in WITH an email (see models.User's docstring), and
     * loadMyProfile() only ever sets patientEmail from that real value — but
     * isSelfView is still checked explicitly here rather than relying on
     * that, so a patient viewing their own record can never be offered an
     * action that reassigns whose account their data goes to. */
    private fun renderEmail() {
        val email = patientEmail
        if (email != null || isSelfView) {
            binding.patientEmailText.text = email ?: ""
            binding.patientEmailText.setTextColor(getColor(R.color.text_secondary))
            binding.patientEmailText.isClickable = false
            binding.patientEmailText.setOnClickListener(null)
        } else {
            binding.patientEmailText.text = "No account yet — tap to assign an email"
            binding.patientEmailText.setTextColor(getColor(R.color.accent))
            binding.patientEmailText.setOnClickListener { showAssignEmailDialog() }
        }
    }

    private fun showAssignEmailDialog() {
        // Practitioner-only action — see renderEmail()'s docstring for why
        // this is guarded here too, not just by never wiring the click that
        // reaches it.
        if (isSelfView) return
        val input = android.widget.EditText(this).apply {
            hint = "patient@example.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Assign email")
            .setMessage("This chooses which account will receive this patient's session data. It can only be set once.")
            .setView(container)
            .setPositiveButton("Assign", null)
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val email = input.text.toString().trim()
                    if (email.isBlank()) {
                        input.error = "Enter an email address"
                        return@setOnClickListener
                    }
                    assignEmail(email, dialog)
                }
            }
    }

    private fun assignEmail(email: String, dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        lifecycleScope.launch {
            try {
                val updated = ApiClient(this@PatientDetailActivity).assignPatientEmail(patientId, email)
                // A different id back means the server merged this name-only
                // patient into an existing account that already owned that
                // email (see assign_patient_email/_merge_name_only_patient
                // server-side) — this screen's sessions/notes now live under
                // THAT id, so switch to it and reload rather than showing an
                // empty patient.
                val merged = updated.id != patientId
                patientId = updated.id
                patientName = updated.name
                patientEmail = updated.email
                binding.patientNameText.text = patientName
                renderEmail()
                dialog.dismiss()
                if (merged) {
                    Toast.makeText(
                        this@PatientDetailActivity,
                        "That email already had an account — merged this patient's sessions and notes into it.",
                        Toast.LENGTH_LONG,
                    ).show()
                    loadSessions()
                    if (binding.detailTabLayout.selectedTabPosition == 2) loadNotes()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, e.message ?: "Failed to assign email", Toast.LENGTH_LONG).show()
            } finally {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }
    }

    // ---- Overview ----

    private fun renderRecentActivity(sessions: List<SessionSummary>) {
        binding.tileSessionCountValue.text = sessions.size.toString()
        binding.recentActivityContainer.removeAllViews()
        if (sessions.isEmpty()) {
            addActivityRow("No sessions recorded yet.")
            return
        }
        // Most recent first: listSessions() is already server-ordered that way.
        // Tapping a row jumps straight into that session instead of making the
        // practitioner switch to the TCM sessions tab and find it again.
        sessions.take(5).forEach { s ->
            addActivityRow("${formatSessionRange(s.startedAt, s.endedAt)} — ${s.modality}") { openSessionDetail(s) }
        }
    }

    /** On-demand only — never called automatically (see ApiClient.summarizePatient).
     * A 503 ("not configured" / safety-classifier decline) or 400 ("nothing to
     * summarize yet") both come back as ApiException.message from the server's
     * detail field, so no special-casing is needed here beyond showing it. */
    private fun summarizePatient() {
        binding.summarizeButton.isEnabled = false
        binding.summaryText.text = "Generating..."
        lifecycleScope.launch {
            try {
                binding.summaryText.text =
                    ApiClient(this@PatientDetailActivity).summarizePatient(patientId)
            } catch (e: Exception) {
                binding.summaryText.text = e.message ?: "Failed to generate summary"
            } finally {
                binding.summarizeButton.isEnabled = true
            }
        }
    }

    /** Single confirm dialog, not the typed-confirmation guard
     * SessionDetailActivity uses for deleting a session — unlike a recorded
     * session, removing a patient here is easily undone (re-add them by
     * name/email, or reassign the device) and destroys no data, so that
     * extra friction isn't earning its keep. Server-side this drops the
     * roster entry and ends any active headband assignment (see
     * ApiClient.deletePatient). */
    private fun confirmRemovePatient() {
        AlertDialog.Builder(this)
            .setTitle("Remove $patientName?")
            .setMessage(
                "This takes $patientName off your patient list and ends any " +
                    "headband assignment you hold for them. Their account, sessions, and " +
                    "notes are kept — you can add them back later."
            )
            .setPositiveButton("Remove") { _, _ -> removePatient() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removePatient() {
        binding.removePatientButton.isEnabled = false
        lifecycleScope.launch {
            try {
                ApiClient(this@PatientDetailActivity).deletePatient(patientId)
                Toast.makeText(this@PatientDetailActivity, "$patientName removed", Toast.LENGTH_SHORT).show()
                // PatientListActivity reloads its roster in onResume(), so just
                // pop back to it rather than trying to patch its in-memory list.
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, "Failed to remove patient: ${e.message}", Toast.LENGTH_LONG).show()
                binding.removePatientButton.isEnabled = true
            }
        }
    }

    /** [onClick], when given, makes the row tappable (e.g. jump to a session) —
     * the placeholder "No sessions recorded yet." row passes none. */
    private fun addActivityRow(text: String, onClick: (() -> Unit)? = null) {
        val row = TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 10, 0, 10)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }
        binding.recentActivityContainer.addView(row)
    }

    // ---- TCM sessions ----

    private fun loadSessions() {
        if (!sessionsLoadedOnce) binding.sessionsLoadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val client = ApiClient(this@PatientDetailActivity)
                val sessions = if (isSelfView) client.mySessions() else client.listSessions(patientId)
                sessionAdapter.submit(buildSessionRows(sessions))
                binding.sessionsEmptyText.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                renderRecentActivity(sessions)
                sessionsLoadedOnce = true
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.sessionsLoadingIndicator.visibility = View.GONE
            }
        }
    }

    /** Prepends a synthetic row for a session SessionRecorder is currently
     * recording/saving/retrying for THIS patient — it doesn't exist server-side
     * yet (buffer-then-upload-at-end, see SessionRecorder's docstring), so it
     * can't come from listSessions(). Only reflects what SessionRecorder knows
     * as of THIS load — see SessionRow.Ongoing's docstring for the staleness
     * this implies. Looked up for this patient specifically, since two
     * patients can have concurrent recorders — this screen only ever shows
     * THEIR ongoing session, never someone else's. */
    private fun buildSessionRows(sessions: List<SessionSummary>): List<SessionRow> {
        val rows = mutableListOf<SessionRow>()
        // A patient can never be the one recording a session, so self-view
        // has no ongoing-session row to synthesize — and patientId is never
        // set there anyway (see onCreate).
        if (!isSelfView) {
            SessionRecorder.activeForPatient(patientId)?.let { active ->
                rows.add(SessionRow.Ongoing(active.phase, active.sessionStartedAtIso))
            }
        }
        sessions.forEach { rows.add(SessionRow.Existing(it)) }
        return rows
    }

    private fun openNewSession() {
        val intent = Intent(this, SessionActivity::class.java)
        intent.putExtra("patient_id", patientId)
        intent.putExtra("patient_name", patientName)
        intent.putExtra("headband_device_id", headbandDeviceId)
        startActivity(intent)
    }

    private fun openSessionDetail(session: SessionSummary) {
        val intent = Intent(this, SessionDetailActivity::class.java)
        intent.putExtra("session_id", session.id)
        intent.putExtra("patient_name", patientName)
        intent.putExtra("self_view", isSelfView)
        startActivity(intent)
    }

    /** Bundles every one of this patient's sessions into one zip (one CSV
     * each), then lets the user save it straight to a device location or hand
     * it to the share sheet. Fetches full detail per session (samples +
     * markers) since the list endpoint only returns summaries. */
    private fun exportAll() {
        binding.exportAllButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val client = ApiClient(this@PatientDetailActivity)
                val summaries = if (isSelfView) client.mySessions() else client.listSessions(patientId)
                if (summaries.isEmpty()) {
                    Toast.makeText(this@PatientDetailActivity, "No sessions to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val details = summaries.map { client.getSession(it.id) }
                AlertDialog.Builder(this@PatientDetailActivity)
                    .setItems(arrayOf("Save to device", "Share...")) { _, which ->
                        if (which == 0) {
                            pendingZipDetails = details
                            saveZipLauncher.launch(ExportUtil.allSessionsZipFilename(patientName))
                        } else {
                            ExportUtil.shareAllSessionsZip(this@PatientDetailActivity, details, patientName)
                        }
                    }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.exportAllButton.isEnabled = true
            }
        }
    }

    // ---- Notes ----

    private fun loadNotes() {
        if (!notesLoadedOnce) binding.notesLoadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val client = ApiClient(this@PatientDetailActivity)
                val notes = if (isSelfView) client.myNotes() else client.listNotes(patientId)
                noteAdapter.submit(notes)
                binding.notesEmptyText.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                notesLoadedOnce = true
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.notesLoadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun addNote() {
        val body = binding.noteInput.text.toString().trim()
        if (body.isBlank()) return
        binding.addNoteButton.isEnabled = false
        lifecycleScope.launch {
            try {
                ApiClient(this@PatientDetailActivity).addNote(patientId, body)
                binding.noteInput.text.clear()
                loadNotes()
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, "Failed to add note: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.addNoteButton.isEnabled = true
            }
        }
    }

    /** Tap a note to delete it — single confirm dialog, no typed-confirmation
     * guard (unlike SessionDetailActivity.confirmDelete): a note is a few
     * lines of text, not a whole recorded session, so re-typing it if this is
     * a mis-tap is a low enough cost that the heavier guard isn't worth the
     * friction. Soft delete server-side either way. */
    private fun confirmDeleteNote(note: PatientNote) {
        AlertDialog.Builder(this)
            .setTitle("Delete this note?")
            .setMessage(note.body)
            .setPositiveButton("Delete") { _, _ -> deleteNote(note) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteNote(note: PatientNote) {
        lifecycleScope.launch {
            try {
                ApiClient(this@PatientDetailActivity).deleteNote(note.id)
                loadNotes()
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, "Failed to delete note: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- EEG trend — practitioner-only, see the tab's own gating in
    // onCreate. Deliberately loaded once per screen visit like Notes (not
    // re-fetched on every onResume): a practitioner opens this tab to review
    // a trend, not to watch it update live. ----

    private fun loadEegTrend() {
        binding.eegTrendLoadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val points = ApiClient(this@PatientDetailActivity).getPatientEegTrend(patientId)
                renderEegTrend(points)
                eegTrendLoadedOnce = true
            } catch (e: Exception) {
                Toast.makeText(this@PatientDetailActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.eegTrendLoadingIndicator.visibility = View.GONE
            }
        }
    }

    /** Renders both halves of the EEG Trend pane: the chart (renderEegTrendChart)
     * and, below it, an exact-numbers list — groups the flat point list by session (server already orders it
     * oldest-first, and within a session by period — see
     * ApiClient.getPatientEegTrend) into one block per session: a date header,
     * then one line per half. */
    private fun renderEegTrend(points: List<EegTrendPoint>) {
        loadedEegTrendPoints = points
        renderEegTrendChart(points)

        binding.eegTrendEmptyText.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        val container = binding.eegTrendContainer
        container.removeAllViews()
        points.groupBy { it.sessionId }.forEach { (_, sessionPoints) ->
            if (container.childCount > 0) {
                container.addView(View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { topMargin = dp(10); bottomMargin = dp(10) }
                    setBackgroundColor(ContextCompat.getColor(this@PatientDetailActivity, R.color.border))
                })
            }
            container.addView(TextView(this).apply {
                text = formatTimestamp(sessionPoints.first().sessionStartedAt)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@PatientDetailActivity, R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            // Session-level facts, the same ones the session card shows, so a
            // session whose halves can't be compared doesn't read as two
            // plain numbers here.
            val first = sessionPoints.first()
            first.excludedChannelNames?.takeIf { it.isNotEmpty() }?.let { names ->
                container.addView(TextView(this).apply {
                    text = "Left out of this analysis: the ${sensorList(names)} - see the session for why."
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                    setTextColor(ContextCompat.getColor(this@PatientDetailActivity, R.color.text_muted))
                })
            }
            // Shorter forms of the session card's warnings (EegWarnings):
            // one list entry per session, and the full card is a tap away.
            if (first.allElectrodesFlagged == true) {
                container.addView(trendWarningView(EegWarnings.allSensorsFlaggedShort().toText()))
            }
            if (first.eyeActivityDiffers == true) {
                container.addView(trendWarningView(EegWarnings.eyeActivityShort().toText()))
            }
            sessionPoints.sortedBy { it.period }.forEach { p ->
                val eogText = if (p.eogSemRatePerMin != null) {
                    "slow eye movements %.1f/min".format(p.eogSemRatePerMin)
                } else {
                    "slow eye movements not counted"
                }
                val deflectionText = p.deflectionRatePerMin?.let { " · blinks and movements %.1f/min".format(it) } ?: ""
                val halfLabel = p.half.replaceFirstChar { it.uppercase() }
                container.addView(TextView(this).apply {
                    text = "$halfLabel — $STEEPNESS %.2f · $eogText$deflectionText".format(p.aperiodicExponent)
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(this@PatientDetailActivity, R.color.text_secondary))
                    setPadding(0, dp(4), 0, 0)
                })
                // The precise terms, kept for a specialist - see EegWording.kt.
                container.addView(TextView(this).apply {
                    text = "Details: aperiodic exponent %.2f, offset %.2f, fit r² %.3f"
                        .format(p.aperiodicExponent, p.aperiodicOffset, p.rSquared)
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@PatientDetailActivity, R.color.text_muted))
                })
                // Same rule and wording as the session card's banner (see
                // isLowFit), so a half flagged there doesn't read as a plain
                // number here. Names only the steepness: this list shows no rhythms.
                if (isLowFit(p.rSquared, p.lowFitRSquared)) {
                    container.addView(trendWarningView(
                        EegWarnings.lowFit(p.rSquared, p.lowFitRSquared, withRhythms = false).toText()
                    ))
                }
            }
        }
    }

    private fun trendWarningView(message: CharSequence): View =
        TextView(this).apply {
            text = message
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(ContextCompat.getColor(this@PatientDetailActivity, R.color.warning_bg))
            setTextColor(ContextCompat.getColor(this@PatientDetailActivity, R.color.warning))
        }

    /** Feeds eegTrendChartView: one step per analyzed session (x-axis, in the
     * server's oldest-first order — session identity, not raw elapsed time,
     * since sessions aren't evenly spaced), one point per period (0/1) per
     * step for whichever metric eegTrendMetricSpinner currently has selected.
     * Chart + spinner hide together with the rest of the pane's content when
     * there's nothing analyzed yet (eegTrendEmptyText covers that case). */
    private fun renderEegTrendChart(points: List<EegTrendPoint>) {
        binding.eegTrendChartControls.visibility = if (points.isEmpty()) View.GONE else View.VISIBLE
        binding.eegTrendChartView.visibility = if (points.isEmpty()) View.GONE else View.VISIBLE
        if (points.isEmpty()) return

        // Distinct session ids in the order they first appear — the server
        // already hands them back oldest-first (see ApiClient.getPatientEegTrend).
        val sessionIds = points.map { it.sessionId }.distinct()
        val stepIndexBySession = sessionIds.withIndex().associate { (idx, id) -> id to idx }
        val stepLabels = sessionIds.map { id ->
            formatShortDate(points.first { it.sessionId == id }.sessionStartedAt)
        }

        val chartPoints = points.map { p ->
            val value = when (selectedEegTrendMetric) {
                EegTrendMetric.EXPONENT -> p.aperiodicExponent
                EegTrendMetric.EOG_RATE -> p.eogSemRatePerMin
            }
            // Low-fit halves are drawn hollow — exponent only: the eye-movement
            // rate comes from a separate detector, not the specparam fit, so
            // r² says nothing about it.
            val flagged = selectedEegTrendMetric == EegTrendMetric.EXPONENT && isLowFit(p.rSquared, p.lowFitRSquared)
            EegMetricChartView.Point(stepIndexBySession.getValue(p.sessionId), p.period, value, flagged)
        }

        binding.eegTrendChartView.setData(stepLabels, chartPoints, EEG_TREND_PERIOD_LABELS, EEG_TREND_PERIOD_COLORS)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** One row in the TCM sessions list: either a real, server-fetched session,
 * or the in-progress one SessionRecorder is currently holding for this
 * patient (see PatientDetailActivity.buildSessionRows) — the latter doesn't
 * have a session id yet (nothing's been uploaded), so it can't be an
 * [SessionSummary] and can't route to SessionDetailActivity; tapping it
 * reopens SessionActivity instead, which reattaches to it. */
private sealed class SessionRow {
    data class Existing(val summary: SessionSummary) : SessionRow()

    /** Reflects SessionRecorder's state as of the list's last load
     * (PatientDetailActivity.onResume/onCreate) — not live-updating while
     * this screen sits open on the sessions tab. A save that finishes or
     * fails while the practitioner is looking at this list won't flip this
     * row until the next reload (returning to this screen, or switching
     * patients and back). Acceptable for now. */
    data class Ongoing(val phase: SessionRecorder.Phase, val startedAtIso: String) : SessionRow()
}

private class SessionAdapter(private val onClick: (SessionRow) -> Unit) :
    RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    private var items: List<SessionRow> = emptyList()

    fun submit(rows: List<SessionRow>) {
        items = rows
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val modalityText: TextView = view.findViewById(R.id.modalityText)
        val dateText: TextView = view.findViewById(R.id.dateText)
        val statusBadgeText: TextView = view.findViewById(R.id.statusBadgeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = items[position]
        val context = holder.itemView.context
        when (row) {
            is SessionRow.Existing -> {
                holder.modalityText.text = row.summary.modality
                holder.dateText.text = formatSessionRange(row.summary.startedAt, row.summary.endedAt)
                holder.statusBadgeText.visibility = View.GONE
            }
            is SessionRow.Ongoing -> {
                holder.modalityText.text = "In-progress session"
                holder.dateText.text = "Started ${formatTimestamp(row.startedAtIso)}"
                holder.statusBadgeText.visibility = View.VISIBLE
                when (row.phase) {
                    SessionRecorder.Phase.SAVE_FAILED -> {
                        holder.statusBadgeText.text = "Not saved"
                        holder.statusBadgeText.setBackgroundResource(R.drawable.bg_badge_warning)
                        holder.statusBadgeText.setTextColor(ContextCompat.getColor(context, R.color.warning))
                    }
                    SessionRecorder.Phase.SAVING -> {
                        holder.statusBadgeText.text = "Saving…"
                        holder.statusBadgeText.setBackgroundResource(R.drawable.bg_badge_accent)
                        holder.statusBadgeText.setTextColor(ContextCompat.getColor(context, R.color.accent))
                    }
                    else -> { // RECORDING
                        holder.statusBadgeText.text = "● Ongoing"
                        holder.statusBadgeText.setBackgroundResource(R.drawable.bg_badge_success)
                        holder.statusBadgeText.setTextColor(ContextCompat.getColor(context, R.color.success))
                    }
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(row) }
    }

    override fun getItemCount(): Int = items.size
}

private class NoteAdapter(private val onClick: (PatientNote) -> Unit) :
    RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    private var items: List<PatientNote> = emptyList()

    fun submit(notes: List<PatientNote>) {
        items = notes
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.noteDateText)
        val bodyText: TextView = view.findViewById(R.id.noteBodyText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = items[position]
        // Author is always the viewing practitioner on their own list (see
        // PatientNote's docstring server-side), so this reads a little
        // redundant there — but it's the only way self-view can tell notes
        // from different practitioners apart, so it's shown either way
        // rather than threading a "whose list is this" flag in just for display.
        holder.dateText.text = "${formatTimestamp(note.createdAt)}  ·  ${note.practitionerName}"
        holder.bodyText.text = note.body
        holder.itemView.setOnClickListener { onClick(note) }
    }

    override fun getItemCount(): Int = items.size
}
