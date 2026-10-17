package cn.rolechat.core

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompressionEngineTest {
    private val actor = Character(id = "a", name = "A", prompt = "角色人设专用标记", worldId = "actor-world",
        primary = ModelConfig(model = "primary"), secondary = ModelConfig(model = "secondary"), cutMin = 2, cutMax = 2)
    private val room = ChatRoom(id = "room", members = listOf(Member("a", 0, true)), worldId = "room-world")
    private val settings = CompressionSettings(keepRecentMessages = 1)
    private fun message(index: Int) = ChatMessage(id = "m$index", roomId = "room",
        characterId = if (index % 2 == 0) "a" else null, senderName = if (index % 2 == 0) "A" else "我",
        text = "历史原文-$index", createdAt = index.toLong())
    private fun state(configuredRoom: ChatRoom = room, actors: List<Character> = listOf(actor),
                      messages: List<ChatMessage> = (1..5).map(::message)) = AppState(
        characters = actors, rooms = listOf(configuredRoom), messages = messages,
        worlds = listOf(World("room-world", "群设定", "群世界观专用标记"), World("actor-world", "角色设定", "角色世界观专用标记")))
    private fun compress(state: AppState, texts: Map<String, String>? = null): AppState {
        val plans = buildCompressionPlans(state, "room", settings)
        return applyCompressionDrafts(state, settings, plans, texts ?: plans.associate { it.characterId to "前情摘要-${it.characterId}" })
    }
    private class Gateway(private val reply: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit = {
        emit(StreamEvent.Text("完整回复"))
    }) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow { requests += request; reply(request) }
    }

    @Test fun bothModelsRetryAndRegenerationUseOneHistoricalReferenceWithoutReplacingStoredContextIds() = runTest {
        val initial = compress(state(actors = listOf(actor.copy(dualModel = true))))
        val store = StateStore(initial)
        var secondaryCalls = 0
        val gateway = Gateway {
            if (it.model.model == "secondary" && ++secondaryCalls == 1) throw IOException("第二模型暂时不可用")
            emit(StreamEvent.Text(if (it.model.model == "primary") "你好朋友" else "朋友你好"))
        }
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "本次新要求")
        advanceUntilIdle()
        val first = gateway.requests.first().messages
        val target = store.snapshot().messages.last()
        assertEquals(MessageStatus.ERROR, target.status)
        assertEquals(first, gateway.requests.last().messages)
        assertEquals("user", first[1].role)
        assertTrue(first[1].content.contains("前情摘要-a"))
        assertFalse(first.first().content.contains("前情摘要-a"))
        assertFalse(first.any { it.content.contains("历史原文-1") || it.content.contains("历史原文-4") })
        assertTrue(first.any { it.content.contains("历史原文-5") })
        assertTrue(first.first().content.contains("以下历史第 3 条真人消息"))
        assertEquals(initial.messages.map { it.id } + store.snapshot().messages.last { it.characterId == null }.id,
            target.contextMessageIds)
        engine.retry(target.id)
        advanceUntilIdle()
        assertEquals(first, gateway.requests.last().messages)
        assertEquals("你好", gateway.requests.last().prefix)
        engine.regenerate(target.id)
        advanceUntilIdle()
        assertEquals(first, gateway.requests.last().messages)
        assertEquals(5, gateway.requests.size)
        assertEquals(initial.messages, store.snapshot().messages.take(5))
    }

    @Test fun editingASourceInvalidatesTheSummaryAndClearingOnlyTheSummaryRestoresOriginalHistory() = runTest {
        for (edit in listOf(true, false)) {
            val store = StateStore(compress(state()))
            val gateway = Gateway()
            val engine = ChatEngine(store, gateway, this)
            if (edit) assertTrue(engine.editMessage("m2", "修改后的历史原文"))
            else store.update { it.copy(contextSummaries = emptyList()) }
            engine.send("room", "接着聊新问题")
            advanceUntilIdle()
            val context = gateway.requests.single().messages
            assertFalse(context.any { it.content.contains("前情摘要-a") })
            assertTrue(context.any { it.content.contains(if (edit) "修改后的历史原文" else "历史原文-2") })
            assertTrue(context.any { it.content.contains("历史原文-1") })
        }
    }

    @Test fun aNewerSummaryCannotSmuggleLaterRepliesIntoAnOldReplay() = runTest {
        val old = (1..3).map(::message)
        val target = ChatMessage(id = "target", roomId = "room", characterId = "a", senderName = "A", text = "旧回复",
            createdAt = 4, contextMessageIds = old.map { it.id })
        val later = ChatMessage(id = "later", roomId = "room", text = "未来秘密要求", createdAt = 5)
        val tail = ChatMessage(id = "tail", roomId = "room", characterId = "a", text = "未来秘密结果", createdAt = 6)
        val original = compress(state(messages = old + target + later + tail), mapOf("a" to "未来秘密进入新摘要"))
        val store = StateStore(original)
        val gateway = Gateway()
        val engine = ChatEngine(store, gateway, this)
        engine.regenerate(target.id)
        advanceUntilIdle()
        val context = gateway.requests.single().messages
        assertFalse(context.any { it.content.contains("未来秘密") || it.content.contains("旧回复") })
        old.forEach { message -> assertTrue(context.any { it.content.contains(message.text) }) }
        assertEquals(old.map { it.id }, store.snapshot().messages.single { it.id == target.id }.contextMessageIds)
        assertEquals(listOf(later, tail), store.snapshot().messages.takeLast(2))
    }

    @Test fun smartResendPinsTheOriginalRequirementAndReplyTargetAsOriginalMessagesInsideTheCompressedPrefix() = runTest {
        val original = message(1).copy(text = "原始要求：讨论信任")
        val source = message(2).copy(text = "A 的例子", requestMessageId = original.id, contextMessageIds = listOf(original.id))
        val other = actor.copy(id = "b", name = "B", primary = ModelConfig(model = "b"))
        val smart = room.copy(mode = ReplyMode.SMART, members = room.members + Member("b", 0, true),
            routing = RoutingSettings(maxAutoReplies = 1))
        val store = StateStore(compress(state(smart, listOf(actor, other), listOf(original, source, message(3)))))
        val gateway = Gateway()
        val router = object : TurnRouter {
            override suspend fun decide(request: TurnRoutingRequest) = RoutingDecision(RoutingAction.ACTOR, "b")
        }
        val engine = ChatEngine(store, gateway, this, router = router)
        assertTrue(engine.editAndSendMessage(source.id, source.text))
        advanceUntilIdle()
        val request = gateway.requests.single()
        assertEquals("b", request.model.model)
        assertFalse(request.messages.any { it.content.contains("前情摘要-b") })
        assertTrue(store.snapshot().contextSummaries.isEmpty())
        assertTrue(request.messages[1].content.contains(original.text))
        assertTrue(request.messages[2].content.contains("【本轮重新发送】"))
        assertTrue(request.messages[2].content.contains("【本次回应】"))
        assertTrue(request.messages[2].content.contains(source.text))
        assertEquals("user", request.messages[2].role)
        assertTrue(request.messages.first().content.contains("本轮原始真人要求是以下历史第 1 条"))
        assertTrue(request.messages.first().content.contains("你本次回应以下历史第 2 条"))
        assertFalse(request.messages.first().content.contains(original.text))
        val fresh = store.snapshot().messages.last()
        assertEquals(listOf("m1", "m2"), fresh.contextMessageIds)
        assertEquals(original.id, fresh.requestMessageId)
        assertEquals(source.id, fresh.replyToMessageId)
        engine.regenerate(fresh.id)
        advanceUntilIdle()
        assertEquals(request.messages, gateway.requests.last().messages)
    }

    @Test fun differentMembersOnlyReceiveTheirOwnSummaryAndNewPermissionsCannotReuseOldPrivateKnowledge() = runTest {
        val other = actor.copy(id = "b", name = "B", primary = ModelConfig(model = "b"))
        val configured = room.copy(members = room.members + Member("b", 3, false), maxInitialReplies = 1)
        val store = StateStore(compress(state(configured, listOf(actor, other)), mapOf("a" to "早期秘密摘要", "b" to "入群后公共摘要")))
        val gateway = Gateway()
        val engine = ChatEngine(store, gateway, this)
        engine.send("room", "新要求", setOf("a", "b"))
        advanceUntilIdle()
        assertTrue(gateway.requests.first().messages.any { it.content.contains("早期秘密摘要") })
        assertTrue(gateway.requests.last().messages.any { it.content.contains("入群后公共摘要") })
        assertFalse(gateway.requests.last().messages.any { it.content.contains("早期秘密摘要") || it.content.contains("历史原文-1") })
        store.update { state -> state.copy(rooms = state.rooms.map { it.copy(members = it.members.map { member ->
            if (member.characterId == "a") member.copy(joinedAt = 3, readHistory = false) else member }) }) }
        engine.send("room", "另一个新要求", setOf("a"))
        advanceUntilIdle()
        val changed = gateway.requests.last().messages
        assertFalse(changed.any { it.content.contains("早期秘密摘要") || it.content.contains("历史原文-1") })
        assertTrue(changed.any { it.content.contains("历史原文-3") })
    }

    @Test fun clearAndDeleteRemoveOnlyTheAffectedSummariesAndFreshGenerationReadsNoOldHistory() = runTest {
        for (delete in listOf(false, true)) {
            val initial = compress(state())
            val secondRoom = room.copy(id = "other")
            val secondMessages = initial.messages.map { it.copy(id = "other-${it.id}", roomId = "other") }
            val both = initial.copy(rooms = initial.rooms + secondRoom, messages = initial.messages + secondMessages)
            val plans = buildCompressionPlans(both, "other", settings)
            val original = applyCompressionDrafts(both, settings, plans, mapOf("a" to "另一个群的前情"))
            val store = StateStore(original)
            val gateway = Gateway()
            val engine = ChatEngine(store, gateway, this)
            if (delete) engine.deleteRoom("room") else engine.clearHistory("room")
            assertEquals(original.contextSummaries.filter { it.roomId == "other" }, store.snapshot().contextSummaries)
            assertEquals(secondMessages, store.snapshot().messages)
            assertEquals(listOf(actor), store.snapshot().characters)
            if (delete) assertFalse(store.snapshot().rooms.any { it.id == "room" })
            else {
                engine.send("room", "清空后的消息")
                advanceUntilIdle()
                assertFalse(gateway.requests.single().messages.any { it.content.contains("前情摘要") || it.content.contains("历史原文") })
            }
        }
    }

    @Test fun actualDirectAndGroupRequestsChooseTheRoomWorldOrActorFallbackWithoutMergingAndAlwaysKeepPersona() = runTest {
        for (direct in listOf(false, true)) for (roomWorld in listOf("room-world", null)) {
            val store = StateStore(compress(state(room.copy(direct = direct, worldId = roomWorld))))
            val gateway = Gateway()
            ChatEngine(store, gateway, this).send("room", "新的提问")
            advanceUntilIdle()
            val request = gateway.requests.single()
            val system = request.messages.first().content
            assertTrue(system.contains("角色人设专用标记"))
            assertEquals(roomWorld != null, system.contains("群世界观专用标记"))
            assertEquals(roomWorld == null, system.contains("角色世界观专用标记"))
            assertFalse(system.contains("前情摘要-a"))
            assertTrue(request.messages.any { it.role == "user" && it.content.contains("前情摘要-a") })
            assertFalse(request.messages.any { it.content.contains("历史原文-1") })
        }
    }
}
