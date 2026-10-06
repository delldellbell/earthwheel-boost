package com.earthwheel.boost

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol securitate (identic cu firmware-ul ESP32):
 *  K     = HMAC-SHA256(APP_SECRET, "EWK1" || nonce || pin)
 *  proof = HMAC-SHA256(K, "AUTH")
 *  cmd   = [op][seq LE 4][payload][ HMAC(K, op||seq||payload)[0..15] ]
 *  pin nou criptat: pin XOR HMAC(K, "PIN"||seq)
 */
object Crypto {
    // Cheia unică (32 octeți) vine din assets/ew_key.bin, care NU e publicat pe GitHub.
    // Trebuie să fie identică cu APP_SECRET din firmware/EarthwheelBoost/secret.h.
    @Volatile
    private var appSecret: ByteArray? = null

    /** încarcă cheia; false dacă lipsește din APK */
    fun init(context: android.content.Context): Boolean {
        if (appSecret != null) return true
        return try {
            val k = context.assets.open("ew_key.bin").use { it.readBytes() }
            if (k.size == 32) { appSecret = k; true } else false
        } catch (e: Exception) {
            false
        }
    }

    private val APP_SECRET: ByteArray
        get() = appSecret ?: throw IllegalStateException("cheia aplicației lipsește")

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun sessionKey(nonce: ByteArray, pin: String): ByteArray =
        hmac(APP_SECRET, "EWK1".toByteArray(Charsets.US_ASCII), nonce, pin.toByteArray(Charsets.UTF_8))

    fun authProof(k: ByteArray): ByteArray = hmac(k, "AUTH".toByteArray(Charsets.US_ASCII))

    fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    fun buildCmd(k: ByteArray, op: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val opB = byteArrayOf(op.toByte())
        val s = le32(seq)
        val mac = hmac(k, opB, s, payload).copyOf(16)
        return opB + s + payload + mac
    }

    fun encryptPin(k: ByteArray, seq: Int, pin: String): ByteArray {
        val ks = hmac(k, "PIN".toByteArray(Charsets.US_ASCII), le32(seq))
        val p = pin.toByteArray(Charsets.US_ASCII)
        return ByteArray(p.size) { i -> (p[i].toInt() xor ks[i].toInt()).toByte() }
    }
}
