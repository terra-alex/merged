package co.tryterra.merged

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The GitHub token, AES-GCM-sealed by a non-exportable Android Keystore key.
 * Platform APIs only — no Tink / security-crypto dependency.
 */
class Vault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vault", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY, null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(value) {
            val e = prefs.edit()
            if (value.isNullOrBlank()) e.remove(KEY) else e.putString(KEY, encrypt(value.trim())).remove(EXPIRED)
            e.apply()
        }

    /** GitHub rejected the token (401): the meter keeps the last numbers, cooled, under TOKEN EXPIRED. A new token clears it. */
    var expired: Boolean
        get() = prefs.getBoolean(EXPIRED, false)
        set(v) { prefs.edit().putBoolean(EXPIRED, v).apply() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val b = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, IV_LEN))
        }
        return String(c.doFinal(b, IV_LEN, b.size - IV_LEN))
    }

    private companion object {
        const val KEY = "github_token"
        const val EXPIRED = "expired"
        const val ALIAS = "merged_vault"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
