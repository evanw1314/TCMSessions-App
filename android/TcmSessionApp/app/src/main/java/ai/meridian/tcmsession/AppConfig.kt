package ai.meridian.tcmsession

import android.content.Context
import android.content.SharedPreferences

/**
 * Server address + session token, persisted in SharedPreferences. The
 * token is stored sealed by TokenVault, never in plain text.
 *
 * The practitioner's phone is a separate device from whatever runs the
 * FastAPI server, so the server host is a runtime-editable field on the
 * login screen rather than baked into the build.
 */
object AppConfig {
    private const val PREFS = "tcm_session_prefs"
    private const val KEY_SERVER_HOST = "server_host"
    private const val KEY_TOKEN = "access_token_sealed"
    // Where builds before TokenVault kept the token in plain text; moved
    // across (sealed) the first time it is read.
    private const val KEY_LEGACY_TOKEN = "access_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_CLINIC_NAME = "clinic_name"
    // "practitioner" or "patient" (mirrors LoginResult.role) — decides which
    // screen a returning, already-logged-in session lands on (see
    // LoginActivity.onCreate) and which endpoints ApiClient may call.
    private const val KEY_ROLE = "role"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // A saved address wins; otherwise the build's default
    // (BuildConfig.DEFAULT_SERVER_URL, the cloud server once there is one —
    // see build.gradle.kts). Blank in a LAN-only build, where the
    // practitioner types their clinic server's address once
    // (e.g. http://192.168.1.20:8000).
    fun serverHost(context: Context): String =
        prefs(context).getString(KEY_SERVER_HOST, "").orEmpty().ifBlank { BuildConfig.DEFAULT_SERVER_URL }

    // The build's default isn't saved as if it had been typed: left blank,
    // a later build with a new default (a re-created service, a custom
    // domain) still reaches this phone.
    fun setServerHost(context: Context, host: String) {
        val trimmed = host.trim().trimEnd('/')
        val saved = if (trimmed == BuildConfig.DEFAULT_SERVER_URL.trimEnd('/')) "" else trimmed
        prefs(context).edit().putString(KEY_SERVER_HOST, saved).apply()
    }

    @Volatile private var opened: Pair<String, String>? = null

    fun token(context: Context): String? {
        val prefs = prefs(context)
        prefs.getString(KEY_LEGACY_TOKEN, null)?.let { legacy ->
            // If the Keystore fails here, stay signed in on the plain copy
            // and try again next time, rather than crash on every launch.
            val sealed = runCatching { TokenVault.seal(legacy) }.getOrNull() ?: return legacy
            prefs.edit().putString(KEY_TOKEN, sealed).remove(KEY_LEGACY_TOKEN).commit()
        }
        val sealed = prefs.getString(KEY_TOKEN, null) ?: return null
        // Every request reads the token; skip the Keystore when it's unchanged.
        opened?.let { (key, plain) -> if (key == sealed) return plain }
        val plain = try {
            TokenVault.open(sealed)
        } catch (e: Exception) {
            return null // Keystore briefly unavailable: not signed out, just not now.
        }
        return plain?.also { opened = sealed to it } ?: run {
            // Restored from another phone, or corrupt: signed out.
            prefs.edit().remove(KEY_TOKEN).apply()
            null
        }
    }

    fun userId(context: Context): Int = prefs(context).getInt(KEY_USER_ID, -1)

    fun userName(context: Context): String =
        prefs(context).getString(KEY_USER_NAME, "") ?: ""

    // Blank when the account has no clinic (shouldn't happen for a
    // practitioner login, but this app doesn't hard-fail a display nicety).
    fun clinicName(context: Context): String =
        prefs(context).getString(KEY_CLINIC_NAME, "") ?: ""

    fun role(context: Context): String =
        prefs(context).getString(KEY_ROLE, "") ?: ""

    fun isPatient(context: Context): Boolean = role(context) == "patient"

    fun saveSession(
        context: Context, token: String, userId: Int, userName: String,
        clinicName: String?, role: String,
    ) {
        prefs(context).edit()
            .putString(KEY_TOKEN, TokenVault.seal(token))
            .remove(KEY_LEGACY_TOKEN)
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_USER_NAME, userName)
            .putString(KEY_CLINIC_NAME, clinicName ?: "")
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_LEGACY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_CLINIC_NAME)
            .remove(KEY_ROLE)
            .apply()
    }

    fun isLoggedIn(context: Context): Boolean = token(context) != null
}
