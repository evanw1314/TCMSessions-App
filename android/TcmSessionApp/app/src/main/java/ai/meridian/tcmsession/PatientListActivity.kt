package ai.meridian.tcmsession

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.meridian.tcmsession.databinding.ActivityPatientListBinding

class PatientListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPatientListBinding
    private val adapter = PatientAdapter { patient -> openPatientDetail(patient) }

    // Full roster from the server; the adapter only ever sees the
    // search-filtered view of it (see filterAndRender()).
    private var allPatients: List<Patient> = emptyList()
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.clinicNameText.text = AppConfig.clinicName(this).ifBlank { getString(R.string.app_name) }

        binding.patientRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.patientRecyclerView.adapter = adapter
        binding.signOutButton.setOnClickListener { signOut() }
        binding.addPatientButton.setOnClickListener { showAddPatientDialog() }
        binding.retryButton.setOnClickListener { loadPatients() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                filterAndRender()
            }
        })
        // The field's imeOptions is actionSearch, but filtering already happens live
        // via the TextWatcher above — this just dismisses the keyboard on that action
        // so tapping "search" doesn't leave it hanging open over the results.
        binding.searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }

        loadPatients()
    }

    // Clears the cached login only — NOT the server address (AppConfig.setServerHost),
    // since LoginActivity pre-fills that field from the cache and it's editable there;
    // this exists specifically so switching networks (a new server address) doesn't
    // require digging into Android's app-storage settings to force a fresh login screen.
    private fun signOut() {
        AppConfig.clearSession(this)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun loadPatients() {
        // Only the very first load (nothing on screen yet) gets the spinner/retry
        // treatment — a background refresh (e.g. onResume with a roster already
        // showing) shouldn't yank the list away and replace it with a spinner, and
        // a transient failure there falls back to a Toast instead of nuking
        // a perfectly good list.
        val isFirstLoad = allPatients.isEmpty()
        if (isFirstLoad) {
            binding.loadingIndicator.visibility = View.VISIBLE
            binding.retryContainer.visibility = View.GONE
            binding.emptyStateText.visibility = View.GONE
        }
        lifecycleScope.launch {
            try {
                allPatients = ApiClient(this@PatientListActivity).listPatients()
                filterAndRender()
            } catch (e: Exception) {
                if (isFirstLoad) {
                    binding.retryMessageText.text = e.message ?: "Couldn't load patients"
                    binding.retryContainer.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this@PatientListActivity, e.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun filterAndRender() {
        val filtered = if (searchQuery.isBlank()) {
            allPatients
        } else {
            allPatients.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    (it.email?.contains(searchQuery, ignoreCase = true) ?: false)
            }
        }
        adapter.submit(filtered)
        binding.emptyStateText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyStateText.text = when {
            allPatients.isEmpty() -> "No patients yet — tap \"Add patient\" to get started."
            filtered.isEmpty() -> "No patients match \"$searchQuery\""
            else -> ""
        }
    }

    // No account is required up front: a patient can be added by name alone
    // at intake (server/app/models.py's User.email is nullable for exactly
    // this), with an email assigned later — see PatientDetailActivity's
    // "Assign email" action. The email field here is an optional shortcut for
    // the other case, a patient who ALREADY has an account: fill it in and
    // that existing account is linked instead of creating a new name-only one
    // (mirrors AddPatientRequest server-side).
    private fun showAddPatientDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val nameInput = android.widget.EditText(this).apply {
            hint = "Patient name"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val emailInput = android.widget.EditText(this).apply {
            hint = "Email (optional — only if they already have an account)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(nameInput)
            addView(emailInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Add patient")
            .setMessage("A device or account isn't required yet — you can assign an email later, once the patient is ready for one.")
            .setView(container)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                // Overriding the positive button's click after show() (rather than
                // in setPositiveButton) keeps the dialog open on failure instead of
                // dismissing before the network call even returns.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = nameInput.text.toString().trim()
                    val email = emailInput.text.toString().trim()
                    if (name.isBlank() && email.isBlank()) {
                        nameInput.error = "Enter a name"
                        return@setOnClickListener
                    }
                    addPatient(name, email, dialog)
                }
            }
    }

    private fun addPatient(name: String, email: String, dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        lifecycleScope.launch {
            try {
                ApiClient(this@PatientListActivity).addPatient(name.ifBlank { null }, email.ifBlank { null })
                dialog.dismiss()
                loadPatients()
            } catch (e: Exception) {
                Toast.makeText(this@PatientListActivity, e.message ?: "Failed to add patient", Toast.LENGTH_LONG).show()
            } finally {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }
    }

    private fun openPatientDetail(patient: Patient) {
        val intent = Intent(this, PatientDetailActivity::class.java)
        intent.putExtra("patient_id", patient.id)
        intent.putExtra("patient_name", patient.name)
        intent.putExtra("patient_email", patient.email)
        intent.putExtra("headband_device_id", patient.headbandDeviceId)
        intent.putExtra("last_session_at", patient.lastSessionAt)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        loadPatients()
        checkForInterruptedRecordings()
    }

    // ---- Recovering recordings the app didn't finish saving (see
    // SessionJournal). Checked here because this is the practitioner's home
    // screen, reached right after login, and uploading needs a login. What
    // must hold across screens (which journals are live, being uploaded, or
    // put off) lives in SessionJournal, not in this Activity, so a rotation
    // or a second copy of this screen can't offer a recording twice. ----

    private class Interrupted(
        val journal: SessionJournal,
        val meta: SessionJournal.Meta,
        val recordedSeconds: Int,
    )

    // A dialog from this screen is showing, or its upload/delete is running.
    private var recoveryInProgress = false

    /** Offers the first interrupted recording, if any; each outcome calls
     * this again for the next. */
    private fun checkForInterruptedRecordings() {
        if (recoveryInProgress) return
        recoveryInProgress = true
        val userId = AppConfig.userId(this)
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                SessionJournal.orphans(this@PatientListActivity).firstNotNullOfOrNull { describe(it, userId) }
            }
            if (next == null || isFinishing || isDestroyed) {
                recoveryInProgress = false
            } else {
                showRecoveryDialog(next)
            }
        }
    }

    /** Null for a journal not worth offering to this practitioner. One with
     * nothing left in it (the app died in the first second, or only an
     * already-uploaded session's id is left) is deleted on the spot. */
    private fun describe(journal: SessionJournal, userId: Int): Interrupted? {
        val meta = journal.readMeta() ?: run {
            // The app died before the first write: nothing to save, and
            // nothing to say whose it was. One that somehow has data but no
            // details is left alone rather than deleted unseen.
            if (!journal.hasAnyData()) journal.deleteNow()
            return null
        }
        // Recorded by someone else on this phone: only they can save it
        // (sessions belong to the practitioner who created them), so it
        // waits for their login rather than being offered, or deleted, here.
        if (meta.practitionerId != null && meta.practitionerId != userId) return null
        val samples = if (meta.sessionId == null) journal.readSamples() else emptyList()
        val nothingLeft = if (meta.sessionId == null) {
            samples.isEmpty() && meta.markers.isEmpty() && !journal.hasRawEeg()
        } else {
            !journal.hasRawEeg()
        }
        if (nothingLeft) {
            journal.deleteNow()
            return null
        }
        return Interrupted(journal, meta, samples.maxOfOrNull { it.offsetSeconds + 1 } ?: 0)
    }

    private fun showRecoveryDialog(item: Interrupted) {
        val m = item.meta
        val started = formatTimestamp(m.startedAtIso)
        val builder = AlertDialog.Builder(this).setCancelable(false)
        if (m.sessionId == null) {
            val length = "%d:%02d".format(item.recordedSeconds / 60, item.recordedSeconds % 60)
            builder.setTitle("Unsaved recording found")
                .setMessage(
                    "The app closed before this session was saved:\n\n" +
                        "${m.patientName} · ${m.modality}\nStarted $started · $length recorded\n\n" +
                        "Save it now? Everything up to the last recorded second is kept" +
                        (if (m.endedAtIso == null) ", and the chart will show where it was cut off." else ".")
                )
                .setPositiveButton("Save") { _, _ -> uploadRecovered(item) }
        } else {
            builder.setTitle("Raw recording not uploaded")
                .setMessage(
                    "${m.patientName}'s ${m.modality} session from $started was saved, but its raw brainwave " +
                        "recording didn't upload, so it can't be analyzed yet.\n\nUpload it now?"
                )
                .setPositiveButton("Upload") { _, _ -> uploadRecovered(item) }
        }
        builder.setNegativeButton("Delete") { _, _ -> confirmDiscard(item) }
            .setNeutralButton("Later") { _, _ ->
                SessionJournal.postpone(item.journal)
                recoveryInProgress = false
                checkForInterruptedRecordings()
            }
            .show()
    }

    private fun uploadRecovered(item: Interrupted) {
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        lifecycleScope.launch {
            // NonCancellable: leaving this screen mid-upload must not stop it
            // between the session being created and its id being recorded.
            val outcome: Result<SessionJournal.RecoveryResult> = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { item.journal.uploadRecovered(appContext) }
            }
            if (isFinishing || isDestroyed) return@launch
            val result = outcome.getOrNull()
            val failure = outcome.exceptionOrNull()
            when {
                result is SessionJournal.RecoveryResult.SavedWithoutRawEeg -> {
                    if (result.kept) SessionJournal.postpone(item.journal)
                    showRecoveryOutcome(
                        "Session saved, raw recording not uploaded",
                        "The session is saved, but its raw brainwave recording didn't upload: ${result.reason}\n\n" +
                            if (result.kept) "It's still on the phone, and you'll be asked again the next time the app starts."
                            else "Trying again couldn't help, so it has been removed from the phone. The session " +
                                "can't be analyzed.",
                    )
                    loadPatients()
                }
                failure != null -> {
                    SessionJournal.postpone(item.journal)
                    val refused = failure is Exception && isServerRefusal(failure)
                    showRecoveryOutcome(
                        "Couldn't save it",
                        "${failure.message ?: "Unknown error"}\n\n" +
                            if (refused) "The server refused this recording, so trying again probably won't help. " +
                                "It's still on the phone; you can delete it the next time the app starts."
                            else "It's still on the phone, and you'll be asked again the next time the app starts.",
                    )
                }
                else -> {
                    if (result == SessionJournal.RecoveryResult.Saved) {
                        Toast.makeText(this@PatientListActivity, "Recovered session saved", Toast.LENGTH_SHORT).show()
                        loadPatients()
                    }
                    recoveryInProgress = false
                    checkForInterruptedRecordings()
                }
            }
        }
    }

    private fun showRecoveryOutcome(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                recoveryInProgress = false
                checkForInterruptedRecordings()
            }
            .setCancelable(false)
            .show()
    }

    private fun confirmDiscard(item: Interrupted) {
        val message = if (item.meta.sessionId == null) {
            "Delete this recording? This can't be undone."
        } else {
            "Delete the raw recording? The saved session stays, but it can't be analyzed without it. " +
                "This can't be undone."
        }
        AlertDialog.Builder(this)
            .setTitle("Delete it?")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { item.journal.deleteNow() }
                    recoveryInProgress = false
                    checkForInterruptedRecordings()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> showRecoveryDialog(item) }
            .setCancelable(false)
            .show()
    }
}

