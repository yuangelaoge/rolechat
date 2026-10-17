package cn.rolechat.app.network

import cn.rolechat.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SiliconFlowGateway(
    private val keyFor: (Provider) -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()
) : ChatGateway {
    override fun stream(request: GenerationRequest): Flow<StreamEvent> = callbackFlow {
        val key = keyFor(request.provider).trim()
        require(key.isNotBlank()) { "请先在设置中填写「${request.provider.name}」的 API Key" }
        val call = client.newCall(Request.Builder()
            .url(request.provider.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(requestJson(request).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                if (!isActive) { response.close(); return }
                val readerJob = launch(Dispatchers.IO) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException(httpError(it.code, it.peekBody(16_384).string(), key))
                            val reader = it.body?.charStream()?.buffered() ?: throw IOException("接口没有返回内容")
                            var ended = false
                            val eventData = StringBuilder()
                            suspend fun dispatch(): Boolean {
                                if (eventData.isEmpty()) return false
                                val data = eventData.toString().trim(); eventData.setLength(0)
                                if (data == "[DONE]") { ended = true; return true }
                                val obj = Json.parseToJsonElement(data).jsonObject
                                obj["error"]?.takeUnless { it is JsonNull }?.let { error -> throw IOException(sanitize(error.toString(), key)) }
                                for (choice in obj["choices"]?.jsonArray.orEmpty()) {
                                    val c = choice.jsonObject
                                    val delta = c["delta"] as? JsonObject
                                    (delta?.get("reasoning_content") as? JsonPrimitive)?.takeIf { it.isString }
                                        ?.content?.takeIf(String::isNotEmpty)?.let { reasoning -> send(StreamEvent.Reasoning(reasoning)) }
                                    delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let { text -> send(StreamEvent.Text(text)) }
                                    c["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                                        ended = true
                                        send(StreamEvent.Finished(reason))
                                    }
                                }
                                return ended
                            }
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) { if (dispatch()) break }
                                else if (line.startsWith("data:")) {
                                    if (eventData.isNotEmpty()) eventData.append('\n')
                                    eventData.append(line.substring(5).trimStart())
                                }
                            }
                            dispatch()
                            if (!ended) throw IOException("连接提前断开，已保留收到的文字")
                        }
                        close()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { close(e) }
                }
                // Cancellation can occur between onResponse and dispatching the IO coroutine.
                // The completion hook also closes responses whose body reader never starts.
                readerJob.invokeOnCompletion { response.close() }
            }
        })
        awaitClose { call.cancel() }
    }

    suspend fun listModels(provider: Provider): List<String> = suspendCancellableCoroutine { continuation ->
        val key = keyFor(provider).trim()
        require(key.isNotBlank()) { "请先保存 API Key" }
        val call = client.newCall(Request.Builder().url(provider.baseUrl.trimEnd('/') + "/models")
            .header("Authorization", "Bearer $key").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val models = response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw IOException(httpError(it.code, it.peekBody(16_384).string(), key))
                        val source = it.body?.source() ?: throw IOException("接口没有返回模型列表")
                        source.request(5L * 1024 * 1024 + 1)
                        require(source.buffer.size <= 5L * 1024 * 1024) { "接口返回的模型列表过大" }
                        Json.parseToJsonElement(source.readUtf8()).jsonObject["data"]?.jsonArray.orEmpty()
                            .mapNotNull { item -> item.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.distinct().sorted()
                    }
                    continuation.resume(models)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }

    companion object {
        fun requestJson(request: GenerationRequest): JsonObject = buildJsonObject {
            put("model", request.model.model)
            put("stream", true)
            put("max_tokens", request.model.maxTokens.coerceIn(1, 32768))
            put("temperature", request.model.temperature.coerceIn(0.0, 2.0))
            when (request.model.thinking) {
                ThinkingMode.ON -> put("enable_thinking", true)
                ThinkingMode.OFF -> put("enable_thinking", false)
                ThinkingMode.DEFAULT -> Unit
            }
            putJsonArray("messages") {
                request.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role); put("content", message.content)
                }) }
                if (request.prefix.isNotEmpty() && request.provider.prefixMode != PrefixMode.SILICONFLOW) {
                    add(buildJsonObject {
                        put("role", "assistant"); put("content", request.prefix)
                        if (request.provider.prefixMode == PrefixMode.ASSISTANT_FLAG) put("prefix", true)
                    })
                }
            }
            if (request.prefix.isNotEmpty() && request.provider.prefixMode == PrefixMode.SILICONFLOW) put("prefix", request.prefix)
        }
        fun sanitize(text: String, key: String = ""): String {
            val safe = if (key.isNotEmpty()) text.replace(key, "[已隐藏]") else text
            return safe.replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[已隐藏]").take(500)
        }
        private fun httpError(code: Int, body: String, key: String): String {
            val detail = runCatching {
                val json = Json.parseToJsonElement(body).jsonObject
                json["message"]?.jsonPrimitive?.contentOrNull
                    ?: json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: body
            }.getOrDefault(body)
            return "接口返回 $code：${sanitize(detail, key)}"
        }
    }
}
