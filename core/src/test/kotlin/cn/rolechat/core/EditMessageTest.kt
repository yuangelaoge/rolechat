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
class EditMessageTest {
    @Test fun invalidEmptyAndUnchangedEditsDoNotCancelAnActiveTurn() = runTest {
        val original = ChatMessage(id = "human", roomId = "room", text = "原文")
        val ai = ChatMessage(id = "ai", roomId = "room", characterId = "a", text = "原来的角色消息")
        val orphan = ChatMessage(id = "orphan", roomId = "missing-room", text = "会话不存在")
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val gateway = FakeGateway {
            try {
                emit(StreamEvent.Text("仍在生成"))
                gate.await()
                emit(StreamEvent.Text("，正常结束"))
            } finally { if (!gate.isCompleted) cancelled = true }
        }
        val store = StateStore(initialState(listOf(original, ai, orphan)))
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "继续回复")
        runCurrent()
        val before = store.snapshot()
        val stopsBefore = speech.stops.toList()
        assertFalse(engine.editMessage("human", "  原文\n"))
        assertFalse(engine.editMessage("human", " \n\t "))
        assertFalse(engine.editMessage("ai", "  原来的角色消息\n"))
        assertFalse(engine.editMessage("ai", " \n\t "))
        assertFalse(engine.editMessage("missing", "找不到消息"))
        assertFalse(engine.editMessage("orphan", "找不到所属会话"))
        runCurrent()
        assertEquals(before, store.snapshot())
        assertEquals(stopsBefore, speech.stops)
        assertEquals(setOf("room"), engine.busyRooms.value)
        assertEquals(1, gateway.requests.size)
        assertFalse(cancelled)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("仍在生成，正常结束", store.snapshot().messages.last().text)
        assertEquals(MessageStatus.COMPLETE, store.snapshot().messages.last().status)
    }

    @Test fun validEditPreservesIdentityAndCompletedHistoryStopsOnlyItsRoomAndRejectsLateDeltas() = runTest {
        val edited = ChatMessage(id = "editable", roomId = "room", senderName = "当时的名字", text = "需要修正的问题",
            mentions = listOf("a"), createdAt = 17, roundId = "earlier-round")
        val completed = ChatMessage(id = "completed", roomId = "room", characterId = "a", senderName = "角色",
            text = "已经完成的后续回复", createdAt = 18, contextMessageIds = listOf(edited.id),
            segments = listOf(GenerationSegment(text = "已经完成的后续回复", reasoning = "保留的历史思考", status = SegmentStatus.COMPLETE)))
        val foreign = ChatMessage(id = "foreign", roomId = "other", text = "其他会话原有消息", createdAt = 19)
        val requests = mutableListOf<GenerationRequest>()
        val otherMayFinish = CompletableDeferred<Unit>()
        var editedStreamCancelled = false
        var lateEventsAttempted = false
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest): Flow<StreamEvent> = object : Flow<StreamEvent> {
                override suspend fun collect(collector: FlowCollector<StreamEvent>) {
                    requests += request
                    if (request.messages.last().content.endsWith("开始生成")) {
                        try {
                            collector.emit(StreamEvent.Reasoning("编辑前的思考"))
                            collector.emit(StreamEvent.Text("编辑前的部分正文"))
                            awaitCancellation()
                        } finally {
                            editedStreamCancelled = true
                            withContext(NonCancellable) {
                                delay(5_000)
                                lateEventsAttempted = true
                                runCatching { collector.emit(StreamEvent.Reasoning("禁止迟到思考")) }
                                runCatching { collector.emit(StreamEvent.Text("禁止迟到正文")) }
                            }
                        }
                    } else {
                        collector.emit(StreamEvent.Text("其他会话仍可"))
                        otherMayFinish.await()
                        collector.emit(StreamEvent.Text("继续生成"))
                    }
                }
            }
        }
        val store = StateStore(initialState(listOf(edited, completed, foreign)))
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech, now = { 1_000 })
        engine.send("room", "开始生成")
        engine.send("other", "其他会话")
        runCurrent()
        val beforeEdit = store.snapshot()
        val roomMessageIds = beforeEdit.messages.filter { it.roomId == "room" }.map { it.id }.toSet()
        val otherBefore = beforeEdit.messages.filter { it.roomId == "other" }
        val replyId = beforeEdit.messages.last { it.roomId == "room" }.id
        val spokenBefore = speech.text.toMap()
        val startsBefore = speech.starts.toList()
        assertTrue(engine.editMessage(edited.id, "  修正后的问题\n"))
        runCurrent()
        val updated = store.snapshot().messages.single { it.id == edited.id }
        assertEquals("修正后的问题", updated.text)
        assertEquals(edited, updated.copy(text = edited.text))
        assertEquals(completed, store.snapshot().messages.single { it.id == completed.id })
        assertEquals(otherBefore, store.snapshot().messages.filter { it.roomId == "other" })
        assertEquals(setOf("other"), engine.busyRooms.value)
        assertTrue(editedStreamCancelled)
        assertEquals(roomMessageIds, speech.stops.last())
        assertEquals(startsBefore, speech.starts)
        assertEquals(spokenBefore, speech.text)
        assertEquals(2, requests.size)
        val stopped = store.snapshot().messages.single { it.id == replyId }
        assertEquals(MessageStatus.INTERRUPTED, stopped.status)
        assertEquals("因编辑消息停止", stopped.error)
        assertEquals("编辑前的部分正文", stopped.text)
        assertEquals(SegmentStatus.INTERRUPTED, stopped.segments.single().status)
        assertFalse(speech.finishes.contains(replyId))

        otherMayFinish.complete(Unit)
        runCurrent()
        assertEquals("其他会话仍可继续生成", store.snapshot().messages.last { it.roomId == "other" }.text)
        val stable = store.snapshot()
        val stableSpeech = speech.text.toMap()
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(lateEventsAttempted)
        assertEquals(stable, store.snapshot())
        assertEquals(stableSpeech, speech.text)
        assertEquals(2, requests.size)
        assertTrue(engine.busyRooms.value.isEmpty())
    }

    @Test fun laterRegenerationAndNewTurnsReadEditedTextThroughTheSameMessageId() = runTest {
        val question = ChatMessage(id = "question", roomId = "room", text = "旧问题唯一标记", createdAt = 1)
        val reply = ChatMessage(id = "target", roomId = "room", characterId = "a", text = "旧回复", createdAt = 2,
            contextMessageIds = listOf(question.id))
        val store = StateStore(initialState(listOf(question, reply)))
        val gateway = FakeGateway { emit(StreamEvent.Text("读取修改后生成的回复")) }
        val engine = ChatEngine(store, gateway, this, now = { 1_000 })
        assertTrue(engine.editMessage(question.id, "  更新后的问题唯一标记  "))
        runCurrent()
        assertTrue("编辑本身不能自动发起请求", gateway.requests.isEmpty())
        assertEquals(listOf(question.id, reply.id), store.snapshot().messages.map { it.id })
        assertEquals(reply, store.snapshot().messages.last())
        engine.regenerate(reply.id)
        advanceUntilIdle()
        assertEquals(1, gateway.requests.size)
        assertTrue(gateway.requests.single().messages.any { it.content == "【我】更新后的问题唯一标记" })
        assertFalse(gateway.requests.single().messages.any { it.content.contains("旧问题唯一标记") })
        val regenerated = store.snapshot().messages.single { it.id == reply.id }
        assertEquals(listOf(question.id), regenerated.contextMessageIds)
        assertEquals(1, store.snapshot().messages.count { it.characterId == null })

        engine.send("room", "继续聊这个问题")
        advanceUntilIdle()
        assertEquals(2, gateway.requests.size)
        val nextHistory = gateway.requests.last().messages
        assertTrue(nextHistory.any { it.content == "【我】更新后的问题唯一标记" })
        assertTrue(nextHistory.any { it.content == "【我】继续聊这个问题" })
        assertFalse(nextHistory.any { it.content.contains("旧问题唯一标记") })
        assertEquals(question.copy(text = "更新后的问题唯一标记"), store.snapshot().messages.single { it.id == question.id })
    }

    @Test fun historicalAiEditsWorkAfterRoleRemovalAndDiscardOnlyObsoleteGenerationMetadata() = runTest {
        for (roleDeleted in listOf(false, true)) {
            val question = ChatMessage(id = "question", roomId = "room", text = "原来的问题", createdAt = 1)
            val target = ChatMessage(id = "historical-ai", roomId = "room", characterId = "a", senderName = "当时的旁白",
                text = "旧模型正文", status = MessageStatus.ERROR, error = "旧连接错误", messageStyle = MessageStyle.SCENE,
                createdAt = 2, roundId = "old-round", contextMessageIds = listOf(question.id), mentions = listOf("a"),
                segments = listOf(
                    GenerationSegment(label = "A", text = "旧模型", reasoning = "旧A思考", status = SegmentStatus.CUT),
                    GenerationSegment(label = "B", text = "正文", reasoning = "旧B思考", status = SegmentStatus.ERROR)
                ), speechPreparation = SpeechPreparationRecord(model = "old-small-model", text = "旧提取对白",
                    instruction = "旧朗读指令", rawOutput = "旧原始 JSON", reasoning = "旧提取思考",
                    status = SpeechPreparationStatus.COMPLETE))
            val later = ChatMessage(id = "later", roomId = "room", text = "后面的完成消息不回写", createdAt = 3)
            val foreign = ChatMessage(id = "foreign", roomId = "other", text = "其他房间也不变", createdAt = 4)
            val initial = initialState(listOf(question, target, later, foreign)).let { state -> state.copy(
                characters = if (roleDeleted) emptyList() else state.characters,
                rooms = state.rooms.map { if (it.id == "room") it.copy(members = emptyList()) else it }
            ) }
            val store = StateStore(initial)
            val gateway = FakeGateway { error("编辑不能发起生成") }
            val speech = RecordingSpeech()
            val engine = ChatEngine(store, gateway, this, speech)
            assertTrue(engine.editMessage(target.id, "  手动修正后的场景。\n"))
            advanceUntilIdle()
            assertEquals(initial.copy(messages = listOf(question,
                target.copy(text = "手动修正后的场景。", status = MessageStatus.COMPLETE, error = "", segments = emptyList(), speechPreparation = null),
                later, foreign)), store.snapshot())
            assertEquals(listOf(setOf(question.id, target.id, later.id)), speech.stops)
            assertTrue(gateway.requests.isEmpty())
            assertTrue(speech.starts.isEmpty())
            assertTrue(engine.busyRooms.value.isEmpty())
        }
    }

    @Test fun editingAnActiveAiReplyStopsAOrCompatibilityBAndLateEventsCannotOverwriteManualText() = runTest {
        for (editDuringB in listOf(false, true)) {
            var cancelled = false
            var lateAttempted = false
            val requests = mutableListOf<GenerationRequest>()
            val gateway = object : ChatGateway {
                override fun stream(request: GenerationRequest): Flow<StreamEvent> = object : Flow<StreamEvent> {
                    override suspend fun collect(collector: FlowCollector<StreamEvent>) {
                        requests += request
                        val isB = request.model.model == "test/edit-b"
                        if (request.messages.last().content.endsWith("新问题")) {
                            collector.emit(if (isB) StreamEvent.Reasoning("新续文") else StreamEvent.Text("新回答"))
                            return
                        }
                        if (editDuringB && !isB) {
                            collector.emit(StreamEvent.Text("前缀"))
                            return
                        }
                        try {
                            collector.emit(StreamEvent.Reasoning(if (isB) "原来的B正文" else "原来的A思考"))
                            if (!isB) collector.emit(StreamEvent.Text("前"))
                            awaitCancellation()
                        } finally {
                            cancelled = true
                            withContext(NonCancellable) {
                                delay(5_000)
                                lateAttempted = true
                                runCatching { collector.emit(StreamEvent.Reasoning("过期思考或正文")) }
                                runCatching { collector.emit(StreamEvent.Text("过期正文")) }
                            }
                        }
                    }
                }
            }
            val initial = initialState(emptyList()).let { state -> state.copy(characters = state.characters.map {
                it.copy(dualModel = true, secondary = ModelConfig(model = "test/edit-b"),
                    cutMin = 2, cutMax = 2, secondaryOutputSource = ReplyOutputSource.REASONING)
            }) }
            val store = StateStore(initial)
            val speech = RecordingSpeech()
            val engine = ChatEngine(store, gateway, this, speech)
            engine.send("room", "开始旧回复")
            runCurrent()
            store.updateMessage(store.snapshot().messages.last().id) { it.copy(speechPreparation = SpeechPreparationRecord(
                model = "small-model", rawOutput = "原来的半个提取结果", reasoning = "原来的提取思考")) }
            val target = store.snapshot().messages.last()
            assertEquals(if (editDuringB) 2 else 1, requests.size)
            assertTrue(target.segments.isNotEmpty())
            assertTrue(engine.editMessage(target.id, "人工改好的完整回答"))
            engine.switchToB(target.id)
            runCurrent()
            val manual = target.copy(text = "人工改好的完整回答", segments = emptyList(), error = "", status = MessageStatus.COMPLETE,
                speechPreparation = null)
            assertEquals(manual, store.snapshot().messages.last())
            assertTrue(cancelled)
            assertTrue(engine.busyRooms.value.isEmpty())
            assertEquals(store.snapshot().messages.map { it.id }.toSet(), speech.stops.last())
            assertFalse(speech.finishes.contains(target.id))
            val audioBeforeLate = speech.text.toMap()
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue(lateAttempted)
            assertEquals(manual, store.snapshot().messages.last())
            assertEquals(audioBeforeLate, speech.text)
            assertEquals(if (editDuringB) 2 else 1, requests.size)

            engine.send("room", "新问题")
            advanceUntilIdle()
            val nextA = requests[if (editDuringB) 2 else 1]
            assertTrue(nextA.messages.any { it == PromptMessage("assistant", "【${manual.senderName}】${manual.text}") })
            assertFalse(nextA.messages.any { it.content.contains("原来的") || it.content.contains("过期") })
            assertEquals(manual, store.snapshot().messages.single { it.id == target.id })
        }
    }

    private fun initialState(messages: List<ChatMessage>) = AppState(
        characters = listOf(Character(id = "a", name = "角色", prompt = "简短回复。",
            primary = ModelConfig(model = "test/edit-model"), firstTextTimeoutSec = 30, stallTimeoutSec = 30)),
        rooms = listOf(
            ChatRoom(id = "room", members = listOf(Member("a", 0, true)), autoSpeak = true),
            ChatRoom(id = "other", members = listOf(Member("a", 0, true)), autoSpeak = true)
        ), messages = messages, tts = TtsSettings(enabled = true)
    )

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
}
