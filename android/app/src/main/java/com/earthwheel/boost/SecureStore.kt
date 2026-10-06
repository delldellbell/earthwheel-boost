package com.earthwheel.boost

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Date sensibile (cod, email, parolă Gmail) criptate AES-256-GCM
 * cu o cheie ținută în Android Keystore (nu poate fi extrasă din telefon).
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val key: SecretKey? = try { loadOrCreateKey() } catch (e: Exception) { null }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? {
        val k = key ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k)
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String? {
        val k = key ?: return null
        return try {
            val all = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getS(name: String): String? = prefs.getString(name, null)?.let { decrypt(it) }

    private fun putS(name: String, v: String?) {
        val e = prefs.edit()
        if (v == null) e.remove(name) else {
            val enc = encrypt(v)
            if (enc == null) e.remove(name) else e.putString(name, enc)
        }
        e.apply()
    }

    /** codul scooterului; null = necunoscut, "" = dispozitivul nu are cod */
    var pin: String?
        get() = getS("pin")
        set(v) = putS("pin", v)

    /** adresa plăcii asociate - aplicația se conectează automat doar la ea */
    var deviceAddress: String?
        get() = getS("addr")
        set(v) = putS("addr", v)

    var recoveryEmail: String?
        get() = getS("email")
        set(v) = putS("email", v?.trim()?.takeIf { it.isNotEmpty() })

    var smtpUser: String?
        get() = getS("smtp_user")
        set(v) = putS("smtp_user", v?.trim()?.takeIf { it.isNotEmpty() })

    var smtpPass: String?
        get() = getS("smtp_pass")
        set(v) = putS("smtp_pass", v?.replace(" ", "")?.takeIf { it.isNotEmpty() })

    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    val smtpConfigured: Boolean
        get() = !smtpUser.isNullOrBlank() && !smtpPass.isNullOrBlank()

    companion object {
        private const val FILE = "ewb_secure"
        private const val ALIAS = "earthwheel_boost_store"
    }
}
