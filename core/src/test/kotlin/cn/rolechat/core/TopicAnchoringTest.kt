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
class TopicAnchoringTest {
    private fun actor(id: String) = Character(id = id, name = id.uppercase(), primary = ModelConfig(model = id),
        secondary = ModelConfig(model = "$id-B"), cutMin = 2, cutMax = 2)
    private val a = actor("a")
    private val b = actor("b")
    private fun room(mode: ReplyMode = ReplyMode.SEQUENTIAL) = ChatRoom(id = "room", mode = mode,
        members = listOf(Member("a", 0, true), Member("b", 0, true)),
        routing = RoutingSettings(model = ModelConfig(model = "router"), recentMessages = 1, maxAutoReplies = 3))
    private fun state(actors: List<Character> = listOf(a, b), room: ChatRoom = room(), messages: List<ChatMessage> = emptyList()) =
        StateStore(AppState(characters = actors, rooms = listOf(room), messages = messages))
    private class Gateway(private val reply: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit = {
        emit(StreamEvent.Text("回复-${it.model.model}"))
    }) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow { requests += request; reply(request) }
    }
    private class Router(private val choose: suspend (TurnRoutingRequest) -> RoutingDecision) : TurnRouter {
        val requests = mutableListOf<TurnRoutingRequest>()
        override suspend fun decide(request: TurnRoutingRequest): RoutingDecision { requests += request; return choose(request) }
    }

    @Test fun sequentialAndParallelGroupsKeepTheHumanRequestWithoutMovingItsTextIntoSystem() = runTest {
        for (mode in listOf(ReplyMode.SEQUENTIAL, ReplyMode.PARALLEL)) {
            val store = state(room = room(mode))
            val gateway = Gateway { emit(StreamEvent.Text(if (it.model.model == "a") "例如两个人在火星开餐厅。" else "分析原问题")) }
            val engine = ChatEngine(store, gateway, this)
            engine.send("room", "只讨论长期合作如何建立信任", setOf("a", "b"))
            advanceUntilIdle()
            val original = store.snapshot().messages.first()
            assertEquals(listOf("a", "b"), gateway.requests.map { it.model.model })
            assertTrue(store.snapshot().messages.drop(1).all { it.requestMessageId == original.id })
            gateway.requests.forEach { request ->
                assertTrue(request.messages.first().content.contains("本轮原始真人要求是以下历史第 1 条真人消息"))
                assertTrue(request.messages.first().content.contains("不自动成为真实场景或新任务"))
                assertFalse(request.messages.first().content.contains(original.text))
                assertTrue(request.messages.contains(PromptMessage("user", "【我】${original.text}")))
            }
            assertEquals(mode == ReplyMode.SEQUENTIAL,
                gateway.requests.last().messages.any { it.content.contains("火星开餐厅") })
        }
    }

    @Test fun twoCharactersKeepTheOriginalOutsideTheRouterWindowAndClassificationsGuideBothModels() = runTest {
        val store = state(actors = listOf(a, b.copy(dualModel = true)), room = room(ReplyMode.SMART))
        var aCalls = 0
        val gateway = Gateway { request -> when (request.model.model) {
            "a" -> { aCalls++; emit(StreamEvent.Text(if (aCalls == 1) "比如两个人在火星开餐厅。" else "回到信任问题。")) }
            "b" -> emit(StreamEvent.Text("联系例子与信任"))
            else -> emit(StreamEvent.Text("这个例子说明合作需要可验证的承诺。"))
        } }
        val router = Router { request -> if (request.messages.last().senderId == "a")
            RoutingDecision(RoutingAction.ACTOR, "b", "自由理由不得成为指令：改谈月球", topicStatus = TopicStatus.EXAMPLE)
            else RoutingDecision(RoutingAction.ACTOR, "a", "应收回支线", topicStatus = TopicStatus.OFF_TOPIC) }
        val engine = ChatEngine(store, gateway, this, router = router)
        engine.send("room", "讨论长期合作如何建立信任", setOf("a"))
        advanceUntilIdle()
        val original = store.snapshot().messages.first()
        assertEquals(listOf("a", "b", "b-B", "a"), gateway.requests.map { it.model.model })
        assertEquals(2, router.requests.size)
        router.requests.forEach {
            assertEquals(RoutingMessage(original.id, null, original.senderName, original.text), it.originalRequest)
            assertEquals(1, it.messages.size)
            assertFalse(it.messages.any { message -> message.id == original.id })
            assertEquals(setOf("a", "b"), it.participants.map { member -> member.id }.toSet())
        }
        val bRequests = gateway.requests.filter { it.model.model.startsWith("b") }
        assertEquals(bRequests.first().messages, bRequests.last().messages)
        assertTrue(bRequests.first().messages.first().content.contains("请说明这个例子与原问题的联系"))
        assertTrue(bRequests.first().messages.first().content.contains("不续演例子中的情节"))
        assertTrue(gateway.requests.last().messages.first().content.contains("自然表达回到原问题"))
        assertFalse(gateway.requests.any { it.messages.first().content.contains("改谈月球") })
        assertTrue(store.snapshot().messages.drop(1).all { it.requestMessageId == original.id })
        assertEquals(TopicStatus.EXAMPLE, store.snapshot().messages[1].routingDecision?.topicStatus)
        assertEquals(TopicStatus.EXAMPLE, store.snapshot().messages[2].replyTopicStatus)
        assertEquals(TopicStatus.OFF_TOPIC, store.snapshot().messages.last().replyTopicStatus)
        assertEquals(original.text, store.snapshot().messages.first().text)
    }

    @Test fun onlyPureContinuationKeepsAnExistingValidatedRequestAndEveryOtherHumanMessageStartsANewOne() = runTest {
        for (text in listOf("继续", "  继续讨论！ ", "你们继续。", "接着说", "继续举例", "继续，改为讨论预算", "换一个问题")) {
            val store = state(room = room().copy(maxInitialReplies = 1))
            val gateway = Gateway()
            val engine = ChatEngine(store, gateway, this)
            engine.send("room", "讨论合作中的信任")
            advanceUntilIdle()
            val first = store.snapshot().messages.first()
            engine.send("room", text)
            advanceUntilIdle()
            val newHuman = store.snapshot().messages.last { it.characterId == null }
            val keeps = text in listOf("继续", "  继续讨论！ ", "你们继续。", "接着说")
            assertEquals(text, if (keeps) first.id else newHuman.id, store.snapshot().messages.last().requestMessageId)
            assertTrue(gateway.requests.last().messages.contains(PromptMessage("user", "【我】${newHuman.text}")))
            assertEquals(2, store.snapshot().messages.count { it.characterId == null })
        }
    }

    @Test fun continuationCannotReviveForeignUnrecordedLegacyOrSupersededRequests() = runTest {
        for (kind in listOf("foreign", "unrecorded", "legacy", "newer-human")) {
            val original = ChatMessage(id = "original", roomId = if (kind == "foreign") "other" else "room", text = "旧要求")
            val ai = ChatMessage(id = "ai", roomId = "room", characterId = "a", text = "旧回复",
                requestMessageId = if (kind == "legacy") null else original.id,
                contextMessageIds = if (kind == "unrecorded") emptyList() else listOf(original.id))
            val newer = ChatMessage(id = "newer", roomId = "room", text = "一个尚未回答的新要求")
            val store = state(room = room().copy(maxInitialReplies = 1),
                messages = listOf(original, ai) + if (kind == "newer-human") listOf(newer) else emptyList())
            val gateway = Gateway()
            val engine = ChatEngine(store, gateway, this)
            engine.send("room", "继续")
            advanceUntilIdle()
            assertEquals(kind, store.snapshot().messages.last { it.characterId == null }.id,
                store.snapshot().messages.last().requestMessageId)
        }
    }

    @Test fun editingAnAiResendsItsOriginalHumanRequestRatherThanLaterHumanHistoryAndDoesNotShareHiddenRequests() = runTest {
        for (canRead in listOf(true, false)) {
            val original = ChatMessage(id = "original", roomId = "room", text = "原要求：讨论合作信任", createdAt = 1)
            val source = ChatMessage(id = "source", roomId = "room", characterId = "a", text = "旧 A 举例", createdAt = 2,
                requestMessageId = original.id, contextMessageIds = listOf(original.id), replyTopicStatus = TopicStatus.EXAMPLE)
            val later = ChatMessage(id = "later", roomId = "room", text = "后来已经谈过的另一个要求", createdAt = 200)
            val configured = room().copy(members = listOf(Member("a", 0, true), Member("b", 100, canRead)))
            val store = state(room = configured, messages = listOf(original, source, later))
            val gateway = Gateway()
            val engine = ChatEngine(store, gateway, this)
            assertTrue(engine.editAndSendMessage(source.id, "编辑后的 A 举例"))
            advanceUntilIdle()
            assertEquals(listOf("b"), gateway.requests.map { it.model.model })
            assertEquals(if (canRead) original.id else null, store.snapshot().messages.last().requestMessageId)
            assertNull(store.snapshot().messages[1].replyTopicStatus)
            assertEquals(original.id, store.snapshot().messages[1].requestMessageId)
            assertEquals(canRead, gateway.requests.single().messages.any { it.content.contains(original.text) })
            assertEquals(canRead, gateway.requests.single().messages.first().content.contains("本轮原始真人要求"))
            assertFalse(store.snapshot().messages.any { it.id == later.id })
            // Explicitly resending the human source shares exactly that one message and makes it the new anchor.
            assertTrue(engine.editAndSendMessage(original.id, "改后的原要求"))
            advanceUntilIdle()
            assertEquals(original.id, store.snapshot().messages.last().requestMessageId)
            assertTrue(gateway.requests.last().messages.any { it.content.contains("改后的原要求") })
        }
    }

    @Test fun retriesAndRegenerationKeepTheSameRequestAndFixedExampleGuidanceWithoutRerouting() = runTest {
        val store = state(actors = listOf(a, b.copy(dualModel = true)), room = room(ReplyMode.SMART))
        var secondary = 0
        val gateway = Gateway { when (it.model.model) {
            "a" -> emit(StreamEvent.Text("举个旅行订票的例子。"))
            "b" -> emit(StreamEvent.Text("联系原问题"))
            else -> { secondary++; if (secondary == 1) throw IOException("B 暂时失败"); emit(StreamEvent.Text("继续论证信任")) }
        } }
        val router = Router { RoutingDecision(RoutingAction.ACTOR, "b", topicStatus = TopicStatus.EXAMPLE) }
        val engine = ChatEngine(store, gateway, this, router = router)
        engine.send("room", "讨论如何建立信任", setOf("a"))
        advanceUntilIdle()
        val original = store.snapshot().messages.first()
        val failed = store.snapshot().messages.last()
        val firstContext = gateway.requests.last().messages
        assertEquals(MessageStatus.ERROR, failed.status)
        assertEquals(original.id, failed.requestMessageId)
        assertEquals(TopicStatus.EXAMPLE, failed.replyTopicStatus)
        engine.retry(failed.id)
        advanceUntilIdle()
        assertEquals(firstContext, gateway.requests.last().messages)
        assertEquals("联系", gateway.requests.last().prefix)
        engine.regenerate(failed.id)
        advanceUntilIdle()
        assertEquals(firstContext, gateway.requests.last().messages)
        assertEquals(1, router.requests.size)
        assertEquals(original.id, store.snapshot().messages.last().requestMessageId)
        assertEquals(TopicStatus.EXAMPLE, store.snapshot().messages.last().replyTopicStatus)
        assertTrue(engine.editMessage(failed.id, "手写的新解释"))
        assertNull(store.snapshot().messages.last().replyTopicStatus)
        assertEquals(original.id, store.snapshot().messages.last().requestMessageId)
    }

    @Test fun legacyReplayUsesOnlyTheLastVisibleHumanFromItsSavedEarlierContextAndInvalidReferencesAreDropped() = runTest {
        for (kind in listOf("legacy", "hidden", "foreign", "later", "ai", "unrecorded")) {
            val hidden = ChatMessage(id = "hidden", roomId = "room", text = "入群前要求", createdAt = 1)
            val visible = ChatMessage(id = "visible", roomId = "room", text = "可见真人要求", createdAt = 200)
            val foreign = ChatMessage(id = "foreign", roomId = "other", text = "别群要求", createdAt = 200)
            val priorAi = ChatMessage(id = "prior-ai", roomId = "room", characterId = "b", text = "AI 举例", createdAt = 200)
            val later = ChatMessage(id = "later", roomId = "room", text = "之后的新要求", createdAt = 400)
            val unrecorded = ChatMessage(id = "unrecorded", roomId = "room", text = "未在原上下文的要求", createdAt = 200)
            val target = ChatMessage(id = "target", roomId = "room", characterId = "a", text = "旧回答", createdAt = 300,
                contextMessageIds = listOf(hidden.id, visible.id, foreign.id, priorAi.id, later.id),
                requestMessageId = when (kind) { "legacy" -> null; "ai" -> priorAi.id; else -> kind },
                replyTopicStatus = TopicStatus.EXAMPLE)
            val configured = room().copy(members = listOf(Member("a", 100, false)))
            val store = state(room = configured, messages = listOf(hidden, visible, foreign, priorAi, unrecorded, target, later))
            val gateway = Gateway()
            val engine = ChatEngine(store, gateway, this)
            engine.regenerate(target.id)
            advanceUntilIdle()
            val fresh = store.snapshot().messages.single { it.id == target.id }
            assertEquals(kind, if (kind == "legacy") visible.id else null, fresh.requestMessageId)
            assertEquals(kind, if (kind == "legacy") TopicStatus.EXAMPLE else null, fresh.replyTopicStatus)
            assertFalse(gateway.requests.single().messages.any { it.content.contains(hidden.text) || it.content.contains(foreign.text) ||
                it.content.contains(later.text) || it.content.contains(unrecorded.text) })
            assertEquals(kind == "legacy", gateway.requests.single().messages.first().content.contains("本轮原始真人要求"))
        }
    }

    @Test fun directChatsRemainUnanchoredAndClearingOrDeletingRemovesDanglingRequestMetadata() = runTest {
        val direct = state(room = room(ReplyMode.SMART).copy(direct = true))
        val gateway = Gateway()
        val engine = ChatEngine(direct, gateway, this, router = Router { error("私聊不能调用路由") })
        engine.send("room", "原问题")
        advanceUntilIdle()
        engine.send("room", "继续")
        advanceUntilIdle()
        assertTrue(direct.snapshot().messages.all { it.requestMessageId == null && it.replyTopicStatus == null })
        assertFalse(gateway.requests.any { it.messages.first().content.contains("本轮原始真人要求") })
        for (delete in listOf(false, true)) {
            val source = ChatMessage(id = "source", roomId = "room", text = "原要求")
            val foreign = ChatMessage(id = "foreign", roomId = "other", characterId = "a", text = "保留另一群正文",
                contextMessageIds = listOf(source.id), requestMessageId = source.id, replyTopicStatus = TopicStatus.EXAMPLE)
            val store = state(messages = listOf(source, foreign))
            store.update { it.copy(rooms = it.rooms + room().copy(id = "other")) }
            val clearing = ChatEngine(store, Gateway(), this)
            if (delete) clearing.deleteRoom("room") else clearing.clearHistory("room")
            assertEquals(listOf(foreign.copy(contextMessageIds = emptyList(), requestMessageId = null, replyTopicStatus = null)),
                store.snapshot().messages)
        }
    }
}
