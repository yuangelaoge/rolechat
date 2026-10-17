package cn.rolechat.app.speech

import okhttp3.Headers
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Volcengine TOP Signature V4, scoped to the public ListSpeakers action. */
internal object VolcengineVoiceSigner {
    const val CONTENT_TYPE = "application/json; charset=utf-8"
    const val QUERY = "Action=ListSpeakers&Version=2025-05-20"
    private const val SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    // Reference: volcengine/volc-sdk-python, auth/SignerV4.py; the unit test uses
    // a fixed vector produced by that SDK, rather than another copy of this signer.
    fun headers(url: HttpUrl, body: String, accessKey: String, secretKey: String, now: Instant): Headers {
        require(url.encodedPath == "/" && url.encodedQuery == QUERY) { "音色列表接口地址无效" }
        val date = dateFormat.format(now)
        val day = date.take(8)
        val hostName = if (':' in url.host) "[${url.host}]" else url.host
        val host = if (url.port == HttpUrl.defaultPort(url.scheme)) hostName else "$hostName:${url.port}"
        val bodyHash = sha256(body)
        val canonicalHeaders = "content-type:$CONTENT_TYPE\nhost:$host\nx-content-sha256:$bodyHash\nx-date:$date\n"
        val canonicalRequest = listOf("POST", "/", QUERY, canonicalHeaders, SIGNED_HEADERS, bodyHash).joinToString("\n")
        val scope = "$day/cn-beijing/speech_saas_prod/request"
        val stringToSign = "HMAC-SHA256\n$date\n$scope\n${sha256(canonicalRequest)}"
        val signingKey = hmac(hmac(hmac(hmac(secretKey.toByteArray(Charsets.UTF_8), day), "cn-beijing"), "speech_saas_prod"), "request")
        val signature = hex(hmac(signingKey, stringToSign))
        return Headers.Builder()
            .add("Content-Type", CONTENT_TYPE)
            .add("Host", host)
            .add("X-Date", date)
            .add("X-Content-Sha256", bodyHash)
            .add("Authorization", "HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$SIGNED_HEADERS, Signature=$signature")
            .build()
    }

    private fun sha256(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
