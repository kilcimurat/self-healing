package io.selfhealing.core.sentry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentrySignatureTest {
    @Test
    fun matchesKnownHmacVector() {
        // RFC 4231-style well-known vector: key "key", message "The quick brown fox jumps over the lazy dog"
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            SentrySignature.hmacHex("key", "The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun verifies() {
        val sig = SentrySignature.hmacHex("s3cret", "{\"a\":1}")
        assertTrue(SentrySignature.verify("s3cret", "{\"a\":1}", sig))
        assertTrue(SentrySignature.verify("s3cret", "{\"a\":1}", sig.uppercase()))
        assertFalse(SentrySignature.verify("s3cret", "{\"a\":2}", sig))
        assertFalse(SentrySignature.verify("other", "{\"a\":1}", sig))
        assertFalse(SentrySignature.verify("s3cret", "{\"a\":1}", null))
        assertTrue(SentrySignature.verifyAny(listOf("x", "s3cret"), "{\"a\":1}", sig))
        assertFalse(SentrySignature.verifyAny(emptyList(), "{\"a\":1}", sig))
    }
}
