package cn.rolechat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneProgressionTest {
    private val a = Character(id = "a", name = "A", primary = ModelConfig(model = "a"))
    private val b = Character(id = "b", name = "B", primary = ModelConfig(model = "b"))
    private val scene = Character(id = "scene", name = "场景", primary = ModelConfig(model = "scene"),
        messageStyle = MessageStyle.SCENE, probability = 0, proactive = false)
    private val room = ChatRoom(id = "room", mode = ReplyMode.SMART,
        members = listOf(a, b, scene).map { Member(it.id, 0, true) },
        routing = RoutingSettings(model = ModelConfig(model = "router"), recentMessages = 1, maxAutoReplies = 3))
    private fun store(room: ChatRoom = this.room, messages: List<ChatMessage> = emptyList()) =
        StateStore(AppState(characters = listOf(a, b, scene), rooms = listOf(room), messages = messages))
    private class Gateway : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow {
            requests += request
            emit(StreamEvent.Text(if (request.model.model == "scene") "次日清晨，窗外已经亮了。" else "${request.model.model} 的发言"))
        }
    }
    private class Router(private val choose: (TurnRoutingRequest) -> RoutingDecision) : TurnRouter {
        val requests = mutableListOf<TurnRoutingRequest>()
        override suspend fun decide(request: TurnRoutingRequest): RoutingDecision { requests += request; return choose(request) }
    }

    @Test fun anExplicitSceneTargetBypassesPassiveSettingsAndReceivesBudgetAnchorAndCompletedSceneProgress() = runTest {
        val state = store()
        val gateway = Gateway()
        val router = Router { request -> RoutingDecision(RoutingAction.ACTOR,
            if (request.messages.last().senderId == "a") "scene" else "b") }
        val engine = ChatEngine(state, gateway, this, router = router)
        engine.send("room", "就这样吧，你们两个先玩着，第二天喊我", setOf("a"))
        advanceUntilIdle()
        val original = state.snapshot().messages.first()
        val completedScene = state.snapshot().messages.single { it.characterId == "scene" }
        assertEquals(listOf("a", "scene", "b"), gateway.requests.map { it.model.model })
        assertEquals(listOf(2, 1), router.requests.map { it.remainingReplies })
        assertTrue(router.requests.first().participants.single { it.id == "scene" }.canReply)
        assertEquals("", router.requests.first().participants.single { it.id == "scene" }.blockedReason)
        assertFalse(router.requests.last().participants.single { it.id == "scene" }.canReply)
        assertTrue(router.requests.first().sceneMessages.isEmpty())
        assertEquals(listOf(RoutingMessage(completedScene.id, "scene", scene.name, completedScene.text, MessageStyle.SCENE)),
            router.requests.last().sceneMessages)
        router.requests.forEach {
            assertEquals(original.id, it.originalRequest?.id)
            assertEquals(original.text, it.originalRequest?.text)
            assertFalse(it.messages.any { message -> message.id == original.id })
        }
        val scenePrompt = gateway.requests[1].messages.first().content
        assertTrue(scenePrompt.contains("原始真人要求明确授权时间或地点推进时"))
        assertTrue(scenePrompt.contains("直接落实下一项尚未完成的阶段"))
        assertTrue(scenePrompt.contains("没有时间或地点推进授权时，只描述当前可确认的场景"))
        assertTrue(scenePrompt.contains("不替真人醒来、作决定或说台词"))
        assertTrue(scenePrompt.contains("真人已确定的角色扮演与场景设定仍按其要求自然推进"))
        assertFalse(scenePrompt.contains(original.text))
        assertTrue(gateway.requests.last().messages.first().content.contains("按已经到达的阶段叫他"))
        assertEquals(RoutingSource.RULE, state.snapshot().messages.last().routingDecision?.source)
    }

    @Test fun aPureContinuationReusesOnlyCompletedScenesFromItsOwnVerifiedRequest() = runTest {
        val original = ChatMessage(id = "original", roomId = "room", text = "第二天喊我", createdAt = 1)
        val wrongRequest = ChatMessage(id = "wrong", roomId = "room", characterId = "scene", text = "别的任务场景",
            messageStyle = MessageStyle.SCENE, requestMessageId = "different", createdAt = 2)
        val unfinished = wrongRequest.copy(id = "unfinished", text = "失败场景", requestMessageId = original.id, status = MessageStatus.ERROR)
        val completed = wrongRequest.copy(id = "completed", text = "已到次日", requestMessageId = original.id)
        val previous = ChatMessage(id = "previous", roomId = "room", characterId = "a", text = "已经早上了",
            requestMessageId = original.id, contextMessageIds = listOf(original.id, completed.id), createdAt = 3)
        val foreign = completed.copy(id = "foreign", roomId = "other", text = "其他群场景")
        val state = store(messages = listOf(original, wrongRequest, unfinished, completed, previous, foreign))
        val gateway = Gateway()
        val router = Router { RoutingDecision(RoutingAction.STOP) }
        val engine = ChatEngine(state, gateway, this, router = router)
        engine.send("room", "你们继续！")
        advanceUntilIdle()
        val request = router.requests.single()
        assertEquals(original.id, request.originalRequest?.id)
        assertEquals(listOf(completed.id), request.sceneMessages.map { it.id })
        assertEquals(listOf("你们继续！"), request.messages.map { it.text })
        assertFalse(request.sceneMessages.any { it.text.contains("别的") || it.text.contains("失败") || it.text.contains("其他群") })
        assertTrue(gateway.requests.isEmpty())
    }

    @Test fun resendingAnOldAiDeletesOldFutureScenesFromRoutingAndStoredHistory() = runTest {
        val original = ChatMessage(id = "original", roomId = "room", text = "第二天喊我", createdAt = 1)
        val source = ChatMessage(id = "source", roomId = "room", characterId = "a", senderName = "A", text = "那我们先玩",
            requestMessageId = original.id, contextMessageIds = listOf(original.id), createdAt = 2)
        val future = ChatMessage(id = "future", roomId = "room", characterId = "scene", text = "旧分支已经过了三天",
            messageStyle = MessageStyle.SCENE, requestMessageId = original.id, roundId = "old", createdAt = 3)
        val laterHuman = ChatMessage(id = "later", roomId = "room", text = "旧分支的后来要求", createdAt = 4)
        val state = store(messages = listOf(original, source, future, laterHuman))
        val gateway = Gateway()
        val router = Router { request -> if (request.triggerMessageId == source.id)
            RoutingDecision(RoutingAction.ACTOR, "scene") else RoutingDecision(RoutingAction.STOP) }
        val engine = ChatEngine(state, gateway, this, router = router)
        assertTrue(engine.editAndSendMessage(source.id, "我们先玩到明天"))
        advanceUntilIdle()
        assertEquals(listOf("scene"), gateway.requests.map { it.model.model })
        router.requests.forEach { request ->
            assertFalse(request.messages.any { it.id == future.id || it.id == laterHuman.id })
            assertFalse(request.sceneMessages.any { it.id == future.id })
            assertEquals(original.id, request.originalRequest?.id)
        }
        assertTrue(router.requests.first().sceneMessages.isEmpty())
        assertEquals(listOf(state.snapshot().messages.last().id), router.requests.last().sceneMessages.map { it.id })
        val context = gateway.requests.single().messages
        assertFalse(context.any { it.content.contains(future.text) })
        assertTrue(context.first().content.contains("不能作为本轮阶段已经完成的依据"))
        assertTrue(context.first().content.contains("本轮尚无新完成的场景"))
        assertFalse(state.snapshot().messages.any { it.id == future.id || it.id == laterHuman.id })
    }

    @Test fun scenesCannotUseUnreadableOriginalRequestsAndZeroBudgetDoesNotCallAnyModel() = runTest {
        val original = ChatMessage(id = "private-request", roomId = "room", text = "入群前的秘密推进要求", createdAt = 1)
        val previous = ChatMessage(id = "previous", roomId = "room", characterId = "a", text = "收到", createdAt = 200,
            requestMessageId = original.id, contextMessageIds = listOf(original.id))
        val limitedRoom = room.copy(members = room.members.map { if (it.characterId == "scene") it.copy(joinedAt = 100, readHistory = false) else it })
        val state = store(limitedRoom, listOf(original, previous))
        val gateway = Gateway()
        val router = Router { RoutingDecision(RoutingAction.ACTOR, "scene") }
        val engine = ChatEngine(state, gateway, this, now = { 300 }, router = router)
        engine.send("room", "继续")
        advanceUntilIdle()
        val participant = router.requests.single().participants.single { it.id == "scene" }
        assertFalse(participant.canReply)
        assertEquals("无法读取本轮原始真人要求", participant.blockedReason)
        assertTrue(gateway.requests.isEmpty())
        assertEquals(RoutingStatus.ERROR, state.snapshot().messages.last().routingDecision?.status)
        assertFalse(state.snapshot().messages.last().routingDecision!!.error.contains(original.text))

        val zero = store(room.copy(routing = room.routing.copy(maxAutoReplies = 0)))
        val zeroGateway = Gateway()
        val zeroRouter = Router { error("额度为0不应判断") }
        ChatEngine(zero, zeroGateway, this, router = zeroRouter).send("room", "第二天喊我", setOf("scene"))
        advanceUntilIdle()
        assertTrue(zeroGateway.requests.isEmpty())
        assertTrue(zeroRouter.requests.isEmpty())
        assertEquals(RoutingAction.STOP, zero.snapshot().messages.single().routingDecision?.action)
    }

    @Test fun sceneResponsibilitiesRemainConditionalAndAreAbsentWithoutAReadableHumanAnchor() = runTest {
        val ordinary = store(room.copy(routing = room.routing.copy(maxAutoReplies = 1)))
        val ordinaryGateway = Gateway()
        ChatEngine(ordinary, ordinaryGateway, this).send("room", "比较信任与契约的作用", setOf("scene"))
        advanceUntilIdle()
        val ordinarySystem = ordinaryGateway.requests.single().messages.first().content
        assertTrue(ordinarySystem.contains("原始真人要求明确授权时间或地点推进时"))
        assertTrue(ordinarySystem.contains("没有时间或地点推进授权时，只描述当前可确认的场景"))
        assertTrue(ordinarySystem.contains("不要越过授权范围擅自推进"))
        assertTrue(ordinaryGateway.requests.single().messages.any { it.content.contains("比较信任与契约的作用") })

        val old = ChatMessage(id = "old-ai", roomId = "room", characterId = "a", text = "没有真人上下文的旧消息")
        val noAnchor = store(room.copy(routing = room.routing.copy(maxAutoReplies = 1)), listOf(old))
        val noAnchorGateway = Gateway()
        val noAnchorRouter = Router { RoutingDecision(RoutingAction.ACTOR, "scene") }
        ChatEngine(noAnchor, noAnchorGateway, this, router = noAnchorRouter).editAndSendMessage(old.id, "重发这句话")
        advanceUntilIdle()
        assertNull(noAnchorRouter.requests.single().originalRequest)
        assertFalse(noAnchorGateway.requests.single().messages.first().content.contains("你的场景职责"))
        assertNull(noAnchor.snapshot().messages.last().requestMessageId)
    }
}
