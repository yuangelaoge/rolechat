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
class EditAndSendMessageTest {
    private fun actor(id: String) = Character(id = id, name = id, prompt = "$id-private-prompt",
        primary = ModelConfig(model = id), secondary = ModelConfig(model = "$id-B"),
        firstTextTimeoutSec = 30, stallTimeoutSec = 30)

    private fun state(actors: List<Character>, messages: List<ChatMessage>, room: ChatRoom? = null) = AppState(
        characters = actors, messages = messages,
        rooms = listOf(room ?: ChatRoom(id = "room", members = actors.map { Member(it.id, 0, true) }, autoSpeak = true)),
        tts = TtsSettings(enabled = true))

    private class Gateway(private val respond: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow { requests += request; respond(request) }
    }

    private class Speech : SpeechSink {
        val starts = mutableListOf<Pair<String, String>>()
        val text = mutableMapOf<String, String>()
        val finishes = mutableListOf<String>()
        val stops = mutableListOf<Set<String>>()
        override fun begin(messageId: String, character: Character) { starts += messageId to character.id }
        override fun append(messageId: String, text: String) { this.text[messageId] = this.text[messageId].orEmpty() + text }
        override fun finish(messageId: String) { finishes += messageId }
        override fun stop(roomMessageIds: Set<String>) { stops += roomMessageIds }
    }

