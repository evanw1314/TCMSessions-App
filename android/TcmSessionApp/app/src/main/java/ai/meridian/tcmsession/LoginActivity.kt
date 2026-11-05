package ai.meridian.tcmsession

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import ai.meridian.tcmsession.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    companion object {
        // OAuth "Web application" client ID from Google Cloud Console — becomes
        // the `aud` claim on the ID token Credential Manager returns, which the
        // server checks against GOOGLE_CLIENT_ID_ANDROID (see
        // security.verify_google_id_token). NOT the "Android"-type client —
        // that one is registered with the app's package name + signing-cert
        // SHA-1 fingerprint and never appears in code. Not a secret (same as
        // the iOS client ID baked into ios/Support/Info.plist), safe to commit
        // once filled in.
        private const val GOOGLE_WEB_CLIENT_ID = "859022544598-o06r56lk8k8njojk19gqpq5kq36nie1t.apps.googleusercontent.com"
    }

    private lateinit var binding: ActivityLoginBinding
    private var isCreateAccountMode = false
    // Only meaningful in create-account mode — which role to register the new
    // account under. Sign-in has no role picker: the account's own role
    // (returned by the server) decides where applyLoginResult() routes.
    // Patient defaults to selected since patients are the far more common
    // signup.
    private var isPatientRole = false

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanned = result.data?.getStringExtra(QrScanActivity.EXTRA_SCANNED_VALUE)
        if (result.resultCode == RESULT_OK && !scanned.isNullOrBlank()) {
            binding.serverHostInput.setText(scanned)
            AppConfig.setServerHost(this, scanned)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScan()
        } else {
            showError("Camera permission is required to scan a QR code")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverHostInput.setText(AppConfig.serverHost(this))

        if (AppConfig.isLoggedIn(this)) {
            goToRoleHome()
            return
        }

        // Expanded by default once a host is already saved, so a returning
        // practitioner can see/verify it without an extra tap.
        setServerSettingsExpanded(AppConfig.serverHost(this).isNotBlank())
        binding.serverSettingsToggle.setOnClickListener {
            setServerSettingsExpanded(binding.serverSettingsSection.visibility != android.view.View.VISIBLE)
        }

        binding.googleSignInButton.setOnClickListener { signInWithGoogle() }

        binding.scanQrButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScan()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.modeSignInButton.setOnClickListener { setCreateAccountMode(false) }
        binding.modeCreateAccountButton.setOnClickListener { setCreateAccountMode(true) }
        binding.rolePractitionerButton.setOnClickListener { setPatientRole(false) }
        binding.rolePatientButton.setOnClickListener { setPatientRole(true) }
        setCreateAccountMode(false)

        binding.submitButton.setOnClickListener {
            val host = binding.serverHostInput.text.toString().trim()
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()
            val name = binding.nameInput.text.toString().trim()
            if (host.isBlank() || email.isBlank() || password.isBlank()) {
                showError("Enter a server address, email, and password")
                return@setOnClickListener
            }
            if (isCreateAccountMode && name.isBlank()) {
                showError("Enter your name")
                return@setOnClickListener
            }
            if (isCreateAccountMode && password != binding.confirmPasswordInput.text.toString()) {
                showError("Passwords don't match")
                return@setOnClickListener
            }
            AppConfig.setServerHost(this, host)
            if (isCreateAccountMode) {
                val role = if (isPatientRole) "patient" else "practitioner"
                createAccount(email, password, name, role)
            } else {
                signIn(email, password)
            }
        }
    }

    private fun setCreateAccountMode(createAccount: Boolean) {
        isCreateAccountMode = createAccount
        clearError()
        // Role picker only makes sense when creating an account — sign-in
        // resolves the role from whichever account the credentials belong to.
        binding.roleGroup.visibility = if (createAccount) View.VISIBLE else View.GONE
        binding.nameLabel.visibility = if (createAccount) View.VISIBLE else View.GONE
        binding.nameInput.visibility = if (createAccount) View.VISIBLE else View.GONE
        binding.confirmPasswordLabel.visibility = if (createAccount) View.VISIBLE else View.GONE
        binding.confirmPasswordInput.visibility = if (createAccount) View.VISIBLE else View.GONE
        if (!createAccount) binding.confirmPasswordInput.text.clear()
        binding.submitButton.text = if (createAccount) "Create account" else "Sign in"

        val (signInBg, signInText) = if (createAccount)
            R.color.surface_1 to R.color.text_secondary else R.color.accent_bg to R.color.accent
        val (createBg, createText) = if (createAccount)
            R.color.accent_bg to R.color.accent else R.color.surface_1 to R.color.text_secondary
        binding.modeSignInButton.setBackgroundColor(getColor(signInBg))
        binding.modeSignInButton.setTextColor(getColor(signInText))
        binding.modeCreateAccountButton.setBackgroundColor(getColor(createBg))
        binding.modeCreateAccountButton.setTextColor(getColor(createText))
    }

    private fun setPatientRole(patient: Boolean) {
        isPatientRole = patient
        val (pracBg, pracText) = if (patient)
            R.color.surface_1 to R.color.text_secondary else R.color.accent_bg to R.color.accent
        val (patientBg, patientText) = if (patient)
            R.color.accent_bg to R.color.accent else R.color.surface_1 to R.color.text_secondary
        binding.rolePractitionerButton.setBackgroundColor(getColor(pracBg))
        binding.rolePractitionerButton.setTextColor(getColor(pracText))
        binding.rolePatientButton.setBackgroundColor(getColor(patientBg))
        binding.rolePatientButton.setTextColor(getColor(patientText))
    }

    private fun setServerSettingsExpanded(expanded: Boolean) {
        binding.serverSettingsSection.visibility =
            if (expanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.serverSettingsToggle.text =
            if (expanded) "Server settings ▴" else "Server settings ▾"
    }

    private fun launchQrScan() {
        qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
    }

    /** Every error on this screen goes through here. The TextView is GONE
     * rather than merely blank when there is no message: an empty TextView
     * still measures a full line, which leaves a dead gap under the submit
     * button. */
    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearError() = showError("")

    private fun signIn(email: String, password: String) {
        clearError()
        binding.submitButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = ApiClient(this@LoginActivity).login(email, password)
                applyLoginResult(result)
            } catch (e: Exception) {
                showError(e.message ?: "Sign-in failed")
            } finally {
                binding.submitButton.isEnabled = true
            }
        }
    }

    /** Shared by password sign-in and Google sign-in: there's no role picker on
     * the sign-in path, so whatever role the account actually has is
     * simply where it goes — see goToRoleHome(). */
    private fun applyLoginResult(result: LoginResult) {
        AppConfig.saveSession(
            this@LoginActivity, result.token, result.userId, result.userName,
            result.clinicName, result.role,
        )
        goToRoleHome()
    }

    /** "Continue with Google" — Credential Manager's Sign in with Google flow.
     * Deliberately link-or-reject, never create: ApiClient.googleLogin() sends
     * no `role`, so the server only succeeds if an account with this Google
     * email already exists (created earlier with a password); a brand-new
     * Google email 422s and is turned into a friendlier prompt below. */
    private fun signInWithGoogle() {
        if (GOOGLE_WEB_CLIENT_ID.startsWith("REPLACE_WITH")) {
            showError("Google Sign-In isn't configured yet (missing web client ID)")
            return
        }
        clearError()
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(GOOGLE_WEB_CLIENT_ID)
            // Not filtering to previously-authorized accounts: linking a
            // patient's or practitioner's FIRST Google sign-in to their
            // existing password account has no prior authorization to filter on.
            .setFilterByAuthorizedAccounts(false)
            .build()
        val credentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val response = CredentialManager.create(this@LoginActivity)
                    .getCredential(this@LoginActivity, credentialRequest)
                val credential = response.credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    showError("Unexpected credential type from Google")
                    return@launch
                }
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val result = ApiClient(this@LoginActivity).googleLogin(idToken)
                applyLoginResult(result)
            } catch (e: GetCredentialCancellationException) {
                // User dismissed the account picker — not an error worth surfacing.
            } catch (e: NoCredentialException) {
                showError("No Google account found on this device")
            } catch (e: GetCredentialException) {
                showError(e.message ?: "Google sign-in failed")
            } catch (e: GoogleIdTokenParsingException) {
                showError("Failed to read Google credential")
            } catch (e: ApiException) {
                showError(
                    if (e.message?.contains("role is required") == true) {
                        "No account found for that Google email. Create an account first, " +
                            "then use \"Continue with Google\" to link it."
                    } else {
                        e.message ?: "Google sign-in failed"
                    }
                )
            }
        }
    }

    private fun createAccount(email: String, password: String, name: String, role: String) {
        clearError()
        binding.submitButton.isEnabled = false
        lifecycleScope.launch {
            try {
                // register() doesn't log in directly — it emails a 6-digit
                // code and the account only becomes real once VerifyEmailActivity
                // confirms it (POST /auth/verify).
                val pending = ApiClient(this@LoginActivity).register(email, password, name, role)
                val intent = Intent(this@LoginActivity, VerifyEmailActivity::class.java)
                intent.putExtra("email", email)
                intent.putExtra("code_sent_to", pending.codeSentTo)
                intent.putExtra("password", password)
                intent.putExtra("name", name)
                intent.putExtra("role", role)
                startActivity(intent)
            } catch (e: Exception) {
                showError(e.message ?: "Account creation failed")
            } finally {
                binding.submitButton.isEnabled = true
            }
        }
    }

    /** Routes a logged-in session to its role's home screen — the
     * practitioner's roster, or a patient's own read-only profile (the same
     * PatientDetailActivity a practitioner sees for one patient, in
     * self-view mode; see that Activity's isSelfView). */
    private fun goToRoleHome() {
        val intent = if (AppConfig.isPatient(this))
            Intent(this, PatientDetailActivity::class.java).putExtra("self_view", true)
        else
            Intent(this, PatientListActivity::class.java)
        startActivity(intent)
        finish()
    }
}