private class PatientAdapter(private val onClick: (Patient) -> Unit) :
    RecyclerView.Adapter<PatientAdapter.ViewHolder>() {

    private var items: List<Patient> = emptyList()

    fun submit(patients: List<Patient>) {
        items = patients
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarText: TextView = view.findViewById(R.id.avatarText)
        val nameText: TextView = view.findViewById(R.id.nameText)
        val subtitleText: TextView = view.findViewById(R.id.subtitleText)
        val deviceBadgeText: TextView = view.findViewById(R.id.deviceBadgeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_patient, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val patient = items[position]
        val context = holder.itemView.context

        holder.avatarText.text = initialsFor(patient.name)
        holder.nameText.text = patient.name

        holder.subtitleText.text = when {
            patient.lastSessionAt != null -> "Last session ${formatTimestamp(patient.lastSessionAt)}"
            patient.headbandDeviceId != null -> "Headband ${patient.headbandDeviceId} · no sessions yet"
            else -> "No device assigned"
        }

        when {
            patient.headbandDeviceId != null -> {
                holder.deviceBadgeText.text = "Headband"
                holder.deviceBadgeText.setBackgroundResource(R.drawable.bg_badge_accent)
                holder.deviceBadgeText.setTextColor(ContextCompat.getColor(context, R.color.accent))
            }
            else -> {
                holder.deviceBadgeText.text = "No device"
                holder.deviceBadgeText.setBackgroundResource(R.drawable.bg_badge_neutral)
                holder.deviceBadgeText.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
        }

        holder.itemView.setOnClickListener { onClick(patient) }
    }

    override fun getItemCount(): Int = items.size

    private fun initialsFor(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
        }
    }
}
