package ai.meridian.tcmsession

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import ai.meridian.tcmsession.ui.AppTheme
import ai.meridian.tcmsession.ui.VerifyEmailScreen

/** Second half of account creation: LoginActivity's createAccount() lands
 * here after POST /auth/register returns "pending, check your email" instead
 * of a token. Carries email/password/name/role via extras so "Resend code"
 * can just re-call register() (that's the server's resend path — see
 * ApiClient.register's doc comment).
 *
 * The UI is Compose, in ui/VerifyEmailScreen.kt; this class keeps only the
 * extras, the ApiClient calls and the navigation. An AppCompatActivity, not a
 * bare ComponentActivity, so it shares the DayNight delegate with the
 * View-based screens. */
class VerifyEmailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extraEmail = intent.getStringExtra("email")
        if (extraEmail.isNullOrBlank()) {
            finish()
            return
        }
        val email = extraEmail
        val password = intent.getStringExtra("password") ?: ""
        val name = intent.getStringExtra("name") ?: ""
        val role = intent.getStringExtra("role") ?: "practitioner"
        // A practitioner's code may go to the team inbox instead (the
        // server's practitioner_verification_inbox); the server says where.
        val codeSentTo = intent.getStringExtra("code_sent_to") ?: email
        val subtitle = if (!codeSentTo.equals(email, ignoreCase = true)) {
            "We sent a code to $codeSentTo for verification"
        } else {
            "We sent a code to $email"
        }

        setContent {
            AppTheme {
                VerifyEmailScreen(
                    subtitle = subtitle,
                    onVerify = { code -> verify(email, role, code) },
                    onResend = { resend(email, password, name, role) },
                    onUseDifferentEmail = { finish() },
                )
            }
        }
    }

    /** null on success (this activity is finishing by then), else the message. */
    private suspend fun verify(email: String, role: String, code: String): String? {
        return try {
            val result = ApiClient(this).verify(email, code)
            AppConfig.saveSession(
                this, result.token, result.userId, result.userName,
                result.clinicName, result.role,
            )
            val homeIntent = if (role == "patient") {
                Intent(this, PatientDetailActivity::class.java).putExtra("self_view", true)
            } else {
                Intent(this, PatientListActivity::class.java)
            }
            startActivity(homeIntent)
            // Clears LoginActivity too, not just this screen — otherwise
            // the back button from the home screen would resurface a
            // stale pre-login form (LoginActivity only checks
            // isLoggedIn() in onCreate, not onResume).
            finishAffinity()
            null
        } catch (e: Exception) {
            e.message ?: "Verification failed"
        }
    }

    /** null on success, which restarts the screen's resend cooldown. */
    private suspend fun resend(email: String, password: String, name: String, role: String): String? {
        return try {
            ApiClient(this).register(email, password, name, role)
            null
        } catch (e: Exception) {
            e.message ?: "Failed to resend code"
        }
    }
}
