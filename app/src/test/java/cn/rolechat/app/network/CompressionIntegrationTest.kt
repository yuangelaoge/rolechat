package cn.rolechat.app.network

import cn.rolechat.core.*
import cn.rolechat.core.Character
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Local HTTP fixtures exercise the real SSE adapter; these tests never call a paid model. */
class CompressionIntegrationTest {
    private fun response(text: String, finishReason: String = "stop"): MockResponse {
        val chunks = text.codePoints().toArray().map { String(java.lang.Character.toChars(it)) }.chunked(3).joinToString("") { characters ->
            val part = characters.joinToString("")
            "data: " + buildJsonObject { putJsonArray("choices") { addJsonObject {
                putJsonObject("delta") { put("content", part) }
            } } } + "\n\n"
        }
        val end = buildJsonObject { putJsonArray("choices") { addJsonObject {
            putJsonObject("delta") {}; put("finish_reason", finishReason)
        } } }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"PRIVATE_THINKING\"}}]}\n\n" + chunks +
                "data: $end\n\ndata: [DONE]\n\n", 1)
    }

    @Test fun completedUtf8StreamPreservesNewSummaryAndSendsOnlyIncrementalVisibleMaterial() = runBlocking {
        MockWebServer().use { server ->
            val output = "他依然用轻松的口吻答应赴约：‘明早见。’🌙"
            server.enqueue(response(output))
            server.start()
            val provider = Provider(id = "local", baseUrl = server.url("/v1").toString(), prefixMode = PrefixMode.ASSISTANT_FLAG)
            val settings = CompressionSettings(keepRecentMessages = 1,
                model = ModelConfig(providerId = provider.id, model = "summary-model", thinking = ThinkingMode.ON))
            val actor = Character(id = "actor", name = "林间", prompt = "PRIVATE_CHARACTER")
            val room = ChatRoom(id = "room", members = listOf(Member(actor.id, 0, true)), compression = settings)
            val initial = AppState(providers = listOf(provider), characters = listOf(actor), rooms = listOf(room), messages = listOf(
                ChatMessage(id = "first", roomId = room.id, text = "ARCHIVED_ORIGINAL_TEXT"),
                ChatMessage(id = "second", roomId = room.id, characterId = actor.id, senderName = actor.name,
                    text = "明早见。", segments = listOf(GenerationSegment(reasoning = "PRIVATE_CHAT_COT")))))
            val firstPlans = buildCompressionPlans(initial, room.id, settings)
            val archived = applyCompressionDrafts(initial, settings, firstPlans, mapOf(actor.id to "他们约好稍后见面。"))
            val current = archived.copy(messages = archived.messages + ChatMessage(id = "third", roomId = room.id, text = "好呀，晚安。"))
            val plan = buildCompressionPlans(current, room.id, settings).single()
            val result = ContextSummaryGenerator(SiliconFlowGateway(keyFor = { "local-fixture" })).generate(plan, settings, provider)
            assertEquals(output, result)
            assertEquals(archived.contextSummaries, current.contextSummaries) // Generation alone cannot commit anything.
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(body["enable_thinking"]!!.jsonPrimitive.boolean)
            assertNull(body["prefix"])
            val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            val input = messages[1]["content"]!!.jsonPrimitive.content
            for (private in listOf("PRIVATE_CHARACTER", "PRIVATE_CHAT_COT", "ARCHIVED_ORIGINAL_TEXT")) assertFalse(input.contains(private))
            val payload = Json.parseToJsonElement(input).jsonObject
            assertEquals("他们约好稍后见面。", payload["previous_summary"]!!.jsonPrimitive.content)
            assertEquals("second", payload["source_messages"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("third", payload["recent_messages_for_style"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            val applied = applyCompressionDrafts(current, settings, listOf(plan), mapOf(actor.id to result))
            assertEquals(output, applied.contextSummaries.single().text)
            assertEquals(current.messages, applied.messages)
            assertEquals(listOf("first", "second"), applied.contextSummaries.single().sourceMessageIds)
        }
    }

    @Test fun realSseLengthFinishRejectsPartialSummaryAndLeavesTheSnapshotUntouched() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("正文被截断", "length"))
            server.start()
            val provider = Provider(id = "local", baseUrl = server.url("/v1").toString())
            val settings = CompressionSettings(keepRecentMessages = 1, model = ModelConfig(providerId = provider.id))
            val room = ChatRoom(id = "room", members = listOf(Member("lin", 0, true)))
            val state = AppState(providers = listOf(provider), rooms = listOf(room), messages = listOf(
                ChatMessage(id = "first", roomId = room.id, text = "旧正文"),
                ChatMessage(id = "recent", roomId = room.id, text = "近期正文")))
            val plan = buildCompressionPlans(state, room.id, settings).single()
            val failure = runCatching { ContextSummaryGenerator(SiliconFlowGateway(keyFor = { "local-fixture" })).generate(plan, settings, provider) }.exceptionOrNull()
            assertTrue(failure is ContextSummaryFailure)
            assertFalse(failure!!.message!!.contains("正文被截断"))
            assertTrue(state.contextSummaries.isEmpty())
        }
    }
}
