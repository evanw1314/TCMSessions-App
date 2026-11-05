package ai.meridian.tcmsession

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the session token with an AES key held in the Android Keystore.
 *
 * The app's SharedPreferences are included in Google Drive backups and
 * phone-to-phone transfers (allowBackup), and the token is a 30-day login.
 * The Keystore key never leaves this device, so a sealed token restored
 * elsewhere can't be opened: that phone simply shows the login screen.
 */
internal object TokenVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "tcm_session_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12  // the Keystore's GCM IV size

    /** Base64 of IV + ciphertext. */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_BYTES) { "unexpected GCM IV size ${cipher.iv.size}" }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null when this token can never be opened: restored from another
     * device (no key here, or the tag doesn't match) or corrupt. Anything
     * else - a Keystore that's briefly unavailable - throws, so the caller
     * doesn't sign the user out over a transient failure. */
    fun open(sealed: String): String? {
        val key = existingKey() ?: return null
        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE,
    ).apply {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
    }.generateKey()
}
