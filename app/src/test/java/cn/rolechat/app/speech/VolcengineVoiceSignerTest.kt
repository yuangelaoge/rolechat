package cn.rolechat.app.speech

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class VolcengineVoiceSignerTest {
    @Test fun signatureMatchesOfficialPythonSdkFixedVector() {
        // Produced by executing official SignerV4.sign_only with these inputs:
        // https://github.com/volcengine/volc-sdk-python/blob/f0da6b2f65ad2b1a299c86edc5e9de8a57588c1f/volcengine/auth/SignerV4.py
        val headers = VolcengineVoiceSigner.headers(
            "https://open.volcengineapi.com/?Action=ListSpeakers&Version=2025-05-20".toHttpUrl(),
            """{"ResourceIDs":["seed-tts-1.0"],"Page":1,"Limit":30}""",
            "AKLTUNITTEST", "unit-test-secret", Instant.parse("2026-10-17T12:00:00Z")
        )
        assertEquals("20261017T120000Z", headers["X-Date"])
        assertEquals("open.volcengineapi.com", headers["Host"])
        assertEquals("b9ab9ca2547fa7d76644c92a037497bdac28e0c5965d756ca0383d218a7ea3bc", headers["X-Content-Sha256"])
        assertEquals("HMAC-SHA256 Credential=AKLTUNITTEST/20261017/cn-beijing/speech_saas_prod/request, " +
            "SignedHeaders=content-type;host;x-content-sha256;x-date, " +
            "Signature=ddf4b1865c750e7d8e6423f731fae5836def3445fd79dfbec6a5fab524a5d14d", headers["Authorization"])
    }
}
