package cn.rolechat.app.speech

import cn.rolechat.core.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SiliconFlow's /audio/speech accepts complete text and streams raw PCM output.
 * Output chunks contain whole 16-bit samples at 24 kHz; cancellation aborts the socket read.
 * See https://api-docs.siliconflow.cn/docs/api/audio-speech-post .
 */
class SiliconFlowSpeechClient(private val client: OkHttpClient = OkHttpClient()) {
    // Keep caller-supplied shorter limits, but never inherit an unlimited transport timeout.
    // Apply redirect policy even when a caller injects its own client.
    private val transport = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(bounded(client.connectTimeoutMillis.toLong(), 20_000), TimeUnit.MILLISECONDS)
        .readTimeout(bounded(client.readTimeoutMillis.toLong(), 30_000), TimeUnit.MILLISECONDS)
        .writeTimeout(bounded(client.writeTimeoutMillis.toLong(), 30_000), TimeUnit.MILLISECONDS)
        .callTimeout(bounded(client.callTimeoutMillis.toLong(), 120_000), TimeUnit.MILLISECONDS)
        .build()

    fun stream(provider: Provider, apiKey: String, model: String, text: String, voice: String,
               speed: Double = 1.0): Flow<ByteArray> = callbackFlow {
        require(apiKey.isNotBlank()) { "请先配置所选模型服务的 API 密钥" }
        require(apiKey.trim().all { it in ' '..'~' }) { "API 密钥中包含无效字符，请重新填写" }
        require(model.isNotBlank()) { "请填写语音模型名称" }
        require(text.isNotBlank()) { "没有可合成的正文" }
        require(voice.isNotBlank()) { "请填写音色名称" }
        require(speed.isFinite() && speed in 0.25..4.0) { "语速需要在 0.25 到 4.0 之间" }
        val key = apiKey.trim()
        val body = buildJsonObject {
            put("model", model.trim())
            put("input", text)
            put("voice", resolvedVoice(model, voice))
            put("response_format", "pcm")
            put("sample_rate", 24_000)
            put("speed", speed)
            put("stream", true)
        }
        val call = transport.newCall(Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/audio/speech")
            .header("Authorization", "Bearer $key")
            .header("Accept", "audio/pcm, application/octet-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build())
        val openBody = AtomicReference<ResponseBody?>(null)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(IOException("语音连接失败：${sanitize(e.message.orEmpty(), key)}"))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body
                openBody.set(responseBody)
                if (!isActive) {
                    response.close()
                    openBody.compareAndSet(responseBody, null)
                    return
                }
                // awaitClose remains independently cancellable while this child blocks in read().
                // Its handler cancels Call and closes body, rather than waiting for read timeout.
                launch(Dispatchers.IO) {
                    try {
                        response.use {
                            val audio = responseBody ?: throw IOException("语音接口返回 HTTP ${response.code}：响应为空")
                            if (!response.isSuccessful) throw IOException(
                                "语音接口返回 HTTP ${response.code}：${errorDetail(readErrorPrefix(audio), key)}")
                            val type = audio.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty().lowercase()
                            if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
                                throw IOException("语音接口返回 HTTP ${response.code}：返回文本而非 PCM 音频；${errorDetail(readErrorPrefix(audio), key)}")
                            }
                            if (type.isNotEmpty() && type !in pcmContentTypes) {
                                throw IOException("语音接口返回 HTTP ${response.code}：响应格式不是 PCM（${sanitize(type, key)}）")
                            }
                            if (audio.contentLength() == 0L) throw IOException("语音接口返回 HTTP ${response.code}：PCM 音频为空")
                            if (audio.contentLength() > 0 && audio.contentLength() % 2L != 0L) {
                                throw IOException("语音接口返回 HTTP ${response.code}：PCM 数据以半个采样结束")
                            }
                            val source = audio.source()
                            val buffer = ByteArray(4_096)
                            val prefix = ByteArrayOutputStream()
                            var checkedPrefix = false
                            var pendingByte: Byte? = null
                            var totalBytes = 0L

                            suspend fun emitSamples(bytes: ByteArray) {
                                val combined = if (pendingByte == null) bytes else byteArrayOf(pendingByte!!) + bytes
                                val evenLength = combined.size - combined.size % 2
                                pendingByte = if (evenLength == combined.size) null else combined.last()
                                if (evenLength > 0) {
                                    send(combined.copyOf(evenLength))
                                    totalBytes += evenLength
                                }
                            }
                            while (isActive) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                val bytes = buffer.copyOf(count)
                                if (checkedPrefix) {
                                    emitSamples(bytes)
                                } else {
                                    prefix.write(bytes)
                                    val candidate = prefix.toByteArray()
                                    when (inspectPrefix(candidate, eof = false)) {
                                        PrefixKind.WAIT -> Unit
                                        PrefixKind.PCM -> { checkedPrefix = true; emitSamples(candidate); prefix.reset() }
                                        PrefixKind.TEXT -> throw IOException("语音接口返回 HTTP ${response.code}：返回 JSON/HTML 或文本而非 PCM 音频")
                                        PrefixKind.ENCODED_AUDIO -> throw IOException("语音接口返回 HTTP ${response.code}：返回了带容器的音频，预期原始 PCM")
                                    }
                                }
                            }
                            if (!isActive) throw CancellationException("Speech stream cancelled")
                            if (!checkedPrefix && prefix.size() > 0) {
                                when (inspectPrefix(prefix.toByteArray(), eof = true)) {
                                    PrefixKind.TEXT -> throw IOException("语音接口返回 HTTP ${response.code}：返回 JSON/HTML 或文本而非 PCM 音频")
                                    PrefixKind.ENCODED_AUDIO -> throw IOException("语音接口返回 HTTP ${response.code}：响应不是原始 PCM")
                                    else -> emitSamples(prefix.toByteArray())
                                }
                            }
                            if (pendingByte != null) throw IOException("语音接口返回 HTTP ${response.code}：PCM 数据以半个采样结束")
                            if (totalBytes == 0L) throw IOException("语音接口返回 HTTP ${response.code}：PCM 音频为空")
                        }
                        close()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val detail = sanitize(failure.message ?: "语音流读取失败", key)
                        close(IOException(if (detail.startsWith("语音接口返回 HTTP")) detail else "语音接口返回 HTTP ${response.code}：$detail"))
                    } finally {
                        openBody.compareAndSet(responseBody, null)
                        runCatching { response.close() }
                    }
                }
            }
        })
        awaitClose {
            call.cancel()
            runCatching { openBody.getAndSet(null)?.close() }
        }
    }

    private enum class PrefixKind { WAIT, PCM, TEXT, ENCODED_AUDIO }

    companion object {
        private val pcmContentTypes = setOf("audio/pcm", "audio/x-pcm", "audio/raw", "audio/x-raw", "application/audio", "application/octet-stream", "binary/octet-stream")
        private val encodedHeaders = listOf("RIFF", "OggS", "fLaC", "ID3")
        private fun bounded(value: Long, maximum: Long) = if (value <= 0) maximum else minOf(value, maximum)

        internal fun resolvedVoice(model: String, voice: String): String =
            voice.trim().let { if (':' in it) it else "${model.trim()}:$it" }

        /** Bounded prefix sniffing happens before the first audio byte can reach playback. */
        private fun inspectPrefix(bytes: ByteArray, eof: Boolean): PrefixKind {
            if (bytes.isEmpty()) return PrefixKind.WAIT
            val limited = bytes.copyOf(minOf(bytes.size, 128))
            val raw = limited.toString(Charsets.ISO_8859_1)
            if (encodedHeaders.any { raw.startsWith(it) }) return PrefixKind.ENCODED_AUDIO
            if (!eof && encodedHeaders.any { it.startsWith(raw) }) return PrefixKind.WAIT
            // A PCM sample can equal the bytes for '{"'. Inspect a bounded *textual*
            // prefix, allowing later binary bytes to disambiguate it before rejecting.
            // Incremental decoding also avoids treating a split Chinese character as PCM.
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(limited)
            val output = CharBuffer.allocate(128)
            val result = decoder.decode(input, output, eof && bytes.size <= 128)
            if (result.isError) return PrefixKind.PCM
            output.flip()
            val decoded = output.toString()
            if (decoded.any { (it < ' ' && it !in "\r\n\t") || it in '\u007f'..'\u009f' }) return PrefixKind.PCM
            if (input.hasRemaining() && !eof && bytes.size < 128) return PrefixKind.WAIT
            val value = decoded.removePrefix("\uFEFF").trimStart(' ', '\t', '\r', '\n')
            if (value.isEmpty()) return if (eof || bytes.size >= 128) PrefixKind.TEXT else PrefixKind.WAIT
            if (eof) {
                val structured = value.first() in "{[\"<" || value.startsWith("data:") || value.startsWith("event:") ||
                    runCatching { Json.parseToJsonElement(value) }.isSuccess
                return if (structured) PrefixKind.TEXT else PrefixKind.PCM
            }
            return if (bytes.size >= 128) PrefixKind.TEXT else PrefixKind.WAIT
        }

        private fun readErrorPrefix(body: ResponseBody): String {
            val buffer = ByteArray(4_096)
            val count = body.source().read(buffer)
            return if (count <= 0) "" else buffer.copyOf(count).toString(Charsets.UTF_8)
        }

        private fun errorDetail(body: String, key: String): String {
            val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            val detail = when (parsed) {
                is JsonObject -> (parsed["message"] as? JsonPrimitive)?.contentOrNull
                    ?: (parsed["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: (parsed["error"] as? JsonPrimitive)?.contentOrNull ?: body
                is JsonPrimitive -> parsed.contentOrNull ?: body
                else -> body
            }
            return sanitize(detail.ifBlank { "服务未返回错误说明" }, key)
        }

        private fun sanitize(text: String, key: String): String {
            var safe = text.replace(key, "[已隐藏]")
                .replace(Regex("(?i)sk-[A-Za-z0-9_-]*"), "[已隐藏]")
                .replace(Regex("(?i)(Bearer\\s+)[^\\s\"'<>]+"), "$1[已隐藏]")
            // A bounded error read may end halfway through an echoed key.
            if (key.length >= 4) for (length in minOf(key.length - 1, safe.length) downTo 4) {
                if (safe.endsWith(key.take(length))) { safe = safe.dropLast(length) + "[已隐藏]"; break }
            }
            return safe.take(500)
        }
    }
}
