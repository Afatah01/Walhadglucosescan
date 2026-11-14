package com.glucosescan.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Locks the passwords and keys the user types in (LibreLinkUp, AI, online copy) before they
 * are written to the settings of the app.
 *
 * The lock is a key in the "key store" of Android. That key never leaves the phone and no
 * other app can use it. If a phone has no working key store, the secret is saved without
 * the lock; the settings of an app are private on Android in any case, and they are not
 * part of any backup of the phone.
 */
object Secrets {
    private const val ALIAS = "walhad_secrets_1"
    private const val LOCKED = "k1:"
    private const val PLAIN = "p0:"

    /** The text to save for [plain]. An empty text stays empty. */
    fun lock(plain: String): String {
        if (plain.isEmpty()) {
            return ""
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return LOCKED + encode(cipher.iv) + ":" + encode(sealed)
        } catch (e: Exception) {
            return PLAIN + encode(plain.toByteArray(Charsets.UTF_8))
        }
    }

    /** The secret behind a saved text, or "" if there is none or it cannot be opened any more. */
    fun open(saved: String?): String {
        if (saved.isNullOrEmpty()) {
            return ""
        }
        try {
            if (saved.startsWith(PLAIN)) {
                return String(decode(saved.substring(PLAIN.length)), Charsets.UTF_8)
            }
            if (saved.startsWith(LOCKED)) {
                val parts = saved.substring(LOCKED.length).split(':')
                if (parts.size != 2) {
                    return ""
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(parts[0])))
                return String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // the key of the phone is gone or the text is damaged: the user types the secret again
        }
        return ""
    }

    /** True if [saved] is locked with the key of the phone (and not only kept privately). */
    fun isLocked(saved: String?): Boolean = saved != null && saved.startsWith(LOCKED)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE)
}
