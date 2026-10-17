package cn.rolechat.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class MessageReplayTest {
    private fun actor(dual: Boolean = false) = Character(id = "a", name = "原来的署名",
        primary = ModelConfig(model = "a"), secondary = ModelConfig(model = "a-B"),
        dualModel = dual, cutMin = 2, cutMax = 2, firstTextTimeoutSec = 2, stallTimeoutSec = 1,
        messageStyle = MessageStyle.SCENE)

    private fun state(character: Character, messages: List<ChatMessage> = emptyList()) = AppState(
        characters = listOf(character), rooms = listOf(ChatRoom(id = "room", members = listOf(Member("a", 0, true)), autoSpeak = true)),
        messages = messages, tts = TtsSettings(enabled = true))

    private class FakeGateway(val respond: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow {
            requests += request
            respond(request)
        }
    }

    private class RecordingSpeech : SpeechSink {
        val starts = mutableListOf<String>()
        val text = mutableMapOf<String, String>()
        val finishes = mutableListOf<String>()
        val stops = mutableListOf<Set<String>>()
        override fun begin(messageId: String, character: Character) { starts += messageId }
        override fun append(messageId: String, text: String) { this.text[messageId] = this.text[messageId].orEmpty() + text }
        override fun finish(messageId: String) { finishes += messageId }
        override fun stop(roomMessageIds: Set<String>) { stops += roomMessageIds }
    }

    @Test fun continuousReasoningDoesNotExtendFirstTextOrBodyStallDeadlines() = runTest {
        for (bodyStarted in listOf(false, true)) {
            val began = testScheduler.currentTime
            var bStarted = -1L
            val gateway = FakeGateway { request ->
                if (request.model.model == "a") {
                    if (bodyStarted) emit(StreamEvent.Text("想"))
                    while (true) { emit(StreamEvent.Reasoning("还在思考。")); delay(200) }
                } else {
                    bStarted = testScheduler.currentTime
                    emit(StreamEvent.Reasoning("B 独立的思考"))
                    emit(StreamEvent.Text("接着回答"))
                }
            }
            val store = StateStore(state(actor(dual = true)))
            val speech = RecordingSpeech()
            ChatEngine(store, gateway, this, speech).send("room", "问题")
            runCurrent()
            assertEquals(MessageStatus.THINKING_A, store.snapshot().messages.last().status)
            val timeout = if (bodyStarted) 1_000L else 2_000L
            advanceTimeBy(timeout + 1)
            runCurrent()
            val reply = store.snapshot().messages.last()
            assertEquals(timeout, bStarted - began)
            assertEquals(if (bodyStarted) "想" else "", gateway.requests[1].prefix)
            assertEquals(if (bodyStarted) "正文输出停滞超时" else "等待正文超时", reply.segments.first().note)
            assertTrue(reply.segments.first().reasoning.startsWith("还在思考。"))
            assertEquals("B 独立的思考", reply.segments.last().reasoning)
            assertEquals((if (bodyStarted) "想" else "") + "接着回答", reply.text)
            assertEquals(reply.text, speech.text[reply.id])
            assertFalse(gateway.requests.last().messages.any { it.content.contains("思考") })
        }
    }

    @Test fun singleModelRegenerationReplacesOnlyTargetAndReplaysItsOriginalEarlierHistory() = runTest {
        val previous = ChatMessage(id = "previous", roomId = "room", characterId = "a", text = "旧的可见回答",
            createdAt = 1, segments = listOf(GenerationSegment(reasoning = "不能进入历史的思考")),
            speechPreparation = SpeechPreparationRecord(model = "small-model", text = "预处理对白唯一标记",
                instruction = "预处理指令唯一标记", rawOutput = "预处理 JSON 唯一标记", reasoning = "预处理思考唯一标记",
                status = SpeechPreparationStatus.COMPLETE))
        val question = ChatMessage(id = "question", roomId = "room", text = "当时的问题", createdAt = 2)
        val foreign = ChatMessage(id = "foreign", roomId = "another-room", text = "其他群的秘密", createdAt = 2)
        val target = ChatMessage(id = "target", roomId = "room", characterId = "a", senderName = "当时的署名",
            text = "要替换的旧正文", error = "旧错误", createdAt = 3, roundId = "original-round", messageStyle = MessageStyle.SCENE,
            segments = listOf(GenerationSegment(text = "要替换的旧正文", reasoning = "要清空的旧思考", status = SegmentStatus.ERROR)),
            speechPreparation = SpeechPreparationRecord(model = "old-small-model", text = "目标旧对白",
                rawOutput = "目标旧 JSON", reasoning = "目标旧提取思考", status = SpeechPreparationStatus.ERROR, error = "旧提取错误"),
            contextMessageIds = listOf("previous", "question", "target", "later", "foreign"))
        val later = ChatMessage(id = "later", roomId = "room", characterId = "b", senderName = "其他角色",
            text = "不能重做的后续消息", createdAt = 3)
        val original = state(actor().copy(name = "当前名字", messageStyle = MessageStyle.CHAT), listOf(previous, question, foreign, target, later))
        val store = StateStore(original.copy(characters = original.characters + Character(id = "b"),
            rooms = original.rooms.map { it.copy(members = it.members + Member("b", 0, true), interChat = true, maxFollowUps = 10) }))
        val before = store.snapshot()
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway {
            emit(StreamEvent.Reasoning("全新的思考"))
            gate.await()
            emit(StreamEvent.Text("全新的正文"))
        }
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech)
        engine.regenerate(target.id)
        val queued = store.snapshot().messages.single { it.id == target.id }
        assertEquals("", queued.text)
        assertEquals("", queued.error)
        assertTrue(queued.segments.isEmpty())
        assertNull(queued.speechPreparation)
        assertEquals(MessageStatus.QUEUED, queued.status)
        runCurrent()
        assertEquals(MessageStatus.THINKING_A, store.snapshot().messages.single { it.id == target.id }.status)
        gate.complete(Unit)
        advanceUntilIdle()

        val reply = store.snapshot().messages.single { it.id == target.id }
        assertEquals(target.id, reply.id)
        assertEquals(target.createdAt, reply.createdAt)
        assertEquals(target.roundId, reply.roundId)
        assertEquals(target.senderName, reply.senderName)
        assertEquals(target.messageStyle, reply.messageStyle)
        assertEquals("全新的正文", reply.text)
        assertEquals("全新的思考", reply.segments.single().reasoning)
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertNull(reply.speechPreparation)
        assertEquals(listOf("previous", "question"), reply.contextMessageIds)
        assertEquals(before.messages.filterNot { it.id == target.id }, store.snapshot().messages.filterNot { it.id == target.id })
        assertEquals(listOf("a"), gateway.requests.map { it.model.model })
        assertEquals(listOf(PromptMessage("assistant", "旧的可见回答"), PromptMessage("user", "【我】当时的问题")), gateway.requests.single().messages.drop(1))
        assertEquals(listOf(target.id), speech.starts)
        assertEquals(listOf(target.id), speech.finishes)
        assertEquals("全新的正文", speech.text[target.id])
        assertEquals(setOf("previous", "question", "target", "later"), speech.stops.single())
        assertTrue(engine.busyRooms.value.isEmpty())

        // A later normal turn can read the original text but none of the persisted
        // extraction result, raw JSON, instruction, or reasoning from a previous reply.
        store.update { current -> current.copy(rooms = current.rooms.map {
            it.copy(members = listOf(Member("a", 0, true)), interChat = false)
        }) }
        engine.send("room", "继续正常聊天")
        advanceUntilIdle()
        val next = gateway.requests.last().messages
        assertTrue(next.contains(PromptMessage("assistant", previous.text)))
        assertTrue(next.contains(PromptMessage("assistant", reply.text)))
        assertFalse(next.any { it.content.contains("唯一标记") || it.content.contains("目标旧") || it.content.contains("不能进入历史的思考") })
        assertEquals(previous, store.snapshot().messages.single { it.id == previous.id })
    }

    @Test fun fullRegenerationStartsFromAAndHonorsCurrentDualModelSettings() = runTest {
        val gateway = FakeGateway { request ->
            emit(StreamEvent.Reasoning("${request.model.model} 的新思考"))
            emit(StreamEvent.Text(when (request.model.model) {
                "a" -> "旧前缀丢弃"; "a-B" -> "旧续文"
                "new-a" -> "新前缀丢弃"; "new-b" -> "新续文"
                else -> "单模型完整回复"
            }))
        }
        val store = StateStore(state(actor(dual = true)))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "原来的提问")
        advanceUntilIdle()
        val original = store.snapshot().messages.last()
        val later = ChatMessage(roomId = "room", text = "较晚的提问")
        store.update { it.copy(characters = it.characters.map { character -> character.copy(name = "后来改名",
            primary = ModelConfig(model = "new-a"), secondary = ModelConfig(model = "new-b"), cutMin = 3, cutMax = 3,
            messageStyle = MessageStyle.CHAT) }, messages = it.messages + later) }
        engine.regenerate(original.id)
        assertTrue(store.snapshot().messages.single { it.id == original.id }.segments.isEmpty())
        advanceUntilIdle()
        val regenerated = store.snapshot().messages.single { it.id == original.id }
        assertEquals(listOf("a", "a-B", "new-a", "new-b"), gateway.requests.map { it.model.model })
        assertEquals("新前缀新续文", regenerated.text)
        assertEquals(listOf("new-a 的新思考", "new-b 的新思考"), regenerated.segments.map { it.reasoning })
        assertEquals("", gateway.requests[2].prefix)
        assertEquals("新前缀", gateway.requests.last().prefix)
        assertEquals(listOf("A", "B"), regenerated.segments.map { it.label })
        assertFalse(regenerated.segments.any { fresh -> original.segments.any { old -> old.id == fresh.id } })
        assertEquals(gateway.requests[0].messages.drop(1), gateway.requests[2].messages.drop(1))
        assertEquals(gateway.requests[2].messages, gateway.requests[3].messages)
        assertEquals(original.contextMessageIds, regenerated.contextMessageIds)
        assertEquals(original.senderName, regenerated.senderName)
        assertEquals(original.messageStyle, regenerated.messageStyle)
        assertEquals(original.createdAt, regenerated.createdAt)
        assertEquals(later, store.snapshot().messages.last())
        assertEquals(3, store.snapshot().messages.size)

        store.update { it.copy(characters = it.characters.map { character ->
            character.copy(dualModel = false, primary = ModelConfig(model = "single-now"))
        }) }
        engine.regenerate(original.id)
        advanceUntilIdle()
        val single = store.snapshot().messages.single { it.id == original.id }
        assertEquals(5, gateway.requests.size)
        assertEquals("single-now", gateway.requests.last().model.model)
        assertEquals("", gateway.requests.last().prefix)
        assertEquals("单模型完整回复", single.text)
        assertEquals("A", single.segments.single().label)
        assertEquals("single-now 的新思考", single.segments.single().reasoning)
        assertEquals(later, store.snapshot().messages.last())
    }

    @Test fun regenerationCancelsOldThinkingWithoutWaitingAndRejectsLateOldEvents() = runTest {
        val requests = mutableListOf<GenerationRequest>()
        var lateEventsAttempted = false
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest): Flow<StreamEvent> = object : Flow<StreamEvent> {
                override suspend fun collect(collector: FlowCollector<StreamEvent>) {
                    requests += request
                    if (requests.size == 1) {
                        try {
                            collector.emit(StreamEvent.Reasoning("旧请求的思考"))
                            awaitCancellation()
                        } finally {
                            // Simulate a transport whose cancellation cleanup is slow
                            // and whose callbacks keep trying to publish old deltas.
                            withContext(NonCancellable) {
                                delay(5_000)
                                lateEventsAttempted = true
                                runCatching { collector.emit(StreamEvent.Reasoning("不能混入的新旧思考")) }
                                runCatching { collector.emit(StreamEvent.Text("不能混入的旧正文")) }
                            }
                        }
                    } else {
                        collector.emit(StreamEvent.Reasoning("${request.model.model} 重生成思考"))
                        collector.emit(StreamEvent.Text(if (request.model.model == "a") "新前缀丢弃" else "新续文"))
                    }
                }
            }
        }
        val store = StateStore(state(actor(dual = true)))
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "原提问")
        runCurrent()
        val original = store.snapshot().messages.last()
        assertEquals(MessageStatus.THINKING_A, original.status)
        engine.regenerate(original.id)
        runCurrent()
        val completed = store.snapshot().messages.last()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("a", "a", "a-B"), requests.map { it.model.model })
        assertEquals(original.id, completed.id)
        assertEquals(MessageStatus.COMPLETE, completed.status)
        assertEquals("新前新续文", completed.text)
        assertEquals(listOf("a 重生成思考", "a-B 重生成思考"), completed.segments.map { it.reasoning })
        assertEquals(completed.text, speech.text[original.id])
        assertEquals(listOf(original.id), speech.finishes)
        assertTrue(speech.stops.last().contains(original.id))
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(lateEventsAttempted)
        assertEquals(completed, store.snapshot().messages.last())
        assertEquals(3, requests.size)
        assertTrue(engine.busyRooms.value.isEmpty())
    }

    @Test fun regenerationOfUserOrDeletedOrRemovedRoleIsIgnoredWithoutSideEffects() = runTest {
        val human = ChatMessage(id = "human", roomId = "room", text = "真人消息")
        val deleted = ChatMessage(id = "deleted", roomId = "room", characterId = "deleted-role", text = "角色已删除")
        val removed = ChatMessage(id = "removed", roomId = "room", characterId = "b", text = "角色已退群")
        val initial = state(actor(), listOf(human, deleted, removed)).let {
            it.copy(characters = it.characters + Character(id = "b"))
        }
        val store = StateStore(initial)
        val gateway = FakeGateway { emit(StreamEvent.Text("不应请求")) }
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech)
        listOf("human", "deleted", "removed", "missing").forEach(engine::regenerate)
        advanceUntilIdle()
        assertEquals(initial, store.snapshot())
        assertTrue(gateway.requests.isEmpty())
        assertTrue(speech.starts.isEmpty())
        assertTrue(speech.stops.isEmpty())
        assertTrue(engine.busyRooms.value.isEmpty())
    }
}
