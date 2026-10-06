package com.earthwheel.boost

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Date sensibile criptate cu Android Keystore (AES-256). */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = open(context)

    private fun open(context: Context): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // fișier corupt (ex. după reinstalare) -> îl ștergem și pornim curat
            if (android.os.Build.VERSION.SDK_INT >= 24) context.deleteSharedPreferences(FILE)
            else context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
            create()
        }
    }

    /** codul scooterului; null = necunoscut, "" = dispozitivul nu are cod */
    var pin: String?
        get() = prefs.getString("pin", null)
        set(v) = prefs.edit().apply { if (v == null) remove("pin") else putString("pin", v) }.apply()

    /** adresa plăcii asociate - aplicația se conectează automat doar la ea */
    var deviceAddress: String?
        get() = prefs.getString("addr", null)
        set(v) = prefs.edit().apply { if (v == null) remove("addr") else putString("addr", v) }.apply()

    var recoveryEmail: String?
        get() = prefs.getString("email", null)
        set(v) = prefs.edit().apply { if (v.isNullOrBlank()) remove("email") else putString("email", v.trim()) }.apply()

    var smtpUser: String?
        get() = prefs.getString("smtp_user", null)
        set(v) = prefs.edit().apply { if (v.isNullOrBlank()) remove("smtp_user") else putString("smtp_user", v.trim()) }.apply()

    var smtpPass: String?
        get() = prefs.getString("smtp_pass", null)
        set(v) = prefs.edit().apply { if (v.isNullOrBlank()) remove("smtp_pass") else putString("smtp_pass", v.replace(" ", "")) }.apply()

    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    val smtpConfigured: Boolean
        get() = !smtpUser.isNullOrBlank() && !smtpPass.isNullOrBlank()

    companion object { private const val FILE = "ewb_secure" }
}
