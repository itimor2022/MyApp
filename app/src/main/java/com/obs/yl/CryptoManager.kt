package com.obs.yl

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val AES_ALG = "AES"
    private const val HMAC_ALG = "HmacSHA256"

    /**
     * 必须替换成你自己的 AES Key Base64
     * Base64 解码后必须是 16 / 24 / 32 字节
     */
    private const val AES_KEY_BASE64 =
        "0yydZmUdUn7rt/v8MDvM07NcfTYW+D8BiUVO6VGxeP4="

    /**
     * 必须替换成你自己的 HMAC 密钥
     */
    private const val HMAC_KEY =
        "79589a8f03146cca5fe5f266ce7b3a75cb11d4c79925316aace7f9d969629124"

    fun decryptAesCbc(base64Iv: String, base64CipherText: String): String {
        val keyBytes = Base64.decode(AES_KEY_BASE64, Base64.NO_WRAP)
        val ivBytes = Base64.decode(base64Iv, Base64.NO_WRAP)
        val cipherBytes = Base64.decode(base64CipherText, Base64.NO_WRAP)

        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "AES Key 长度必须是 16/24/32 字节"
        }

        require(ivBytes.size == 16) {
            "AES-CBC IV 长度必须是 16 字节"
        }

        val keySpec = SecretKeySpec(keyBytes, AES_ALG)
        val ivSpec = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, StandardCharsets.UTF_8)
    }

    fun hmacSha256(data: String, ts: Long): String {
        val mac = Mac.getInstance(HMAC_ALG)
        val keySpec = SecretKeySpec(HMAC_KEY.toByteArray(StandardCharsets.UTF_8), HMAC_ALG)
        mac.init(keySpec)
        val content = data + ts.toString()
        val result = mac.doFinal(content.toByteArray(StandardCharsets.UTF_8))
        return result.joinToString("") { "%02x".format(it) }
    }

    fun verifyHmac(data: String, ts: Long, sign: String): Boolean {
        if (sign.isBlank()) return false
        val local = hmacSha256(data, ts)
        return constantTimeEquals(local.lowercase(), sign.lowercase())
    }

    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}