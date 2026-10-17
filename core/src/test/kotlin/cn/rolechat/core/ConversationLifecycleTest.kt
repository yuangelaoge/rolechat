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
class ConversationLifecycleTest {
    private fun initialState(dual: Boolean = false): AppState {
        val actor = Character(id = "a", name = "共享角色", primary = ModelConfig(model = "a"),
            secondary = ModelConfig(model = "a-B"), dualModel = dual, cutMin = 2, cutMax = 2,
            cooldownSec = 100, firstTextTimeoutSec = 20, stallTimeoutSec = 20)
        val room = ChatRoom(id = "room", name = "要清理的群", members = listOf(Member("a", 0, true)),
            autoSpeak = true, userName = "旅人", userBackground = "会话里的身份", pinned = true)
        return AppState(characters = listOf(actor), rooms = listOf(room, room.copy(id = "other", name = "保留的群", pinned = false)),
            tts = TtsSettings(enabled = true))
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

    @Test fun clearHistoryRemovesAllStoredContentAndOnlyResetsThisRoomsCooldown() = runTest {
        val requests = mutableListOf<GenerationRequest>()
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest) = flow {
                requests += request
                emit(StreamEvent.Reasoning("思考记录：${request.messages.last().content}"))
                emit(StreamEvent.Text("回答：${request.messages.last().content}"))
            }
        }
        val initial = initialState()
        val store = StateStore(initial)
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech, now = { 1_000L })
        engine.send("room", "应该彻底清掉的旧提问")
        advanceUntilIdle()
        engine.send("other", "保留的提问")
        advanceUntilIdle()
        val deletedMessages = store.snapshot().messages.filter { it.roomId == "room" }
        val deletedIds = deletedMessages.map { it.id }.toSet()
        val deletedReplyId = deletedMessages.last().id
        val otherQuestionId = store.snapshot().messages.first { it.roomId == "other" }.id
        // Imported files can contain invalid cross-room history references. A
        // mention is a character ID, not a message reference, and stays untouched.
        store.update { state -> state.copy(messages = state.messages.map { message ->
            if (message.roomId == "other" && message.characterId != null)
                message.copy(contextMessageIds = listOf(otherQuestionId) + deletedIds,
                    mentions = listOf("a")) else message
        }) }
        val before = store.snapshot()
        engine.clearHistory("room")
        val cleared = store.snapshot()
        assertEquals(initial.rooms, cleared.rooms)
        assertEquals(initial.characters, cleared.characters)
        assertEquals(initial.worlds, cleared.worlds)
        assertEquals(initial.providers, cleared.providers)
        assertEquals(initial.tts, cleared.tts)
        assertEquals(before.messages.filter { it.roomId == "other" }.map {
            it.copy(contextMessageIds = it.contextMessageIds.filterNot(deletedIds::contains))
        }, cleared.messages)
        assertTrue(cleared.messages.none { it.roomId == "room" || it.id in deletedIds })
        assertTrue(cleared.messages.none { it.contextMessageIds.any(deletedIds::contains) })
        assertEquals(deletedIds, speech.stops.last())
        engine.retry(deletedReplyId)
        engine.regenerate(deletedReplyId)
        engine.switchToB(deletedReplyId)
        advanceUntilIdle()
        assertEquals(cleared, store.snapshot())
        assertEquals(2, requests.size)

        // The same actor is still cooling down in the unaffected conversation.
        engine.send("other", "冷却期间不应再回复")
        advanceUntilIdle()
        assertEquals(2, requests.size)
        engine.send("room", "清空后的新提问")
        advanceUntilIdle()
        assertEquals(3, requests.size)
        val fresh = requests.last()
        assertEquals(listOf(PromptMessage("user", "【旅人】清空后的新提问")), fresh.messages.drop(1))
        assertFalse(fresh.messages.any { "应该彻底清掉" in it.content || "思考记录" in it.content || "保留的提问" in it.content })
        assertEquals(2, store.snapshot().messages.count { it.roomId == "room" })
    }

    @Test fun clearingAnActiveStreamThenSendingImmediatelyRejectsLateOldTextAndReasoning() = runTest {
        val requests = mutableListOf<GenerationRequest>()
        var oldCancelled = false
        var attemptedLateEvents = false
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest): Flow<StreamEvent> = object : Flow<StreamEvent> {
                override suspend fun collect(collector: FlowCollector<StreamEvent>) {
                    requests += request
                    if (requests.size == 1) {
                        try {
                            collector.emit(StreamEvent.Reasoning("已删除的旧思考"))
                            collector.emit(StreamEvent.Text("旧"))
                            awaitCancellation()
                        } finally {
                            oldCancelled = true
                            withContext(NonCancellable) {
                                delay(5_000)
                                attemptedLateEvents = true
                                runCatching { collector.emit(StreamEvent.Reasoning("迟到的旧思考")) }
                                runCatching { collector.emit(StreamEvent.Text("迟到的旧正文")) }
                            }
                        }
                    } else {
                        collector.emit(StreamEvent.Reasoning("全新思考"))
                        collector.emit(StreamEvent.Text(if (request.model.model == "a") "新前缀" else "新续写"))
                    }
                }
            }
        }
        val store = StateStore(initialState(dual = true))
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech, now = { 1_000L })
        engine.send("room", "旧提问")
        runCurrent()
        val oldIds = store.snapshot().messages.map { it.id }.toSet()
        val oldReplyId = store.snapshot().messages.last().id
        engine.clearHistory("room")
        assertTrue(store.snapshot().messages.isEmpty())
        assertTrue(engine.busyRooms.value.isEmpty())
        assertEquals(oldIds, speech.stops.last())
        engine.switchToB(oldReplyId)
        engine.retry(oldReplyId)
        engine.regenerate(oldReplyId)
        engine.send("room", "新提问")
        runCurrent()
        assertTrue(oldCancelled)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("a", "a", "a-B"), requests.map { it.model.model })
        assertEquals(listOf(PromptMessage("user", "【旅人】新提问")), requests[1].messages.drop(1))
        val freshState = store.snapshot()
        val freshReply = freshState.messages.last()
        assertEquals("新前新续写", freshReply.text)
        assertEquals(listOf("全新思考", "全新思考"), freshReply.segments.map { it.reasoning })
        assertEquals(freshReply.text, speech.text[freshReply.id])
        assertEquals(listOf(freshReply.id), speech.finishes)
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(attemptedLateEvents)
        assertEquals(freshState, store.snapshot())
        assertTrue(store.snapshot().messages.none { it.id in oldIds })
        assertEquals(3, requests.size)
    }

    @Test fun deleteStopsOnlyItsRoomAndRecreatedRoomDoesNotInheritOldCooldownOrCallbacks() = runTest {
        val requests = mutableListOf<GenerationRequest>()
        val otherMayFinish = CompletableDeferred<Unit>()
        var deletedStreamCancelled = false
        var attemptedLateEvents = false
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest): Flow<StreamEvent> = object : Flow<StreamEvent> {
                override suspend fun collect(collector: FlowCollector<StreamEvent>) {
                    requests += request
                    val question = request.messages.last().content
                    when {
                        request.model.model == "a-B" -> collector.emit(StreamEvent.Text("接续"))
                        question.endsWith("删除中的提问") -> {
                            try {
                                collector.emit(StreamEvent.Reasoning("删除中的思考"))
                                collector.emit(StreamEvent.Text("删"))
                                awaitCancellation()
                            } finally {
                                deletedStreamCancelled = true
                                withContext(NonCancellable) {
                                    delay(5_000)
                                    attemptedLateEvents = true
                                    runCatching { collector.emit(StreamEvent.Reasoning("不能复活的旧思考")) }
                                    runCatching { collector.emit(StreamEvent.Text("不能复活的旧正文")) }
                                }
                            }
                        }
                        question.endsWith("其他群的提问") -> {
                            collector.emit(StreamEvent.Reasoning("保留的思考"))
                            collector.emit(StreamEvent.Text("留"))
                            otherMayFinish.await()
                            collector.emit(StreamEvent.Text("下"))
                        }
                        else -> collector.emit(StreamEvent.Text("重建"))
                    }
                }
            }
        }
        val initial = initialState(dual = true)
        val store = StateStore(initial)
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech, now = { 1_000L })
        engine.send("room", "删除中的提问")
        engine.send("other", "其他群的提问")
        runCurrent()
        val deletedIds = store.snapshot().messages.filter { it.roomId == "room" }.map { it.id }.toSet()
        val deletedReplyId = store.snapshot().messages.single { it.roomId == "room" && it.characterId != null }.id
        val otherBefore = store.snapshot().messages.filter { it.roomId == "other" }
        engine.deleteRoom("room")
        runCurrent()
        assertTrue(deletedStreamCancelled)
        assertEquals(listOf(initial.rooms.last()), store.snapshot().rooms)
        assertEquals(initial.characters, store.snapshot().characters)
        assertEquals(initial.worlds, store.snapshot().worlds)
        assertEquals(initial.providers, store.snapshot().providers)
        assertEquals(otherBefore, store.snapshot().messages)
        assertEquals(setOf("other"), engine.busyRooms.value)
        assertEquals(deletedIds, speech.stops.last())
        engine.retry(deletedReplyId)
        engine.regenerate(deletedReplyId)
        engine.switchToB(deletedReplyId)
        engine.send("room", "已删除的群不能发言")
        runCurrent()
        assertEquals(2, requests.size)

        // Imported/recreated rooms can reuse an ID. Both the old epoch and this
        // room's cooldown must stay isolated from its fresh conversation.
        store.update { it.copy(rooms = it.rooms + initial.rooms.first()) }
        engine.send("room", "重建后的提问")
        runCurrent()
        assertEquals(4, requests.size)
        assertEquals(listOf(PromptMessage("user", "【旅人】重建后的提问")), requests[2].messages.drop(1))
        assertEquals("重建接续", store.snapshot().messages.last { it.roomId == "room" }.text)
        assertEquals(otherBefore, store.snapshot().messages.filter { it.roomId == "other" })
        otherMayFinish.complete(Unit)
        runCurrent()
        assertEquals("留下接续", store.snapshot().messages.last { it.roomId == "other" }.text)
        val completed = store.snapshot()
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(attemptedLateEvents)
        assertEquals(completed, store.snapshot())
        assertEquals(5, requests.size)
        assertTrue(store.snapshot().messages.none { it.id in deletedIds })
        assertTrue(engine.busyRooms.value.isEmpty())
    }
}
