package cn.rolechat.app.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * System TTS voices from the official IAM-signed ListSpeakers API, not voice cloning.
 * https://docs.volcengine.com/docs/DoubaoVoice/ListSpeakers-LargeModelSoundListNewInterface
 * This API needs an Access Key ID / Secret Access Key pair, not a speech API Key.
 */
class DoubaoVoiceClient(
    client: OkHttpClient = OkHttpClient(),
    endpoint: String = "https://open.volcengineapi.com/",
    private val now: () -> Instant = Instant::now,
) {
    // Endpoint injection is for local transport tests. Credentials may never be
    // redirected or sent to an arbitrary configured model-service address.
    private val url = endpoint.toHttpUrl().also {
        val official = it.scheme == "https" && it.host == "open.volcengineapi.com" && it.port == 443
        val loopback = it.host in setOf("127.0.0.1", "::1", "localhost")
        require((official || loopback) && it.username.isEmpty() && it.password.isEmpty() &&
            it.encodedPath == "/" && it.query == null && it.fragment == null) { "音色列表接口地址无效" }
    }.newBuilder().encodedQuery(VolcengineVoiceSigner.QUERY).build()

    private val transport = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(bounded(client.connectTimeoutMillis.toLong(), 20_000), TimeUnit.MILLISECONDS)
        .readTimeout(bounded(client.readTimeoutMillis.toLong(), 30_000), TimeUnit.MILLISECONDS)
        .writeTimeout(bounded(client.writeTimeoutMillis.toLong(), 20_000), TimeUnit.MILLISECONDS)
        .callTimeout(bounded(client.callTimeoutMillis.toLong(), 40_000), TimeUnit.MILLISECONDS)
        .build()

    /** Returns only when both resource lists are complete; callers can replace their cache atomically. */
    suspend fun listVoices(accessKey: String, secretKey: String): List<DoubaoVoice> {
        val ak = accessKey.trim()
        val sk = secretKey.trim()
        if (ak.isEmpty() || sk.isEmpty()) throw IOException("刷新音色需要火山引擎 Access Key ID 和 Secret Access Key")
        if (ak.length > 512 || sk.length > 512 || ak.any { it !in '!'..'~' } || sk.any { it !in '!'..'~' }) {
            throw IOException("火山引擎访问密钥格式无效，请重新填写")
        }
        return withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) {
            val voices = mutableListOf<DoubaoVoice>()
            var byteCount = 0L
            for (resource in RESOURCES) {
                val seen = mutableSetOf<String>()
                var total: Int? = null
                var finished = false
                for (page in 1..MAX_PAGES_PER_RESOURCE) {
                    coroutineContext.ensureActive()
                    val body = buildJsonObject {
                        put("ResourceIDs", buildJsonArray { add(JsonPrimitive(resource)) })
                        put("Page", page)
                        put("Limit", PAGE_SIZE)
                    }.toString()
                    val request = Request.Builder().url(url)
                        .headers(VolcengineVoiceSigner.headers(url, body, ak, sk, now()))
                        .post(body.toRequestBody(VolcengineVoiceSigner.CONTENT_TYPE.toMediaType())).build()
                    val bytes = fetchPage(request)
                    byteCount += bytes.size
                    if (byteCount > MAX_TOTAL_BYTES) throw IOException("音色列表总大小超过限制，请稍后重试")
                    val result = parsePage(bytes, resource)
                    if (total != null && total != result.total) throw IOException("音色列表分页发生变化，请重新刷新")
                    total = result.total
                    if (voices.size + result.voices.size > MAX_VOICES) throw IOException("音色列表数量超过限制")
                    for (voice in result.voices) {
                        if (!seen.add(voice.id)) throw IOException("音色列表出现重复分页，请重新刷新")
                        voices += voice
                    }
                    if (seen.size > result.total) throw IOException("音色列表分页数量不一致，请重新刷新")
                    if (seen.size == result.total) { finished = true; break }
                    if (result.voices.isEmpty()) throw IOException("音色列表分页不完整，请重新刷新")
                }
                if (!finished) throw IOException("音色列表页数超过限制，请稍后重试")
            }
            coroutineContext.ensureActive()
            if (voices.isEmpty()) throw IOException("官方音色列表为空，已保留原有音色，请稍后重试")
            voices.toList()
        } ?: throw IOException("音色列表刷新超时，请稍后重试")
    }

    // Keep the cancellation handler active for the complete body read, not just
    // until response headers arrive. Cancelling closes an active socket promptly.
    private suspend fun fetchPage(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        val openResponse = AtomicReference<Response?>(null)
        continuation.invokeOnCancellation {
            call.cancel()
            runCatching { openResponse.getAndSet(null)?.close() }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("音色列表连接失败或超时，请检查网络和访问密钥权限"))
            }

            override fun onResponse(call: Call, response: Response) {
                openResponse.set(response)
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!response.isSuccessful) throw IOException("音色列表接口返回 HTTP ${response.code}，请检查访问密钥权限和服务开通状态")
                        val body = response.body ?: throw IOException("音色列表响应为空")
                        if (body.contentLength() > MAX_PAGE_BYTES) throw IOException("音色列表单页大小超过限制")
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        val source = body.source()
                        while (continuation.isActive) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_PAGE_BYTES) throw IOException("音色列表单页大小超过限制")
                            output.write(buffer, 0, count)
                        }
                        if (continuation.isActive) continuation.resume(output.toByteArray())
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) {
                        // Only our own fixed messages are exposed; upstream exceptions may contain URLs or credentials.
                        val message = when {
                            failure.message?.startsWith("音色列表接口返回 HTTP ") == true -> "音色列表接口返回 HTTP ${response.code}，请检查访问密钥权限和服务开通状态"
                            failure.message == "音色列表单页大小超过限制" -> "音色列表单页大小超过限制"
                            else -> "音色列表响应读取失败或超时，请重新刷新"
                        }
                        continuation.resumeWithException(IOException(message))
                    }
                } finally {
                    openResponse.compareAndSet(response, null)
                    runCatching { response.close() }
                }
            }
        })
    }

    private data class Page(val total: Int, val voices: List<DoubaoVoice>)

    private fun parsePage(bytes: ByteArray, resource: String): Page {
        try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: invalidResponse()
            val metadata = root["ResponseMetadata"] as? JsonObject
            if (metadata?.get("Error")?.let { it != JsonNull } == true) {
                throw VoiceListResponseFailure("音色列表接口报告错误，请检查访问密钥权限和服务开通状态")
            }
            val result = root["Result"] as? JsonObject ?: invalidResponse()
            val totalField = result["Total"] as? JsonPrimitive ?: invalidResponse()
            val total = if (!totalField.isString) totalField.intOrNull else null
            if (total == null || total !in 0..MAX_VOICES) invalidResponse()
            val speakers = result["Speakers"] as? JsonArray ?: invalidResponse()
            if (speakers.size > PAGE_SIZE) invalidResponse()
            val voices = speakers.map { element ->
                val speaker = element as? JsonObject ?: invalidResponse()
                val id = speaker.string("VoiceType", 256)
                val name = speaker.string("Name", 256)
                val returnedResource = speaker.string("ResourceID", 128)
                if (returnedResource != resource) throw VoiceListResponseFailure("音色列表返回了不匹配的资源版本，请重新刷新")
                val description = when (val value = speaker["Description"]) {
                    null, JsonNull -> ""
                    is JsonPrimitive -> if (value.isString && value.content.length <= 8_192) value.content.take(2_000) else invalidResponse()
                    else -> invalidResponse()
                }
                val supportsBidirectional = DoubaoVoices.builtIn.none {
                    it.id == id && DoubaoVoices.resourceVersion(it.resourceId) == DoubaoVoices.resourceVersion(returnedResource) && !it.bidirectional
                }
                DoubaoVoice(id, name, returnedResource, description, supportsBidirectional)
            }
            return Page(total, voices)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VoiceListResponseFailure) {
            throw IOException(failure.message)
        } catch (_: Exception) {
            throw IOException("音色列表响应格式无效，请稍后重试")
        }
    }

    private fun JsonObject.string(key: String, maximum: Int): String {
        val value = get(key) as? JsonPrimitive ?: invalidResponse()
        if (!value.isString || value.content.isBlank() || value.content.length > maximum) invalidResponse()
        return value.content
    }

    private class VoiceListResponseFailure(message: String) : Exception(message)
    private fun invalidResponse(): Nothing = throw VoiceListResponseFailure("音色列表响应格式无效，请稍后重试")

    companion object {
        private val RESOURCES = listOf("seed-tts-1.0", "seed-tts-2.0")
        private const val PAGE_SIZE = 30
        private const val MAX_PAGES_PER_RESOURCE = 100
        private const val MAX_VOICES = 5_000
        private const val MAX_PAGE_BYTES = 1_048_576
        private const val MAX_TOTAL_BYTES = 8_388_608
        private const val REFRESH_TIMEOUT_MILLIS = 120_000L
        private fun bounded(value: Long, maximum: Long) = if (value <= 0) maximum else minOf(value, maximum)
    }
}
