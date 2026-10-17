package cn.rolechat.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineTest {
    private fun actor(id: String, dual: Boolean = false) = Character(id = id, name = id,
        prompt = "$id-private-prompt", primary = ModelConfig(model = id),
        secondary = ModelConfig(model = "$id-B"), dualModel = dual, cutMin = 2, cutMax = 2,
        firstTextTimeoutSec = 2, stallTimeoutSec = 1)

    private fun state(actors: List<Character>, mode: ReplyMode = ReplyMode.SEQUENTIAL,
                      room: ChatRoom? = null, messages: List<ChatMessage> = emptyList()) = AppState(
        characters = actors, rooms = listOf(room ?: ChatRoom(id = "room", name = "test",
            members = actors.map { Member(it.id, 0, true) }, mode = mode)), messages = messages)

    private class FakeGateway(val respond: suspend kotlinx.coroutines.flow.FlowCollector<StreamEvent>.(GenerationRequest) -> Unit) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow {
            requests += request
            respond(request)
        }
    }
    private class RecordingSpeech : SpeechSink {
        val starts = mutableListOf<Pair<String, String>>()
        val text = mutableMapOf<String, String>()
        val finishes = mutableListOf<String>()
        val stops = mutableListOf<Set<String>>()
        override fun begin(messageId: String, character: Character) { starts += messageId to character.id }
        override fun append(messageId: String, text: String) { this.text[messageId] = this.text[messageId].orEmpty() + text }
        override fun finish(messageId: String) { finishes += messageId }
        override fun stop(roomMessageIds: Set<String>) { stops += roomMessageIds }
    }

    @Test fun sequentialRepliesSeeEarlierCompletedReplies() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") gate.await()
            emit(StreamEvent.Text("reply-${request.model.model}"))
        }
        val store = StateStore(state(listOf(actor("a"), actor("b"))))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "hello")
        runCurrent()
        assertEquals(listOf("a"), gateway.requests.map { it.model.model })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), gateway.requests.map { it.model.model })
        assertTrue(gateway.requests[1].messages.any { it.role == "user" && it.content == "【a】reply-a" })
        assertTrue(engine.busyRooms.value.isEmpty())
    }

    @Test fun parallelUsesFrozenHistoryAndStablePlaceholderOrder() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") gate.await()
            emit(StreamEvent.Text("reply-${request.model.model}"))
        }
        val speech = RecordingSpeech()
        val initial = state(listOf(actor("a"), actor("b")), ReplyMode.PARALLEL)
        val store = StateStore(initial.copy(rooms = initial.rooms.map { it.copy(autoSpeak = true) },
            tts = TtsSettings(enabled = true)))
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "hello")
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertEquals(listOf("a", "b"), store.snapshot().messages.filter { it.characterId != null }.map { it.characterId })
        assertEquals(listOf("a", "b"), speech.starts.map { it.second })
        gateway.requests.forEach { request -> assertFalse(request.messages.any { "reply-" in it.content }) }
        gate.complete(Unit)
        advanceUntilIdle()
        val replies = store.snapshot().messages.filter { it.characterId != null }
        assertEquals(replies[0].contextMessageIds, replies[1].contextMessageIds)
        assertEquals(2, speech.finishes.size)
    }

    @Test fun contextContainsOnlyActorPromptAndVisibleConversationHistory() = runTest {
        val a = actor("a")
        val room = ChatRoom(id = "room", members = listOf(Member("a", 100, false)))
        val messages = listOf(
            ChatMessage(id = "old", roomId = "room", text = "before-join-secret", createdAt = 99),
            ChatMessage(id = "private", roomId = "elsewhere", text = "other-room-secret", createdAt = 200),
            ChatMessage(id = "own", roomId = "room", characterId = "a", text = "own-visible", createdAt = 200),
            ChatMessage(id = "other", roomId = "room", characterId = "b", senderName = "b", text = "peer-visible", createdAt = 201))
        val store = StateStore(state(listOf(a, actor("b")), room = room, messages = messages))
        val gateway = FakeGateway { emit(StreamEvent.Text("ok")) }
        val engine = ChatEngine(store, gateway, this, now = { 300 })
        engine.send("room", "new")
        advanceUntilIdle()
        val request = gateway.requests.single()
        val all = request.messages.joinToString { it.content }
        assertTrue("a-private-prompt" in all)
        listOf("b-private-prompt", "before-join-secret", "other-room-secret").forEach { assertFalse(it in all) }
        assertTrue(request.messages.any { it.role == "assistant" && it.content == "own-visible" })
        assertTrue(request.messages.any { it.role == "user" && it.content == "【b】peer-visible" })
    }

    @Test fun allMentionsBypassProbabilityCooldownAndInitialCap() = runTest {
        val actors = listOf(actor("a"), actor("b")).map { it.copy(probability = 0, cooldownSec = 100) }
        val room = ChatRoom(id = "room", members = actors.map { Member(it.id, 0, true) }, maxInitialReplies = 1)
        val store = StateStore(state(actors, room = room))
        val gateway = FakeGateway { emit(StreamEvent.Text("ok")) }
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "ignored")
        advanceUntilIdle()
        assertTrue(gateway.requests.isEmpty())
        repeat(2) {
            engine.send("room", "both", setOf("a", "b"))
            advanceUntilIdle()
        }
        assertEquals(listOf("a", "b", "a", "b"), gateway.requests.map { it.model.model })
    }

    @Test fun directChatAlwaysRepliesAndCooldownIsPerConversation() = runTest {
        val a = actor("a").copy(probability = 0, cooldownSec = 100)
        val room = ChatRoom(id = "room", direct = true, members = listOf(Member("a", 0, true)))
        val store = StateStore(state(listOf(a), room = room).copy(rooms = listOf(room, room.copy(id = "other"))))
        val gateway = FakeGateway { emit(StreamEvent.Text("ok")) }
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "one")
        advanceUntilIdle()
        engine.send("other", "two")
        advanceUntilIdle()
        assertEquals(2, gateway.requests.size)
        assertFalse(gateway.requests[1].messages.any { "one" in it.content })
    }

    @Test fun followUpsAreSequentialAndBoundedPerActor() = runTest {
        val actors = listOf(actor("a"), actor("b"))
        val room = ChatRoom(id = "room", members = actors.map { Member(it.id, 0, true) }, interChat = true, maxFollowUps = 20)
        val store = StateStore(state(actors, room = room))
        val gateway = FakeGateway { request -> emit(StreamEvent.Text("reply-${request.model.model}")) }
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "start")
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "a", "b"), gateway.requests.map { it.model.model })
        assertEquals(5, gateway.requests.last().messages.size)
    }

    @Test fun thinkingDoesNotCountTowardUnicodeCutoffAndSpeechOnlyReceivesCommittedText() = runTest {
        val a = actor("a", true)
        val initial = state(listOf(a))
        val store = StateStore(initial.copy(rooms = initial.rooms.map { it.copy(autoSpeak = true) }, tts = TtsSettings(enabled = true)))
        val speech = RecordingSpeech()
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") {
                emit(StreamEvent.Thinking)
                emit(StreamEvent.Reasoning("A 的思考"))
                emit(StreamEvent.Reasoning("，仅供查看"))
                delay(10)
                emit(StreamEvent.Text("😀你绝不能出现的尾巴"))
                awaitCancellation()
            } else {
                emit(StreamEvent.Reasoning("B 的思考"))
                emit(StreamEvent.Text("好"))
            }
        }
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "hello")
        runCurrent()
        assertEquals(MessageStatus.THINKING_A, store.snapshot().messages.last().status)
        assertEquals("", store.snapshot().messages.last().text)
        advanceUntilIdle()
        val reply = store.snapshot().messages.last()
        assertEquals("😀你好", reply.text)
        assertEquals("😀你", gateway.requests[1].prefix)
        assertEquals(gateway.requests[0].messages, gateway.requests[1].messages)
        assertEquals(SegmentStatus.CUT, reply.segments[0].status)
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals("😀你好", speech.text[reply.id])
        assertEquals(1, speech.starts.size)
        assertEquals(1, speech.finishes.size)
        assertEquals(listOf("A 的思考，仅供查看", "B 的思考"), reply.segments.map { it.reasoning })
        engine.send("room", "下一句")
        advanceUntilIdle()
        assertTrue(gateway.requests[2].messages.any { it.role == "assistant" && it.content == reply.text })
        gateway.requests.forEach { request ->
            assertFalse(request.messages.any { "思考" in it.content || "仅供查看" in it.content })
            assertFalse(request.prefix.contains("思考"))
        }
    }

    @Test fun bStartsWithoutWaitingForACancellationAcknowledgement() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") {
                try {
                    emit(StreamEvent.Text("前缀后面"))
                    awaitCancellation()
                } finally { withContext(NonCancellable) { delay(5_000) } }
            } else emit(StreamEvent.Text("续写"))
        }
        val store = StateStore(state(listOf(actor("a", true))))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "hello")
        runCurrent()
        assertEquals(listOf("a", "a-B"), gateway.requests.map { it.model.model })
        assertEquals("前缀续写", store.snapshot().messages.last().text)
        assertEquals(0L, testScheduler.currentTime)
        advanceUntilIdle()
    }

    @Test fun perpetualThinkingTimesOutToBWithoutPrefix() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") {
                while (true) { emit(StreamEvent.Thinking); delay(200) }
            } else emit(StreamEvent.Text("从头回答"))
        }
        val store = StateStore(state(listOf(actor("a", true))))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "hello")
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertEquals("", gateway.requests[1].prefix)
        assertEquals("从头回答", store.snapshot().messages.last().text)
        assertEquals("等待正文超时", store.snapshot().messages.last().segments.first().note)
    }

    @Test fun streamThatNeverStartsAlsoTimesOut() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") awaitCancellation() else emit(StreamEvent.Text("B"))
        }
        val store = StateStore(state(listOf(actor("a", true))))
        ChatEngine(store, gateway, this).send("room", "hello")
        advanceUntilIdle()
        assertEquals(2, gateway.requests.size)
        assertEquals("B", store.snapshot().messages.last().text)
    }

    @Test fun earlyFinishErrorAndStalledBodyAllContinueWithAvailablePrefix() = runTest {
        for (kind in listOf("end", "error", "stall")) {
            val gateway = FakeGateway { request ->
                if (request.model.model == "a") {
                    emit(StreamEvent.Text("想"))
                    when (kind) {
                        "end" -> emit(StreamEvent.Finished("stop"))
                        "error" -> error("temporary failure")
                        else -> { emit(StreamEvent.Thinking); awaitCancellation() }
                    }
                } else emit(StreamEvent.Text("好了"))
            }
            val store = StateStore(state(listOf(actor("a", true))))
            ChatEngine(store, gateway, this).send("room", kind)
            advanceUntilIdle()
            assertEquals(kind, "想", gateway.requests[1].prefix)
            assertEquals(kind, "想好了", store.snapshot().messages.last().text)
        }
    }

    @Test fun manualSwitchDuringThinkingStartsBWithoutWaiting() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") {
                emit(StreamEvent.Thinking)
                emit(StreamEvent.Reasoning("切换前的思考"))
                awaitCancellation()
            }
            else emit(StreamEvent.Text("B"))
        }
        val store = StateStore(state(listOf(actor("a", true))))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "hello")
        runCurrent()
        val id = store.snapshot().messages.last().id
        engine.switchToB(id)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals("B", store.snapshot().messages.last().text)
        assertEquals("切换前的思考", store.snapshot().messages.last().segments.first().reasoning)
        assertEquals("", gateway.requests.last().prefix)
    }

    @Test fun stopAndNewUserMessageNeverTriggerOldB() = runTest {
        val gateway = FakeGateway {
            emit(StreamEvent.Reasoning("已经收到的思考"))
            emit(StreamEvent.Text("半"))
            awaitCancellation()
        }
        val store = StateStore(state(listOf(actor("a", true))))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "old")
        runCurrent()
        val oldId = store.snapshot().messages.last().id
        engine.send("room", "new")
        runCurrent()
        engine.stop("room")
        advanceUntilIdle()
        assertEquals(listOf("a", "a"), gateway.requests.map { it.model.model })
        assertEquals(MessageStatus.INTERRUPTED, store.snapshot().messages.first { it.id == oldId }.status)
        assertEquals("半", store.snapshot().messages.first { it.id == oldId }.text)
        assertEquals("已经收到的思考", store.snapshot().messages.first { it.id == oldId }.segments.single().reasoning)
        assertTrue(engine.busyRooms.value.isEmpty())
    }

    @Test fun retryFailedBPreservesAOnceAndUsesOriginalHistory() = runTest {
        var bAttempts = 0
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") {
                emit(StreamEvent.Reasoning("保留 A 的思考"))
                emit(StreamEvent.Text("前缀丢弃"))
            } else if (++bAttempts == 1) {
                emit(StreamEvent.Reasoning("丢弃 B 的旧思考"))
                emit(StreamEvent.Text("旧片段")); error("B failed")
            } else {
                emit(StreamEvent.Reasoning("B 的新思考"))
                emit(StreamEvent.Text("新片段"))
            }
        }
        val store = StateStore(state(listOf(actor("a", true).copy(messageStyle = MessageStyle.SCENE))))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "original")
        advanceUntilIdle()
        val original = store.snapshot().messages.last()
        assertEquals(MessageStatus.ERROR, original.status)
        store.update { it.copy(
            characters = it.characters.map { character -> character.copy(messageStyle = MessageStyle.CHAT) },
            messages = it.messages + ChatMessage(roomId = "room", text = "later message")) }
        engine.retry(original.id)
        advanceUntilIdle()
        val reply = store.snapshot().messages.first { it.id == original.id }
        assertEquals(listOf("a", "a-B", "a-B"), gateway.requests.map { it.model.model })
        assertEquals("前缀新片段", reply.text)
        assertEquals(MessageStyle.SCENE, reply.messageStyle)
        assertEquals(original.senderName, reply.senderName)
        assertEquals(listOf("A", "B"), reply.segments.map { it.label })
        assertEquals(original.segments.first(), reply.segments.first())
        assertEquals(listOf("保留 A 的思考", "B 的新思考"), reply.segments.map { it.reasoning })
        assertEquals(gateway.requests[1].messages, gateway.requests[2].messages)
        assertFalse(gateway.requests[2].messages.any { "later message" in it.content })
    }

    @Test fun messagePresentationIsSnapshottedBeforeStreamingAndOnlyFutureMessagesFollowRoleEdits() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") gate.await()
            emit(StreamEvent.Text("reply-${request.model.model}"))
        }
        val store = StateStore(state(listOf(actor("a").copy(messageStyle = MessageStyle.SCENE), actor("b")), ReplyMode.PARALLEL))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "first")
        runCurrent()
        val originalId = store.snapshot().messages.first { it.characterId == "a" }.id
        assertEquals(listOf(MessageStyle.CHAT, MessageStyle.SCENE, MessageStyle.CHAT), store.snapshot().messages.map { it.messageStyle })
        assertEquals(MessageStatus.GENERATING_A, store.snapshot().messages.first { it.id == originalId }.status)

        store.update { state -> state.copy(characters = state.characters.map {
            if (it.id == "a") it.copy(name = "改名后的普通角色", messageStyle = MessageStyle.CHAT) else it
        }) }
        gate.complete(Unit)
        advanceUntilIdle()
        val completed = store.snapshot().messages.first { it.id == originalId }
        assertEquals(MessageStatus.COMPLETE, completed.status)
        assertEquals("a", completed.senderName)
        assertEquals(MessageStyle.SCENE, completed.messageStyle)

        engine.send("room", "second")
        advanceUntilIdle()
        val newReply = store.snapshot().messages.last { it.characterId == "a" }
        assertEquals("改名后的普通角色", newReply.senderName)
        assertEquals(MessageStyle.CHAT, newReply.messageStyle)
        assertTrue(store.snapshot().messages.filter { it.characterId == null }.all { it.messageStyle == MessageStyle.CHAT })
        store.update { state -> state.copy(characters = state.characters.filterNot { it.id == "a" },
            rooms = state.rooms.map { room -> room.copy(members = room.members.filterNot { it.characterId == "a" }) }) }
        assertEquals(completed, store.snapshot().messages.first { it.id == originalId })
        assertEquals(newReply, store.snapshot().messages.first { it.id == newReply.id })
    }

    @Test fun sceneStyleDoesNotChangeModelRequestsHistoryRolesOrDualModelSpeech() = runTest {
        val captured = mutableListOf<List<GenerationRequest>>()
        for (style in MessageStyle.entries) {
            val character = actor("a", true).copy(messageStyle = style)
            val room = ChatRoom(id = "room", members = listOf(Member("a", 0, true)), autoSpeak = true)
            val initial = state(listOf(character), room = room, messages = listOf(
                ChatMessage(id = "own", roomId = "room", characterId = "a", senderName = "a", text = "自己的上一句", messageStyle = style),
                ChatMessage(id = "other", roomId = "room", characterId = "other", senderName = "别的角色", text = "其他人的上一句", messageStyle = style)
            )).copy(tts = TtsSettings(enabled = true))
            val store = StateStore(initial)
            val speech = RecordingSpeech()
            val gateway = FakeGateway { request ->
                emit(StreamEvent.Text(if (request.model.model == "a") "前缀被截断" else "接续"))
            }
            ChatEngine(store, gateway, this, speech).send("room", "现在呢")
            advanceUntilIdle()
            val reply = store.snapshot().messages.last()
            assertEquals(style, reply.messageStyle)
            assertEquals(MessageStatus.COMPLETE, reply.status)
            assertEquals("前缀接续", reply.text)
            assertEquals(listOf(reply.id to "a"), speech.starts)
            assertEquals(listOf(reply.id), speech.finishes)
            assertEquals(reply.text, speech.text[reply.id])
            gateway.requests.forEach { request ->
                assertEquals("system", request.messages.first().role)
                assertTrue(request.messages.any { it.role == "assistant" && it.content == "自己的上一句" })
                assertTrue(request.messages.any { it.role == "user" && it.content == "【别的角色】其他人的上一句" })
            }
            captured += gateway.requests.toList()
        }
        assertEquals(2, captured.first().size)
        assertEquals(captured.first(), captured.last())
    }

    @Test fun echoPrefixIsRemovedAcrossChunksOnlyWhenConfigured() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") emit(StreamEvent.Text("前缀超长"))
            else { emit(StreamEvent.Text("前")); emit(StreamEvent.Text("缀续")); emit(StreamEvent.Text("写")) }
        }
        val initial = state(listOf(actor("a", true)))
        val store = StateStore(initial.copy(providers = initial.providers.map { it.copy(echoPrefix = true) }))
        ChatEngine(store, gateway, this).send("room", "hello")
        advanceUntilIdle()
        assertEquals("前缀续写", store.snapshot().messages.last().text)
    }

    @Test fun outputTokenLimitIsMarkedInterruptedRatherThanComplete() = runTest {
        val gateway = FakeGateway { emit(StreamEvent.Text("unfinished")); emit(StreamEvent.Finished("length")) }
        val store = StateStore(state(listOf(actor("a"))))
        ChatEngine(store, gateway, this).send("room", "hello")
        advanceUntilIdle()
        assertEquals(MessageStatus.INTERRUPTED, store.snapshot().messages.last().status)
        assertTrue(store.snapshot().messages.last().error.contains("输出上限"))
    }

    @Test fun emptySecondaryReplyKeepsPrefixButReportsFailure() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") emit(StreamEvent.Text("前缀超长"))
            else emit(StreamEvent.Finished("stop"))
        }
        val store = StateStore(state(listOf(actor("a", true))))
        ChatEngine(store, gateway, this).send("room", "hello")
        advanceUntilIdle()
        val reply = store.snapshot().messages.last()
        assertEquals("前缀", reply.text)
        assertEquals(MessageStatus.ERROR, reply.status)
        assertTrue(reply.error.contains("接续模型未返回正文"))
    }

    @Test fun groupCooldownDoesNotLeakIntoAnotherRoom() = runTest {
        val a = actor("a").copy(cooldownSec = 100)
        val room = ChatRoom(id = "room", members = listOf(Member("a", 0, true)))
        val initial = state(listOf(a), room = room)
        val store = StateStore(initial.copy(rooms = listOf(room, room.copy(id = "other"))))
        val gateway = FakeGateway { emit(StreamEvent.Text("ok")) }
        val engine = ChatEngine(store, gateway, this, now = { 1_000L })
        engine.send("room", "first")
        advanceUntilIdle()
        engine.send("room", "cooling")
        advanceUntilIdle()
        assertEquals(1, gateway.requests.size)
        engine.send("other", "fresh room")
        advanceUntilIdle()
        assertEquals(2, gateway.requests.size)
    }

    @Test fun ambiguousPartialEchoAtEofIsPreservedExactlyOnce() = runTest {
        for (endWithError in listOf(false, true)) {
            val gateway = FakeGateway { request ->
                if (request.model.model == "a") emit(StreamEvent.Text("前缀更多"))
                else {
                    emit(StreamEvent.Text("前"))
                    if (endWithError) error("early disconnect")
                }
            }
            val initial = state(listOf(actor("a", true)))
            val store = StateStore(initial.copy(providers = initial.providers.map { it.copy(echoPrefix = true) }))
            ChatEngine(store, gateway, this).send("room", "hello")
            advanceUntilIdle()
            val reply = store.snapshot().messages.last()
            assertEquals("前缀前", reply.text)
            assertEquals("前", reply.segments.last().text)
            assertEquals(if (endWithError) MessageStatus.ERROR else MessageStatus.COMPLETE, reply.status)
        }
    }

    @Test fun unicodeSurrogatePairSplitAcrossDeltasStillCountsAsOneCharacter() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") {
                emit(StreamEvent.Text("\uD83D"))
                emit(StreamEvent.Text("\uDE00你多余的字"))
            } else emit(StreamEvent.Text("好"))
        }
        val store = StateStore(state(listOf(actor("a", true))))
        ChatEngine(store, gateway, this).send("room", "hello")
        advanceUntilIdle()
        assertEquals("😀你", gateway.requests[1].prefix)
        assertEquals("😀你好", store.snapshot().messages.last().text)
    }

    @Test fun punctuationAtCutoffIsOmittedBeforeDisplaySpeechAndContinuation() = runTest {
        val initial = state(listOf(actor("a", true)))
        val store = StateStore(initial.copy(rooms = initial.rooms.map { it.copy(autoSpeak = true) },
            tts = TtsSettings(enabled = true)))
        val speech = RecordingSpeech()
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") emit(StreamEvent.Text("好。后面的内容"))
            else emit(StreamEvent.Text("的，我们继续聊。"))
        }
        ChatEngine(store, gateway, this, speech).send("room", "hello")
        advanceUntilIdle()
        val reply = store.snapshot().messages.last()
        assertEquals("好", gateway.requests[1].prefix)
        assertEquals("好的，我们继续聊。", reply.text)
        assertEquals(reply.text, speech.text[reply.id])
        assertEquals(SegmentStatus.CUT, reply.segments.first().status)
    }

    @Test fun eachRoomSharesItsOwnHumanIdentityWithAllRolesWithoutLeakingMessages() = runTest {
        val actors = listOf(actor("a"), actor("b"))
        val first = ChatRoom(id = "mountain", name = "山居群", members = actors.map { Member(it.id, 0, true) },
            userName = "  云岚  ", userBackground = "我是一名住在山中的药师。")
        val second = first.copy(id = "station", name = "空间站群", userName = "星舟",
            userBackground = "我是负责空间站维修的工程师。")
        val store = StateStore(state(actors, room = first).copy(rooms = listOf(first, second)))
        val gateway = FakeGateway { emit(StreamEvent.Text("收到")) }
        val engine = ChatEngine(store, gateway, this)
        engine.send(first.id, "山居的秘密消息")
        advanceUntilIdle()
        engine.send(second.id, "空间站的秘密消息")
        advanceUntilIdle()
        assertEquals(4, gateway.requests.size)
        gateway.requests.take(2).forEach { request ->
            val system = request.messages.first()
            assertEquals("system", system.role)
            assertTrue(system.content.contains("真人用户（不是你，也不是其他 AI 角色）"))
            assertTrue(system.content.contains("名字：云岚\n"))
            assertTrue(system.content.contains(first.userBackground))
            val all = request.messages.joinToString { it.content }
            assertFalse(all.contains(second.userName))
            assertFalse(all.contains(second.userBackground))
            assertFalse(all.contains("空间站的秘密消息"))
            assertTrue(request.messages.any { it.role == "user" && it.content == "【云岚】山居的秘密消息" })
        }
        gateway.requests.drop(2).forEach { request ->
            val system = request.messages.first().content
            assertTrue(system.contains("名字：星舟\n"))
            assertTrue(system.contains(second.userBackground))
            val all = request.messages.joinToString { it.content }
            assertFalse(all.contains("云岚"))
            assertFalse(all.contains(first.userBackground))
            assertFalse(all.contains("山居的秘密消息"))
            assertTrue(request.messages.any { it.role == "user" && it.content == "【星舟】空间站的秘密消息" })
        }
        assertEquals(listOf("云岚", "星舟"), store.snapshot().messages.filter { it.characterId == null }.map { it.senderName })
    }

    @Test fun aAndBKeepTheSameRoomIdentitySnapshotDuringHandoff() = runTest {
        val a = actor("a", true)
        val room = ChatRoom(id = "room", members = listOf(Member("a", 0, true)),
            userName = "旅人", userBackground = "来自南方的地图绘制者。")
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway { request ->
            if (request.model.model == "a") {
                emit(StreamEvent.Thinking)
                gate.await()
                emit(StreamEvent.Text("你好，多余的尾巴"))
            } else emit(StreamEvent.Text("，旅人。"))
        }
        val store = StateStore(state(listOf(a), room = room))
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "请介绍一下这里")
        runCurrent()
        store.update { it.copy(rooms = listOf(room.copy(userName = "后来者", userBackground = "另一份设定"))) }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, gateway.requests.size)
        assertEquals(gateway.requests[0].messages, gateway.requests[1].messages)
        gateway.requests.forEach { request ->
            val system = request.messages.first().content
            assertTrue(system.contains("名字：旅人\n"))
            assertTrue(system.contains(room.userBackground))
            assertFalse(system.contains("后来者"))
            assertFalse(system.contains("另一份设定"))
        }
        assertEquals("旅人", store.snapshot().messages.first().senderName)
    }

    @Test fun renamingTheHumanOnlyChangesFutureMessageSignaturesAndBlankFallsBackToMe() = runTest {
        val room = ChatRoom(id = "room", members = listOf(Member("a", 0, true)),
            userName = "旧名字", userBackground = "旧背景")
        val store = StateStore(state(listOf(actor("a")), room = room))
        val gateway = FakeGateway { emit(StreamEvent.Text("收到")) }
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "之前说的话")
        advanceUntilIdle()
        store.update { it.copy(rooms = listOf(room.copy(userName = " \t ", userBackground = "新背景"))) }
        engine.send("room", "现在说的话")
        advanceUntilIdle()
        assertEquals(listOf("旧名字", "我"), store.snapshot().messages.filter { it.characterId == null }.map { it.senderName })
        val second = gateway.requests.last()
        assertTrue(second.messages.first().content.contains("名字：我\n"))
        assertTrue(second.messages.first().content.contains("新背景"))
        assertFalse(second.messages.first().content.contains("旧背景"))
        assertTrue(second.messages.any { it.content == "【旧名字】之前说的话" })
        assertTrue(second.messages.any { it.content == "【我】现在说的话" })
    }
}
