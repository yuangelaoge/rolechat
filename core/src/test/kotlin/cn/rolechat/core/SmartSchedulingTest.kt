package cn.rolechat.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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

@OptIn(ExperimentalCoroutinesApi::class)
class SmartSchedulingTest {
    private fun actor(id: String) = Character(id = id, name = id.uppercase(), prompt = "private-$id",
        primary = ModelConfig(model = id), secondary = ModelConfig(model = "$id-B"), cooldownSec = 0)
    private val a = actor("a")
    private val b = actor("b")
    private val c = actor("c")
    private fun room(actors: List<Character> = listOf(a, b, c)) = ChatRoom(id = "room", mode = ReplyMode.SMART,
        members = actors.map { Member(it.id, 0, true) }, autoSpeak = true,
        routing = RoutingSettings(model = ModelConfig(model = "router"), maxAutoReplies = 6))
    private fun store(actors: List<Character> = listOf(a, b, c), room: ChatRoom = room(actors),
                      messages: List<ChatMessage> = emptyList()) = StateStore(AppState(characters = actors,
        rooms = listOf(room), messages = messages, tts = TtsSettings(enabled = true)))
    private class Gateway(private val reply: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit = {
        emit(StreamEvent.Text("正文-${it.model.model}"))
    }) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow { requests += request; reply(request) }
    }
    private class Router(private val choose: suspend (TurnRoutingRequest) -> RoutingDecision) : TurnRouter {
        val requests = mutableListOf<TurnRoutingRequest>()
        override suspend fun decide(request: TurnRoutingRequest): RoutingDecision { requests += request; return choose(request) }
    }
    private class Speech : SpeechSink {
        val starts = mutableListOf<String>()
        val body = mutableMapOf<String, String>()
        val stops = mutableListOf<Set<String>>()
        override fun begin(messageId: String, character: Character) { starts += character.id }
        override fun append(messageId: String, text: String) { body[messageId] = body[messageId].orEmpty() + text }
        override fun finish(messageId: String) {}
        override fun stop(roomMessageIds: Set<String>) { stops += roomMessageIds }
    }

    @Test fun aHandsTheCompletedDualReplyToCEvenWhenBComesFirstAndCProbabilityIsZero() = runTest {
        val actors = listOf(a.copy(dualModel = true, cutMin = 2, cutMax = 2,
            secondaryOutputSource = ReplyOutputSource.REASONING), b, c.copy(probability = 0))
        val state = store(actors)
        val bFinish = CompletableDeferred<Unit>()
        val gateway = Gateway { request -> when (request.model.model) {
            "a" -> { emit(StreamEvent.Reasoning("A 的秘密思考")); emit(StreamEvent.Text("前缀被截掉")) }
            "a-B" -> { bFinish.await(); emit(StreamEvent.Text("不应成为正文")); emit(StreamEvent.Reasoning("，C，你怎么看？")) }
            else -> emit(StreamEvent.Text("由 C 回答"))
        } }
        val router = Router { request -> if (request.messages.last().senderId == "a")
            RoutingDecision(RoutingAction.ACTOR, "c", "A 明确向 C 发问", "actor-c-json")
            else RoutingDecision(RoutingAction.WAIT_USER, reason = "轮到真人") }
        val speech = Speech()
        val engine = ChatEngine(state, gateway, this, speech, router = router)
        engine.send("room", "请 A 先说", setOf("a"))
        runCurrent()
        assertEquals(listOf("a", "a-B"), gateway.requests.map { it.model.model })
        assertTrue(router.requests.isEmpty())
        bFinish.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "a-B", "c"), gateway.requests.map { it.model.model })
        assertEquals(listOf("a", "c"), speech.starts)
        val replies = state.snapshot().messages.filter { it.characterId != null }
        assertEquals("前缀，C，你怎么看？", router.requests.first().messages.last().text)
        assertFalse(router.requests.any { it.messages.any { message -> message.text.contains("秘密思考") || message.text.contains("不应成为正文") } })
        assertEquals(replies.first().id, replies.last().replyToMessageId)
        assertNull(replies.last().triggerMessageId)
        assertEquals("c", replies.first().routingDecision?.selectedCharacterId)
        assertEquals("C", replies.first().routingDecision?.selectedName)
        assertEquals("actor-c-json", replies.first().routingDecision?.rawOutput)
        assertEquals(RoutingAction.WAIT_USER, replies.last().routingDecision?.action)
        val cPrompt = gateway.requests.last().messages
        assertTrue(cPrompt.first().content.contains("AI 角色 ID=c，名字=C"))
        assertTrue(cPrompt.first().content.contains("ID=a，名字=A"))
        assertTrue(cPrompt.any { it.content.contains("【本次回应】\n【AI 角色 ID=a 名字=A】前缀") })
        assertFalse(cPrompt.any { it.content.contains("【本轮重新发送】") })
        replies.forEach { assertEquals(it.text, speech.body[it.id]) }
    }

    @Test fun mentioningANameDoesNotActAsAStructuredMentionAndWaitingForHumanDoesNotForceAReply() = runTest {
        val state = store()
        val gateway = Gateway()
        val router = Router { RoutingDecision(RoutingAction.WAIT_USER, reason = "这只是第三人提及，等待真人补充") }
        val engine = ChatEngine(state, gateway, this, router = router)
        engine.send("room", "昨天 B 说过，C 在忙。")
        advanceUntilIdle()
        assertEquals(1, router.requests.size)
        assertEquals(state.snapshot().messages.single().id, router.requests.single().triggerMessageId)
        assertTrue(gateway.requests.isEmpty())
        assertEquals(RoutingAction.WAIT_USER, state.snapshot().messages.single().routingDecision?.action)
        assertTrue(engine.busyRooms.value.isEmpty())
    }

    @Test fun humanMentionsRemainOrderedAndMandatoryButRespectTheTotalLimit() = runTest {
        val actors = listOf(a, b, c).map { it.copy(probability = 0, maxRepliesPerRound = 0, cooldownSec = 100) }
        val configured = room(actors).copy(routing = room().routing.copy(maxAutoReplies = 2))
        val state = store(actors, configured)
        val gateway = Gateway()
        val router = Router { error("真人 @ 队列未完成时不可调用路由") }
        val engine = ChatEngine(state, gateway, this, router = router)
        engine.send("room", "都来说一句", setOf("c", "b", "a"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), gateway.requests.map { it.model.model })
        assertTrue(router.requests.isEmpty())
        val messages = state.snapshot().messages
        assertTrue(messages.drop(1).all { it.replyToMessageId == messages.first().id })
        assertEquals(RoutingSource.MENTION, messages[0].routingDecision?.source)
        assertEquals(RoutingSource.MENTION, messages[1].routingDecision?.source)
        assertTrue(messages[1].routingDecision!!.reason.contains("真人本轮 @ 队列"))
        assertEquals(RoutingSource.RULE, messages.last().routingDecision?.source)
        assertEquals(RoutingAction.STOP, messages.last().routingDecision?.action)
    }

    @Test fun explicitHandoffsRespectCooldownAndPerActorLimitsWithoutSubstitutingB() = runTest {
        for (limit in listOf("cooldown", "count")) {
            val constrained = a.copy(cooldownSec = if (limit == "cooldown") 100 else 0,
                maxRepliesPerRound = if (limit == "count") 1 else 2)
            val state = store(listOf(constrained, b, c))
            val gateway = Gateway()
            val router = Router { request -> RoutingDecision(RoutingAction.ACTOR,
                if (request.messages.last().senderId == "a") "c" else "a") }
            val engine = ChatEngine(state, gateway, this, now = { 1_000 }, router = router)
            engine.send("room", "A 先说", setOf("a"))
            advanceUntilIdle()
            assertEquals(limit, listOf("a", "c"), gateway.requests.map { it.model.model })
            val last = state.snapshot().messages.last().routingDecision!!
            assertEquals(RoutingStatus.DECIDED, last.status)
            assertEquals(RoutingAction.STOP, last.action)
            assertNull(last.selectedCharacterId)
            assertTrue(last.reason.contains(if (limit == "cooldown") "冷却" else "次数上限"))
        }
    }

    @Test fun openFloorDoesNotOverrideProbabilityOrProactivityAndTotalBudgetStopsLoops() = runTest {
        val quietActors = listOf(a.copy(probability = 0), b.copy(proactive = false), c.copy(maxRepliesPerRound = 0))
        val quiet = store(quietActors)
        val quietGateway = Gateway()
        val quietEngine = ChatEngine(quiet, quietGateway, this, router = Router { RoutingDecision(RoutingAction.OPEN) })
        quietEngine.send("room", "大家随意")
        advanceUntilIdle()
        assertTrue(quietGateway.requests.isEmpty())
        assertEquals(RoutingAction.STOP, quiet.snapshot().messages.single().routingDecision?.action)

        val state = store(room = room().copy(routing = room().routing.copy(maxAutoReplies = 3)))
        val gateway = Gateway()
        val router = Router { request -> RoutingDecision(RoutingAction.ACTOR,
            if (request.messages.last().senderId == "a") "c" else "a") }
        val engine = ChatEngine(state, gateway, this, router = router)
        engine.send("room", "开始讨论")
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "a"), gateway.requests.map { it.model.model })
        assertEquals(3, router.requests.size)
        assertEquals(RoutingSource.RULE, state.snapshot().messages.last().routingDecision?.source)
    }

    @Test fun unknownTargetsSelfRepliesFailuresAndTimeoutsStopWithReadableRecords() = runTest {
        for (kind in listOf("unknown", "self", "error", "timeout")) {
            val state = store()
            val gateway = Gateway()
            val router = Router { when (kind) {
                "error" -> throw IOException("无法连接路由模型")
                "timeout" -> { delay(25_000); RoutingDecision(RoutingAction.ACTOR, "b") }
                "self" -> RoutingDecision(RoutingAction.ACTOR, "a")
                else -> RoutingDecision(RoutingAction.ACTOR, "removed")
            } }
            val engine = ChatEngine(state, gateway, this, router = router)
            engine.send("room", "A 先说", setOf("a"))
            advanceUntilIdle()
            assertEquals(kind, listOf("a"), gateway.requests.map { it.model.model })
            val record = state.snapshot().messages.last().routingDecision!!
            assertEquals(kind, RoutingStatus.ERROR, record.status)
            assertTrue(kind, record.error.isNotBlank())
            assertTrue(engine.busyRooms.value.isEmpty())
        }
    }

    @Test fun decisionsInFlightBecomeCancelledAndLateResultsCannotRestartDeletedStoppedOrEditedRooms() = runTest {
        for (operation in listOf("stop", "clear", "delete", "edit", "send")) {
            val state = store()
            val gate = CompletableDeferred<Unit>()
            var first = true
            val router = Router {
                if (first) { first = false; withContext(NonCancellable) { gate.await() }; RoutingDecision(RoutingAction.ACTOR, "a") }
                else RoutingDecision(RoutingAction.STOP)
            }
            val gateway = Gateway()
            val engine = ChatEngine(state, gateway, this, router = router)
            engine.send("room", "待判断")
            runCurrent()
            val trigger = state.snapshot().messages.single()
            assertEquals(RoutingStatus.DECIDING, trigger.routingDecision?.status)
            when (operation) {
                "stop" -> engine.stop("room")
                "clear" -> engine.clearHistory("room")
                "delete" -> engine.deleteRoom("room")
                "edit" -> engine.editMessage(trigger.id, "改为手写正文")
                else -> engine.send("room", "新问题")
            }
            runCurrent()
            val stable = state.snapshot()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(operation, stable, state.snapshot())
            assertTrue(operation, gateway.requests.isEmpty())
            if (operation == "stop" || operation == "send")
                assertEquals(RoutingStatus.CANCELLED, state.snapshot().messages.first().routingDecision?.status)
            if (operation == "edit") assertNull(state.snapshot().messages.single().routingDecision)
            assertTrue(engine.busyRooms.value.isEmpty())
        }
    }

    @Test fun oldResentTriggerIsIncludedWithoutOtherRoomsCotsOrPrivatePersonasAndCanBeAskedBackLater() = runTest {
        val source = ChatMessage(id = "source", roomId = "room", characterId = "a", senderName = "旧 A", text = "旧发言", createdAt = 1)
        val old = ChatMessage(id = "old", roomId = "room", text = "窗口外旧消息", createdAt = 2)
        val recent = ChatMessage(id = "recent", roomId = "room", characterId = "b", senderName = "B", text = "最近背景",
            segments = listOf(GenerationSegment(reasoning = "不应发送的 COT")), createdAt = 200)
        val foreign = ChatMessage(id = "foreign", roomId = "other", text = "另一群秘密", createdAt = 201)
        val configured = room().copy(routing = room().routing.copy(recentMessages = 2, maxAutoReplies = 2),
            members = listOf(a, b, c).map { Member(it.id, 100, false) })
        val state = store(room = configured, messages = listOf(source, old, recent, foreign))
        val gateway = Gateway()
        val router = Router { request -> RoutingDecision(RoutingAction.ACTOR,
            if (request.triggerMessageId == source.id) "c" else "a") }
        val engine = ChatEngine(state, gateway, this, router = router)
        assertTrue(engine.editAndSendMessage(source.id, "明确重发的 A 发言"))
        advanceUntilIdle()
        assertEquals(listOf("c", "a"), gateway.requests.map { it.model.model })
        assertEquals(listOf(source.id), router.requests.first().messages.map { it.id })
        assertEquals(source.id, router.requests.first().triggerMessageId)
        router.requests.forEach { request ->
            assertFalse(request.messages.any { it.text.contains("秘密") || it.text.contains("COT") || it.text.contains("private-") })
        }
        val first = state.snapshot().messages[2]
        assertEquals(source.id, first.replyToMessageId)
        assertEquals(source.id, first.triggerMessageId)
        assertFalse(gateway.requests.first().messages.any { it.content.contains(old.text) || it.content.contains(foreign.text) })
        assertTrue(gateway.requests.first().messages.any { it.content.contains("【本轮重新发送】\n【本次回应】") })
        assertTrue(gateway.requests.first().messages.first().content.contains("以它为主要接话依据"))
        val nextSystem = gateway.requests.last().messages.first().content
        assertTrue(nextSystem.contains("本轮讨论的起点背景"))
        assertTrue(nextSystem.contains("当前应优先回应前文明确指定的【本次回应】消息"))
        assertTrue(nextSystem.contains("ID=c，名字=C"))
        assertFalse(nextSystem.contains("以它为主要接话依据"))
        assertFalse(nextSystem.contains("以此自然继续说"))
        assertTrue(gateway.requests.last().messages.any {
            it.content == "【本次回应】\n【AI 角色 ID=c 名字=C】正文-c"
        })
        assertEquals(foreign, state.snapshot().messages[1])
    }

    @Test fun sequentialParallelAndDirectModesNeverInvokeTheRouter() = runTest {
        for (kind in listOf("sequential", "parallel", "direct")) {
            val configured = room().copy(mode = if (kind == "sequential") ReplyMode.SEQUENTIAL else
                if (kind == "parallel") ReplyMode.PARALLEL else ReplyMode.SMART, direct = kind == "direct")
            val state = store(room = configured)
            val gateway = Gateway()
            val router = Router { error("旧模式不可调用路由") }
            val engine = ChatEngine(state, gateway, this, router = router)
            engine.send("room", "普通问题")
            advanceUntilIdle()
            assertEquals(kind, if (kind == "direct") 1 else 3, gateway.requests.size)
            assertTrue(router.requests.isEmpty())
            assertTrue(state.snapshot().messages.all { it.routingDecision == null && it.replyToMessageId == null })
            assertFalse(gateway.requests.any { it.messages.first().content.contains("当前发言者只有你") })
        }
    }

    @Test fun retryAndRegenerateKeepTheReplyTargetAndIdentityButNeverRunAnotherRoutingRound() = runTest {
        val dual = a.copy(dualModel = true, cutMin = 2, cutMax = 2)
        val state = store(listOf(dual, b, c))
        var secondaryCalls = 0
        val gateway = Gateway { request ->
            if (request.model.model == "a") emit(StreamEvent.Text("开头丢弃"))
            else {
                secondaryCalls++
                if (secondaryCalls == 1) throw IOException("B 暂时失败")
                emit(StreamEvent.Text("正常续答"))
            }
        }
        val router = Router { RoutingDecision(RoutingAction.STOP) }
        val engine = ChatEngine(state, gateway, this, router = router)
        engine.send("room", "A，给我一个答案", setOf("a"))
        advanceUntilIdle()
        val user = state.snapshot().messages.first()
        val failed = state.snapshot().messages.last()
        val context = gateway.requests.first().messages
        assertEquals(MessageStatus.ERROR, failed.status)
        assertEquals(user.id, failed.replyToMessageId)
        assertTrue(router.requests.isEmpty())
        state.updateMessage(failed.id) { it.copy(routingDecision = RoutingRecord(status = RoutingStatus.DECIDED,
            action = RoutingAction.ACTOR, targetId = "b")) }
        engine.retry(failed.id)
        advanceUntilIdle()
        assertEquals(listOf("a", "a-B", "a-B"), gateway.requests.map { it.model.model })
        assertEquals("开头", gateway.requests.last().prefix)
        assertEquals(context, gateway.requests.last().messages)
        assertNull(state.snapshot().messages.last().routingDecision)
        assertEquals(user.id, state.snapshot().messages.last().replyToMessageId)
        assertTrue(router.requests.isEmpty())

        engine.regenerate(failed.id)
        advanceUntilIdle()
        assertEquals(listOf("a", "a-B", "a-B", "a", "a-B"), gateway.requests.map { it.model.model })
        assertEquals(context, gateway.requests.last().messages)
        assertTrue(router.requests.isEmpty())
        assertEquals(2, state.snapshot().messages.size)
        assertEquals(failed.id, state.snapshot().messages.last().id)
        assertTrue(engine.editMessage(failed.id, "保存手写答案"))
        assertNull(state.snapshot().messages.last().routingDecision)
        assertTrue(state.snapshot().messages.last().segments.isEmpty())
        assertEquals(user.id, state.snapshot().messages.last().replyToMessageId)
    }

    @Test fun aTargetThatLeftOrLostHistoryAccessDuringRoutingCannotBeReplacedByAnotherActor() = runTest {
        for (change in listOf("left", "joined-later")) {
            val state = store()
            val decided = CompletableDeferred<Unit>()
            val gateway = Gateway()
            val router = Router { decided.await(); RoutingDecision(RoutingAction.ACTOR, "c") }
            val engine = ChatEngine(state, gateway, this, now = { 100 }, router = router)
            engine.send("room", "C，你怎么看？")
            runCurrent()
            state.update { original -> original.copy(rooms = original.rooms.map { chat -> chat.copy(members =
                if (change == "left") chat.members.filterNot { it.characterId == "c" }
                else chat.members.map { if (it.characterId == "c") it.copy(joinedAt = 200, readHistory = false) else it }) }) }
            decided.complete(Unit)
            advanceUntilIdle()
            assertTrue(change, gateway.requests.isEmpty())
            assertEquals(change, RoutingStatus.ERROR, state.snapshot().messages.single().routingDecision?.status)
            assertNull(state.snapshot().messages.single().routingDecision?.selectedCharacterId)
        }
    }

    @Test fun stoppingOneRoomLeavesTheOtherRoomsPendingDecisionIntact() = runTest {
        val state = store()
        state.update { it.copy(rooms = it.rooms + room().copy(id = "other")) }
        val firstGate = CompletableDeferred<Unit>()
        val otherGate = CompletableDeferred<Unit>()
        val gateway = Gateway()
        val router = Router { request ->
            if (request.messages.last().text == "第一个群等待") firstGate.await() else otherGate.await()
            RoutingDecision(RoutingAction.STOP)
        }
        val engine = ChatEngine(state, gateway, this, router = router)
        engine.send("room", "第一个群等待")
        engine.send("other", "另一个群等待")
        runCurrent()
        assertEquals(setOf("room", "other"), engine.busyRooms.value)
        engine.stop("room")
        runCurrent()
        assertEquals(setOf("other"), engine.busyRooms.value)
        assertEquals(RoutingStatus.CANCELLED, state.snapshot().messages.first().routingDecision?.status)
        assertEquals(RoutingStatus.DECIDING, state.snapshot().messages.last().routingDecision?.status)
        otherGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(RoutingStatus.DECIDED, state.snapshot().messages.last().routingDecision?.status)
        assertEquals(RoutingStatus.CANCELLED, state.snapshot().messages.first().routingDecision?.status)
        assertTrue(gateway.requests.isEmpty())
    }
}