    @Test fun humanResendPreservesIdentityAndMentionsAppendsNoUserDuplicateAndAllowsUnchangedResend() = runTest {
        val target = ChatMessage(id = "target", roomId = "room", senderName = "当时的我", text = "旧提问", createdAt = 10,
            mentions = listOf("a", "b"), contextMessageIds = listOf("earlier"), roundId = "old-round")
        val later = ChatMessage(id = "later", roomId = "room", characterId = "a", senderName = "a", text = "后面已经完成的回答", createdAt = 20)
        val actors = listOf(actor("a"), actor("b")).map { it.copy(probability = 0, cooldownSec = 100) }
        val room = ChatRoom(id = "room", members = actors.map { Member(it.id, 0, true) }, maxInitialReplies = 1,
            userName = "现在的我", userBackground = "当前会话背景", autoSpeak = true)
        val store = StateStore(state(actors, listOf(target, later), room))
        val gateway = Gateway { emit(StreamEvent.Text("接话-${it.model.model}")) }
        val speech = Speech()
        val engine = ChatEngine(store, gateway, this, speech)

        assertTrue(engine.editAndSendMessage(target.id, "  修改后作为本轮依据的提问\n"))
        assertEquals(listOf(target.copy(text = "修改后作为本轮依据的提问")), store.snapshot().messages)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), gateway.requests.map { it.model.model })
        assertEquals(1, store.snapshot().messages.count { it.characterId == null })
        assertFalse(store.snapshot().messages.any { it.id == later.id })
        val first = gateway.requests.first()
        assertTrue(first.messages.first().content.contains("本轮由一条重新发送的消息触发"))
        assertTrue(first.messages.first().content.contains("原发送者：当时的我（真人用户）"))
        assertTrue(first.messages.first().content.contains("历史中的第 1 条"))
        assertFalse(first.messages.first().content.contains("修改后作为本轮依据的提问"))
        assertTrue(first.messages.first().content.contains("名字：现在的我"))
        assertEquals(listOf(PromptMessage("user", "【本轮重新发送】\n【当时的我】修改后作为本轮依据的提问")), first.messages.drop(1))
        val firstRound = store.snapshot().messages.last().roundId
        assertNotEquals(target.roundId, firstRound)
        assertEquals(listOf("a", "b"), speech.starts.map { it.second })

        assertTrue(engine.editAndSendMessage(target.id, "修改后作为本轮依据的提问"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "a", "b"), gateway.requests.map { it.model.model })
        assertNotEquals(firstRound, store.snapshot().messages.last().roundId)
        assertEquals(target.copy(text = "修改后作为本轮依据的提问"), store.snapshot().messages.first())
        assertEquals(1, store.snapshot().messages.count { it.characterId == null })

        // A normal later turn must not inherit the one-round system reminder.
        engine.send("room", "一个正常的新问题", setOf("a"))
        advanceUntilIdle()
        assertFalse(gateway.requests.last().messages.first().content.contains("本轮由一条重新发送的消息触发"))
        assertEquals(2, store.snapshot().messages.count { it.characterId == null })
    }

    @Test fun aiResendDeletesLaterHistoryAndUsesTheExistingSequentialOrParallelRules() = runTest {
        listOf(ReplyMode.SEQUENTIAL, ReplyMode.PARALLEL).forEach { mode ->
            val target = ChatMessage(id = "target", roomId = "room", characterId = "a", senderName = "过去的 a",
                text = "旧场景", createdAt = 1, roundId = "old-round", messageStyle = MessageStyle.SCENE,
                contextMessageIds = listOf("before"), status = MessageStatus.ERROR, error = "旧错误",
                segments = listOf(GenerationSegment(text = "旧场景", reasoning = "旧 COT")),
                speechPreparation = SpeechPreparationRecord(rawOutput = "旧提取 JSON"))
            val later = ChatMessage(id = "later", roomId = "room", text = "晚些时候留下的旧消息", createdAt = 2)
            val foreign = ChatMessage(id = "foreign", roomId = "other", text = "其他会话秘密", createdAt = 2)
            val actors = listOf(actor("a"), actor("b"), actor("c"))
            val room = ChatRoom(id = "room", members = actors.map { Member(it.id, 0, true) }, mode = mode, autoSpeak = true)
            val store = StateStore(state(actors, listOf(target, later, foreign), room))
            val gate = CompletableDeferred<Unit>()
            val gateway = Gateway { request ->
                if (request.model.model == "b") gate.await()
                emit(StreamEvent.Text("新回答-${request.model.model}"))
            }
            val speech = Speech()
            val engine = ChatEngine(store, gateway, this, speech)
            assertTrue(engine.editAndSendMessage(target.id, "人工修正的场景"))
            runCurrent()
            assertEquals(if (mode == ReplyMode.PARALLEL) listOf("b", "c") else listOf("b"), gateway.requests.map { it.model.model })
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("b", "c"), gateway.requests.map { it.model.model })
            val changed = target.copy(text = "人工修正的场景", segments = emptyList(), error = "",
                status = MessageStatus.COMPLETE, speechPreparation = null)
            assertEquals(listOf(changed, foreign), store.snapshot().messages.take(2))
            val appended = store.snapshot().messages.drop(2)
            assertEquals(listOf("b", "c"), appended.map { it.characterId })
            assertEquals(listOf("b", "c"), speech.starts.map { it.second })
            assertEquals(appended.map { it.id }.toSet(), speech.finishes.toSet())
            appended.forEach { assertEquals(it.text, speech.text[it.id]) }
            gateway.requests.forEach { request ->
                assertTrue(request.messages.first().content.contains("原发送者：过去的 a（AI 角色）"))
                assertTrue(request.messages.contains(PromptMessage("user", "【本轮重新发送】\n【过去的 a】人工修正的场景")))
                assertFalse(request.messages.any { it.content.contains(later.text) })
                assertFalse(request.messages.any { it.content.contains("旧 COT") || it.content.contains("旧提取") || it.content.contains("其他会话秘密") })
            }
            val lastSawFirst = gateway.requests.last().messages.any { it.content == "【b】新回答-b" }
            assertEquals(mode == ReplyMode.SEQUENTIAL, lastSawFirst)
            if (mode == ReplyMode.PARALLEL) assertEquals(appended.first().contextMessageIds, appended.last().contextMessageIds)
            assertTrue(engine.busyRooms.value.isEmpty())
        }
    }

    @Test fun oneActorContinuesAnEditedAssistantMessageAsANewDualReplyEvenWithZeroFrequency() = runTest {
        listOf(true, false).forEach { direct ->
            val a = actor("a").copy(dualModel = true, cutMin = 2, cutMax = 2, probability = 0, maxRepliesPerRound = 0, cooldownSec = 100)
            val target = ChatMessage(id = "target", roomId = "room", characterId = a.id, senderName = "旧署名", text = "待修正的回答", createdAt = 1)
            val later = ChatMessage(id = "later", roomId = "room", text = "历史上更晚的问题", createdAt = 2)
            val room = ChatRoom(id = "room", direct = direct, members = listOf(Member(a.id, 0, true)), interChat = true, maxFollowUps = 10)
            val store = StateStore(state(listOf(a), listOf(target, later), room))
            val gateway = Gateway { emit(StreamEvent.Text(if (it.model.model == "a") "续答丢弃" else "继续")) }
            val engine = ChatEngine(store, gateway, this)
            assertTrue(engine.editAndSendMessage(target.id, "修改后的 assistant 正文"))
            advanceUntilIdle()
            assertEquals(listOf("a", "a-B"), gateway.requests.map { it.model.model })
            assertEquals(gateway.requests.first().messages, gateway.requests.last().messages)
            assertEquals(listOf(PromptMessage("assistant", "【本轮重新发送】\n修改后的 assistant 正文")),
                gateway.requests.first().messages.drop(1))
            assertTrue(gateway.requests.first().messages.first().content.contains("这条是你自己的发言，请以此自然继续说"))
            assertEquals("续答", gateway.requests.last().prefix)
            assertEquals(target.copy(text = "修改后的 assistant 正文"), store.snapshot().messages.first())
            assertFalse(store.snapshot().messages.any { it.id == later.id })
            assertEquals(2, store.snapshot().messages.size)
            assertNotEquals(target.id, store.snapshot().messages.last().id)
            assertEquals("续答继续", store.snapshot().messages.last().text)
            assertEquals(a.id, store.snapshot().messages.last().characterId)
        }
    }

    @Test fun resendingSharesOnlyItsTriggerWithNewMembersAndKeepsPeerFollowupsBounded() = runTest {
        val a = actor("a")
        val b = actor("b")
        val c = actor("c")
        val target = ChatMessage(id = "target", roomId = "room", characterId = a.id, senderName = a.name, text = "旧原文", createdAt = 1)
        val hidden = ChatMessage(id = "hidden", roomId = "room", text = "入群前保密消息", createdAt = 2)
        val foreign = ChatMessage(id = "foreign", roomId = "other", text = "另一群私密消息", createdAt = 200)
        val visible = ChatMessage(id = "visible", roomId = "room", text = "入群后背景消息", createdAt = 200)
        val room = ChatRoom(id = "room", members = listOf(a, b, c).map { Member(it.id, 100, false) },
            interChat = true, maxFollowUps = 20, userName = "此群用户", userBackground = "此群身份背景")
        val store = StateStore(state(listOf(a, b, c), listOf(target, hidden, foreign, visible), room))
        val gateway = Gateway { emit(StreamEvent.Text("接话-${it.model.model}")) }
        val engine = ChatEngine(store, gateway, this, now = { 300 })
        assertTrue(engine.editAndSendMessage(target.id, "本次明确分享的消息"))
        advanceUntilIdle()
        assertEquals(listOf("b", "c", "b", "c"), gateway.requests.map { it.model.model })
        gateway.requests.forEach { request ->
            val contents = request.messages.joinToString { it.content }
            assertFalse(contents.contains(hidden.text))
            assertFalse(contents.contains(foreign.text))
            assertFalse(contents.contains(visible.text))
            assertTrue(request.messages.contains(PromptMessage("user", "【本轮重新发送】\n【a】本次明确分享的消息")))
            assertTrue(request.messages.first().content.contains("此群身份背景"))
        }
        assertEquals(target.createdAt, store.snapshot().messages.first().createdAt)
        assertEquals(foreign, store.snapshot().messages[1])
        assertFalse(store.snapshot().messages.any { it.id == hidden.id || it.id == visible.id })
        assertTrue(store.snapshot().messages.drop(2).all { target.id in it.contextMessageIds && hidden.id !in it.contextMessageIds && foreign.id !in it.contextMessageIds })
    }

    @Test fun invalidRequestsAndSaveOnlyNeverStartARoundWhileRewindClearsDiscardedReplyCooldown() = runTest {
        val target = ChatMessage(id = "target", roomId = "room", text = "原问题")
        val orphan = ChatMessage(id = "orphan", roomId = "missing", text = "没有会话")
        val a = actor("a").copy(cooldownSec = 100)
        val store = StateStore(state(listOf(a), listOf(target, orphan)))
        val gateway = Gateway { emit(StreamEvent.Text("一次回复")) }
        val speech = Speech()
        val engine = ChatEngine(store, gateway, this, speech, now = { 1_000 })
        assertFalse(engine.editAndSendMessage(target.id, " \n\t"))
        assertFalse(engine.editAndSendMessage("missing", "内容"))
        assertFalse(engine.editAndSendMessage(orphan.id, "内容"))
        assertTrue(speech.stops.isEmpty())
        assertEquals(listOf(target, orphan), store.snapshot().messages)
        assertTrue(engine.editMessage(target.id, "只保存"))
        advanceUntilIdle()
        assertTrue(gateway.requests.isEmpty())
        assertFalse(engine.editMessage(target.id, "只保存"))
        assertTrue(engine.editAndSendMessage(target.id, "只保存"))
        advanceUntilIdle()
        assertEquals(1, gateway.requests.size)
        assertTrue(engine.editAndSendMessage(target.id, "删除的回复不再占用冷却"))
        advanceUntilIdle()
        assertEquals(2, gateway.requests.size)
        assertEquals("删除的回复不再占用冷却", store.snapshot().messages.first().text)
        assertEquals(orphan, store.snapshot().messages[1])
    }

    @Test fun editingAndSendingCancelsTheOldDualTurnAndAudioWithoutLettingLateDeltasOrBReturn() = runTest {
        val a = actor("a").copy(dualModel = true, cutMin = 10, cutMax = 10)
        val b = actor("b")
        val room = ChatRoom(id = "room", members = listOf(Member(a.id, 0, true), Member(b.id, 0, true)), maxInitialReplies = 1, autoSpeak = true)
        val other = ChatRoom(id = "other", members = listOf(Member(b.id, 0, true)), autoSpeak = true)
        val requests = mutableListOf<GenerationRequest>()
        val otherFinish = CompletableDeferred<Unit>()
        var oldCancelled = false
        var lateAttempted = false
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest): Flow<StreamEvent> = object : Flow<StreamEvent> {
                override suspend fun collect(collector: FlowCollector<StreamEvent>) {
                    requests += request
                    when {
                        request.model.model == "a" -> try {
                            collector.emit(StreamEvent.Reasoning("旧 A 思考"))
                            collector.emit(StreamEvent.Text("旧前缀"))
                            awaitCancellation()
                        } finally {
                            oldCancelled = true
                            withContext(NonCancellable) {
                                delay(5_000)
                                lateAttempted = true
                                runCatching { collector.emit(StreamEvent.Reasoning("迟到思考")) }
                                runCatching { collector.emit(StreamEvent.Text("迟到正文")) }
                            }
                        }
                        request.messages.last().content.endsWith("另一群保持生成") -> {
                            collector.emit(StreamEvent.Text("另一群"))
                            otherFinish.await()
                            collector.emit(StreamEvent.Text("继续正常结束"))
                        }
                        else -> collector.emit(StreamEvent.Text("其他角色接住修改后的话"))
                    }
                }
            }
        }
        val store = StateStore(state(listOf(a, b), emptyList(), room).copy(rooms = listOf(room, other)))
        val speech = Speech()
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "开始旧一轮")
        engine.send("other", "另一群保持生成")
        runCurrent()
        val target = store.snapshot().messages.last { it.roomId == "room" }
        val foreignBefore = store.snapshot().messages.filter { it.roomId == "other" }
        val oldRoomIds = store.snapshot().messages.filter { it.roomId == "room" }.map { it.id }.toSet()
        assertTrue(engine.editAndSendMessage(target.id, "原地人工改写"))
        runCurrent()
        assertTrue(oldCancelled)
        assertEquals(setOf("other"), engine.busyRooms.value)
        assertEquals(oldRoomIds, speech.stops.last())
        assertEquals(foreignBefore, store.snapshot().messages.filter { it.roomId == "other" })
        assertEquals(target.copy(text = "原地人工改写", status = MessageStatus.COMPLETE, error = "", segments = emptyList()),
            store.snapshot().messages.single { it.id == target.id })
        val newReply = store.snapshot().messages.last { it.roomId == "room" }
        assertNotEquals(target.id, newReply.id)
        assertEquals("b", newReply.characterId)
        assertEquals(newReply.text, speech.text[newReply.id])
        assertFalse(speech.finishes.contains(target.id))
        assertEquals(listOf("a", "b", "b"), requests.map { it.model.model })

        otherFinish.complete(Unit)
        runCurrent()
        val stable = store.snapshot()
        val spoken = speech.text.toMap()
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(lateAttempted)
        assertEquals(stable, store.snapshot())
        assertEquals(spoken, speech.text)
        assertFalse(requests.any { it.model.model == "a-B" })
        assertTrue(engine.busyRooms.value.isEmpty())
    }
}
