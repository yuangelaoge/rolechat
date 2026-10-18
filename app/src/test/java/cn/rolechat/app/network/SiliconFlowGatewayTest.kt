package cn.rolechat.app.network

import cn.rolechat.core.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SiliconFlowGatewayTest {
    private fun request(provider: Provider = Provider(id = "test"), prefix: String = "") = GenerationRequest(
        ModelConfig(providerId = "test", model = "any-model", thinking = ThinkingMode.ON), provider,
        listOf(PromptMessage("system", "角色系统"), PromptMessage("user", "你好")), prefix
    )
    @Test fun consecutiveNamedAssistantMessagesStaySeparateAndPrefixStaysVerbatim() {
        val history = listOf(PromptMessage("system", "只扮演小萱"), PromptMessage("user", "【林川】你们讨论一下"),
            PromptMessage("assistant", "【小萱】我觉得可以"), PromptMessage("assistant", "【沈禾】我有不同意见"),
            PromptMessage("assistant", "【场景】第二天早晨"))
        for (mode in PrefixMode.entries) {
            val body = SiliconFlowGateway.requestJson(request(Provider(prefixMode = mode), "我想到").copy(messages = history))
            val sent = body["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(history.map { it.role }, sent.take(history.size).map { it["role"]!!.jsonPrimitive.content })
            assertEquals(history.map { it.content }, sent.take(history.size).map { it["content"]!!.jsonPrimitive.content })
            if (mode == PrefixMode.SILICONFLOW) {
                assertEquals(history.size, sent.size)
                assertEquals("我想到", body["prefix"]!!.jsonPrimitive.content)
            } else {
                assertEquals(history.size + 1, sent.size)
                assertEquals("assistant", sent.last()["role"]!!.jsonPrimitive.content)
                assertEquals("我想到", sent.last()["content"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun siliconFlowPrefixIsTopLevelWithoutDuplicateAssistant() {
        val body = SiliconFlowGateway.requestJson(request(prefix = "没写完的"))
        assertEquals("没写完的", body["prefix"]!!.jsonPrimitive.content)
        assertEquals(2, body["messages"]!!.jsonArray.size)
        assertTrue(body["enable_thinking"]!!.jsonPrimitive.boolean)
    }
    @Test fun alternatePrefixProtocolsAreExplicit() {
        val body = SiliconFlowGateway.requestJson(request(Provider(prefixMode = PrefixMode.ASSISTANT_FLAG), "前缀"))
        assertNull(body["prefix"])
        val last = body["messages"]!!.jsonArray.last().jsonObject
        assertEquals("assistant", last["role"]!!.jsonPrimitive.content)
        assertTrue(last["prefix"]!!.jsonPrimitive.boolean)
        val plain = SiliconFlowGateway.requestJson(request(Provider(prefixMode = PrefixMode.ASSISTANT), "前缀"))
        assertNull(plain["messages"]!!.jsonArray.last().jsonObject["prefix"])
    }
    @Test fun missingPrefixDoesNotCreateEmptyAssistant() {
        val body = SiliconFlowGateway.requestJson(request())
        assertNull(body["prefix"])
        assertEquals(2, body["messages"]!!.jsonArray.size)
    }
    @Test fun fragmentedSseSeparatesThinkingFromText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(
                ": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"接口返回的思考片段\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好🙂\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", 3))
            server.start()
            val gateway = SiliconFlowGateway(keyFor = { "test-secret" })
            val events = gateway.stream(request(Provider(id = "test", baseUrl = server.url("/v1").toString()))).toList()
            assertEquals(listOf(StreamEvent.Reasoning("接口返回的思考片段"), StreamEvent.Text("你好🙂"), StreamEvent.Finished()), events)
            assertEquals("Bearer test-secret", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun reasoningDeltasKeepWhitespaceAndStaySeparateWhenContentSharesAnEvent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先看天气。\\n\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"再确定路线。\",\"content\":\"可以去河边\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":null,\"content\":\"散步。\"}}]}\n\n" +
                "data: [DONE]\n\n", 5))
            server.start()
            val events = SiliconFlowGateway(keyFor = { "test" })
                .stream(request(Provider(baseUrl = server.url("/v1").toString()))).toList()
            assertEquals(listOf(StreamEvent.Reasoning("先看天气。\n"), StreamEvent.Reasoning("再确定路线。"),
                StreamEvent.Text("可以去河边"), StreamEvent.Text("散步。")), events)
        }
    }
    @Test fun missingOrEmptyReasoningDoesNotInventThinkingContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"\",\"content\":\"直接回答\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"。\"},\"finish_reason\":\"stop\"}]}\n\n"))
            server.start()
            val events = SiliconFlowGateway(keyFor = { "test" })
                .stream(request(Provider(baseUrl = server.url("/v1").toString()))).toList()
            assertEquals(listOf(StreamEvent.Text("直接回答"), StreamEvent.Text("。"), StreamEvent.Finished()), events)
        }
    }
    @Test fun serverRejectionIsVisibleAndDoesNotLeakKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"message\":\"Prefix is not supported. sk-example-secret123\"}")); server.start()
            val gateway = SiliconFlowGateway(keyFor = { "sk-example-secret123" })
            val error = runCatching { gateway.stream(request(Provider(baseUrl = server.url("/v1").toString()), "前缀")).toList() }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("Prefix is not supported"))
            assertFalse(error.message!!.contains("sk-example-secret123"))
        }
    }
    @Test fun prematureEofDoesNotMasqueradeAsSuccessfulCompletion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n")); server.start()
            val error = runCatching { SiliconFlowGateway(keyFor = { "test" }).stream(request(Provider(baseUrl = server.url("/v1").toString()))).toList() }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("提前断开"))
        }
    }
}
