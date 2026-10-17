package cn.rolechat.app.speech

import cn.rolechat.app.network.SiliconFlowGateway
import cn.rolechat.core.DEFAULT_DIALOGUE_EXTRACTION_PROMPT
import cn.rolechat.core.DialogueExtractionSettings
import cn.rolechat.core.ModelConfig
import cn.rolechat.core.Provider
import cn.rolechat.core.ThinkingMode
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/**
 * The JSON content below was sampled from three authorized requests on 2026-10-17.
 * These tests only replay those recorded contents over a loopback MockWebServer;
 * they never contact SiliconFlow and make no assertion about future model wording.
 * Reconstructed SSE events split the recorded JSON into pieces, and one-byte HTTP
 * chunks also split Chinese UTF-8 sequences before the real gateway decodes them.
 */
class DialogueExtractionRecordedReplayTest {
    private data class Fixture(val source: String, val output: String)

    private val quoted = Fixture(
        source = "（小萱轻轻放下杯子，抬眼看向你，声音放得很轻。）“你终于回来了。今天过得怎么样？”她笑了笑，又补充道：“先坐下休息一会儿吧。”",
        output = """{"text":"你终于回来了。今天过得怎么样？先坐下休息一会儿吧。","instruction":"请用轻柔、温和的语气，放慢语速，带着关切和欢迎的情绪读出这段话。"}"""
    )
    private val natural = Fixture(
        source = "今天辛苦啦。要不要先吃点东西？我可以陪你聊一会儿，你慢慢说就好。",
        output = """{"text":"今天辛苦啦。要不要先吃点东西？我可以陪你聊一会儿，你慢慢说就好。","instruction":"请用温柔、关切的语气，语速稍慢，带着安抚和陪伴的感觉读出这段话。"}"""
    )
    private val narration = Fixture(
        source = "夜色渐深，窗外的雨点落在屋檐上。小萱放下书，安静地望着窗外，没有开口。",
        output = """{"text":"","instruction":""}"""
    )

    @Test fun `recorded quoted dialogue survives UTF8 transport and keeps actions out of speech`() = runBlocking {
        val result = replay(quoted)
        assertTrue(result.text.isNotBlank())
        assertFalse(result.text.contains("放下杯子"))
        assertFalse(result.text.contains("她笑了笑"))
        assertTrue(result.instruction.isNotBlank())
    }

    @Test fun `recorded natural chat survives UTF8 transport without requiring quotation marks`() = runBlocking {
        val result = replay(natural)
        assertEquals(natural.source, result.text)
        assertTrue(result.instruction.isNotBlank())
    }

    @Test fun `recorded narration is a successful empty extraction rather than a transport failure`() = runBlocking {
        assertEquals(PreparedDialogue("", ""), replay(narration))
    }

    private suspend fun replay(fixture: Fixture): PreparedDialogue = withTimeout(15_000) {
        val transport = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val chunks = fixture.output.chunked(7)
                val body = buildString {
                    chunks.forEach { chunk -> append("data: ").append(event(chunk, null)).append("\n\n") }
                    append("data: ").append(event(null, "stop")).append("\n\n")
                    append("data: [DONE]\n\n")
                }
                server.enqueue(MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                    .setChunkedBody(body, 1))
                val provider = Provider(id = "recorded-extraction", baseUrl = server.url("/v1")
                    .newBuilder().host("127.0.0.1").build().toString())
                val settings = DialogueExtractionSettings(enabled = true,
                    model = ModelConfig(providerId = provider.id, model = "deepseek-ai/DeepSeek-V4-Flash",
                        thinking = ThinkingMode.OFF, temperature = 0.0, maxTokens = 2048),
                    systemPrompt = DEFAULT_DIALOGUE_EXTRACTION_PROMPT)
                val input = Channel<String>(Channel.UNLIMITED)
                fixture.source.chunked(5).forEach { input.trySend(it).getOrThrow() }
                input.close()
                val updates = mutableListOf<Pair<String, String>>()
                var starts = 0
                var consumed = 0
                val result = DialogueExtractor().extract(input, settings,
                    DialogueExtractionSnapshot(provider, SiliconFlowGateway({ "local-replay-only" }, transport)),
                    onConsumed = { consumed += it }, onExtractionStarted = { starts++ },
                    onOutput = { raw, reasoning -> updates += raw to reasoning })

                // Extraction returns only after the actual gateway has observed finish_reason=stop.
                val expected = Json.parseToJsonElement(fixture.output).jsonObject
                assertEquals(expected.getValue("text").jsonPrimitive.content, result.text)
                assertEquals(expected.getValue("instruction").jsonPrimitive.content, result.instruction)
                assertEquals(1, starts)
                assertEquals(fixture.source.length, consumed)
                assertEquals(chunks.size, updates.size)
                assertEquals(fixture.output to "", updates.last())
                assertEquals(chunks.indices.map { index -> chunks.take(index + 1).joinToString("") },
                    updates.map { it.first })
                assertTrue(updates.all { it.second.isEmpty() })

                val recorded = server.takeRequest(5, TimeUnit.SECONDS)
                assertNotNull(recorded)
                assertEquals("/v1/chat/completions", recorded!!.path)
                val request = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
                assertEquals(settings.model.model, request.getValue("model").jsonPrimitive.content)
                assertTrue(request.getValue("stream").jsonPrimitive.boolean)
                assertFalse(request.getValue("enable_thinking").jsonPrimitive.boolean)
                assertEquals(2048, request.getValue("max_tokens").jsonPrimitive.int)
                assertEquals(0.0, request.getValue("temperature").jsonPrimitive.double, 0.0)
                val messages = request.getValue("messages").jsonArray
                assertEquals(2, messages.size)
                assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
                assertEquals(DEFAULT_DIALOGUE_EXTRACTION_PROMPT, messages[0].jsonObject.getValue("content").jsonPrimitive.content)
                assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
                assertEquals(fixture.source, messages[1].jsonObject.getValue("content").jsonPrimitive.content)
                assertFalse(request.containsKey("prefix"))
                assertEquals(1, server.requestCount)
                result
            }
        } finally {
            transport.dispatcher.executorService.shutdown()
            transport.connectionPool.evictAll()
        }
    }

    private fun event(content: String?, finish: String?): String = buildJsonObject {
        putJsonArray("choices") {
            add(buildJsonObject {
                put("index", 0)
                putJsonObject("delta") { if (content != null) put("content", content) }
                if (finish == null) put("finish_reason", JsonNull) else put("finish_reason", finish)
            })
        }
    }.toString()
}
