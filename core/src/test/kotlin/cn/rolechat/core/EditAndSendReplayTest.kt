package cn.rolechat.core

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditAndSendReplayTest {
    private val actor = Character(id = "a", name = "a", primary = ModelConfig(model = "a"), secondary = ModelConfig(model = "b"),
        dualModel = true, cutMin = 2, cutMax = 2)
    private val room = ChatRoom(id = "room", members = listOf(Member(actor.id, 100, false)))
    private class Gateway(private val respond: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow { requests += request; respond(request) }
    }

    @Test fun retryBAndRegenerationKeepTheExplicitResendAndItsOneMessageHistoryPermission() = runTest {
        val trigger = ChatMessage(id = "trigger", roomId = room.id, text = "编辑前旧问题", createdAt = 1)
        val hidden = ChatMessage(id = "hidden", roomId = room.id, text = "仍然不能读取的入群前历史", createdAt = 2)
        val later = ChatMessage(id = "later", roomId = room.id, text = "后面的旧消息不能抢走触发地位", createdAt = 200)
        val store = StateStore(AppState(characters = listOf(actor), rooms = listOf(room), messages = listOf(trigger, hidden, later)))
        var bAttempts = 0
        val gateway = Gateway { request ->
            if (request.model.model == "a") {
                emit(StreamEvent.Reasoning("A 思考"))
                emit(StreamEvent.Text("前缀舍弃"))
            } else {
                bAttempts++
                if (bAttempts == 1) throw IOException("临时失败")
                emit(StreamEvent.Text("续文"))
            }
        }
        val engine = ChatEngine(store, gateway, this, now = { 300 })
        assertTrue(engine.editAndSendMessage(trigger.id, "真正重发的新问题"))
        advanceUntilIdle()
        val failed = store.snapshot().messages.last()
        assertEquals(MessageStatus.ERROR, failed.status)
        assertEquals(trigger.id, failed.triggerMessageId)
        assertEquals(listOf(trigger.id), failed.contextMessageIds)
        val expected = gateway.requests.first().messages
        assertEquals(PromptMessage("user", "【本轮重新发送】\n【我】真正重发的新问题"), expected[1])
        assertFalse(expected.first().content.contains("真正重发的新问题"))
        assertEquals(expected, gateway.requests.last().messages)
        engine.retry(failed.id)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "b"), gateway.requests.map { it.model.model })
        assertEquals("前缀", gateway.requests.last().prefix)
        assertEquals(expected, gateway.requests.last().messages)
        assertEquals(trigger.id, store.snapshot().messages.last().triggerMessageId)

        engine.regenerate(failed.id)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "b", "a", "b"), gateway.requests.map { it.model.model })
        assertEquals(expected, gateway.requests.last().messages)
        assertEquals("前缀续文", store.snapshot().messages.last().text)
        assertEquals(failed.id, store.snapshot().messages.last().id)
        assertEquals(2, store.snapshot().messages.size)
        assertFalse(store.snapshot().messages.any { it.id == hidden.id || it.id == later.id })

        assertTrue(engine.editMessage(trigger.id, "再次修正的同一消息"))
        engine.regenerate(failed.id)
        advanceUntilIdle()
        val replay = gateway.requests.last().messages
        assertEquals(PromptMessage("user", "【本轮重新发送】\n【我】再次修正的同一消息"), replay[1])
        assertFalse(replay.any { it.content.contains("真正重发的新问题") || it.content.contains(hidden.text) })
        assertTrue(replay.first().content.contains("历史中的第 1 条"))
    }

    @Test fun importedTriggerReferencesCannotAuthorizeForeignLaterSelfMissingOrUnrecordedHistory() = runTest {
        for (kind in listOf("foreign", "later", "self", "missing", "unrecorded")) {
            val trigger = ChatMessage(id = "trigger", roomId = if (kind == "foreign") "other" else room.id,
                text = "不能被错误授权的内容", createdAt = 1)
            val target = ChatMessage(id = "target", roomId = room.id, characterId = actor.id, text = "旧回答", createdAt = 200,
                contextMessageIds = if (kind == "unrecorded") emptyList() else listOf(trigger.id, "target", "missing"),
                triggerMessageId = when (kind) { "self" -> "target"; "missing" -> "missing"; else -> trigger.id })
            val messages = if (kind == "later") listOf(target, trigger) else listOf(trigger, target)
            val store = StateStore(AppState(characters = listOf(actor.copy(dualModel = false)), rooms = listOf(room), messages = messages))
            val gateway = Gateway { emit(StreamEvent.Text("新回答")) }
            val engine = ChatEngine(store, gateway, this)
            engine.regenerate(target.id)
            advanceUntilIdle()
            val request = gateway.requests.single()
            assertEquals(kind, 1, request.messages.size)
            assertFalse(kind, request.messages.first().content.contains("本轮由一条重新发送的消息触发"))
            assertFalse(kind, request.messages.any { it.content.contains(trigger.text) })
            assertNull(kind, store.snapshot().messages.single { it.id == target.id }.triggerMessageId)
        }
    }

    @Test fun deletingOrClearingHistoryRemovesDanglingTriggerReferencesAlongWithContextReferences() = runTest {
        listOf(false, true).forEach { delete ->
            val trigger = ChatMessage(id = "trigger", roomId = room.id, text = "要清除的消息")
            val foreign = ChatMessage(id = "other-reply", roomId = "other", characterId = actor.id, text = "其他群消息保留",
                contextMessageIds = listOf(trigger.id), triggerMessageId = trigger.id)
            val store = StateStore(AppState(characters = listOf(actor), rooms = listOf(room, room.copy(id = "other")), messages = listOf(trigger, foreign)))
            val gateway = Gateway { error("清空不能发起生成") }
            val engine = ChatEngine(store, gateway, this)
            if (delete) engine.deleteRoom(room.id) else engine.clearHistory(room.id)
            advanceUntilIdle()
            assertEquals(listOf(foreign.copy(contextMessageIds = emptyList(), triggerMessageId = null)), store.snapshot().messages)
            assertEquals(!delete, store.snapshot().rooms.any { it.id == room.id })
            assertTrue(gateway.requests.isEmpty())
        }
    }

    @Test fun legacyMessagesDefaultToNoTriggerAndNewTriggerMetadataSurvivesSerialization() {
        val json = Json { encodeDefaults = true }
        val legacy = json.decodeFromString<ChatMessage>("""{"id":"legacy","roomId":"room","text":"旧消息"}""")
        assertNull(legacy.triggerMessageId)
        val message = legacy.copy(characterId = actor.id, triggerMessageId = "source", contextMessageIds = listOf("source"))
        assertEquals(message, json.decodeFromString<ChatMessage>(json.encodeToString(message)))
    }
}
