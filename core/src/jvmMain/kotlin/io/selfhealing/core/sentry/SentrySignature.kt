package io.selfhealing.core.sentry

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SentrySignature {
    /** Lowercase hex HMAC-SHA256 of [body] keyed with [secret] (Sentry service hook scheme). */
    fun hmacHex(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun verify(secret: String, body: String, signature: String?): Boolean {
        if (signature.isNullOrBlank() || secret.isEmpty()) return false
        val expected = hmacHex(secret, body).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, signature.trim().lowercase().toByteArray(Charsets.UTF_8))
    }

    fun verifyAny(secrets: Collection<String>, body: String, signature: String?): Boolean =
        secrets.any { verify(it, body, signature) }
}
