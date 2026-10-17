package cn.rolechat.app.network

import cn.rolechat.core.*
import cn.rolechat.core.Character
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Real HTTP/SSE adapter and routing parser; responses are local fixtures, never paid calls. */
class SmartRoutingIntegrationTest {
    private fun sse(text: String): MockResponse {
        val events = text.chunked(7).joinToString("") { part ->
            "data: " + buildJsonObject {
                putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content", part) } } }
            } + "\n\n"
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(events +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", 1)
    }

    @Test fun twoRolesKeepOriginalRequestOutsideRecentWindowAndReceiveExampleThenOffTopicGuidance() = runBlocking {
        MockWebServer().use { server ->
            val task = "请讨论首映片单，比较两个适合邻里的方向，不讨论酒会布置。"
            server.enqueue(sse("举个反例，有人会只顾酒会而忽略影片。许川，你怎么看？"))
            server.enqueue(sse("""{"action":"actor","target_id":"b","reason":"这是说明首映取舍的反例，请许川回扣原问题","topic_status":"example"}"""))
            // Deliberately simulate a model ignoring the first reminder; a later reply must correct course.
            server.enqueue(sse("酒会应该摆三张桌子，门口放花。沈禾，你觉得呢？"))
            server.enqueue(sse("""{"action":"actor","target_id":"a","reason":"讨论已转到用户排除的酒会布置，应回到首映片单","topic_status":"off_topic"}"""))
            server.enqueue(sse("先回到片单吧。两个方向可以是社区生活和轻松喜剧，我们比较观众接受度。"))
            server.start()
            val provider = Provider(id = "test", baseUrl = server.url("/v1").toString())
            val actors = listOf(Character(id = "a", name = "沈禾"), Character(id = "b", name = "许川")).map {
                it.copy(primary = ModelConfig(providerId = "test", model = "chat-${it.id}"), maxRepliesPerRound = 3)
            }
            val room = ChatRoom(id = "focus", members = actors.map { Member(it.id, 0, true) }, mode = ReplyMode.SMART,
                routing = RoutingSettings(model = ModelConfig(providerId = "test", model = "route-small"),
                    recentMessages = 1, maxAutoReplies = 3))
            val store = StateStore(AppState(providers = listOf(provider), characters = actors, rooms = listOf(room)))
            val engine = ChatEngine(store, SiliconFlowGateway(keyFor = { "local-fixture" }), this)
            engine.send(room.id, task, setOf("a"))
            withTimeout(10_000) { engine.busyRooms.first { it.isEmpty() } }
            assertEquals(5, server.requestCount)
            val bodies = (1..5).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            assertEquals(listOf("chat-a", "route-small", "chat-b", "route-small", "chat-a"),
                bodies.map { it["model"]!!.jsonPrimitive.content })
            val messages = store.snapshot().messages.filter { it.roomId == room.id }
            val request = messages.first()
            assertEquals(listOf(null, "a", "b", "a"), messages.map { it.characterId })
            assertTrue(messages.drop(1).all { it.requestMessageId == request.id })
            assertEquals(TopicStatus.EXAMPLE, messages[1].routingDecision!!.topicStatus)
            assertEquals(TopicStatus.EXAMPLE, messages[2].replyTopicStatus)
            assertEquals(TopicStatus.OFF_TOPIC, messages[2].routingDecision!!.topicStatus)
            assertEquals(TopicStatus.OFF_TOPIC, messages[3].replyTopicStatus)
            for (body in listOf(bodies[1], bodies[3])) {
                val envelope = body["messages"]!!.jsonArray
                val payload = Json.parseToJsonElement(envelope[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                val anchor = payload["original_request"]!!.jsonObject
                assertEquals(request.id, anchor["id"]!!.jsonPrimitive.content)
                assertEquals(task, anchor["text"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, anchor["sender_id"])
                val recent = payload["messages"]!!.jsonArray
                assertEquals(1, recent.size)
                assertNotEquals(request.id, recent.single().jsonObject["id"]!!.jsonPrimitive.content)
            }
            for (index in listOf(0, 2, 4)) {
                val history = bodies[index]["messages"]!!.jsonArray.map { it.jsonObject }
                assertFalse(history.first()["content"]!!.jsonPrimitive.content.contains(task))
                assertTrue(history.any { it["role"]!!.jsonPrimitive.content == "user" &&
                    it["content"]!!.jsonPrimitive.content.contains(task) })
            }
            assertTrue(bodies[2]["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("例子"))
            assertTrue(bodies[4]["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("原始要求"))
            assertEquals("酒会应该摆三张桌子，门口放花。沈禾，你觉得呢？", messages[2].text)
        }
    }

    @Test fun authorizedNextMorningUsesSceneEvenWithoutProactiveRepliesWithinFourMessageBudget() = runBlocking {
        MockWebServer().use { server ->
            val task = "就这样吧，你们两个先玩着，第二天喊我"
            fun route(id: String, reason: String) = sse(buildJsonObject {
                put("action", "actor"); put("target_id", id); put("reason", reason); put("topic_status", "on_topic")
            }.toString())
            server.enqueue(route("a", "先简短回应，再为次日转场和叫人留出名额"))
            server.enqueue(sse("好，你先休息。我们把这局下完。"))
            server.enqueue(route("b", "让另一位简短接话，仍预留两条完成后续阶段"))
            server.enqueue(sse("行，我收拾棋盘，明早记得叫他。"))
            server.enqueue(route("scene", "两人已经回应，真人授权的次日转场尚未完成"))
            server.enqueue(sse("次日清晨。窗帘缝里透进晨光，走廊传来轻轻的脚步声。"))
            server.enqueue(route("a", "场景已经进入次日，由在场人物叫人，不再重复跳时"))
            server.enqueue(sse("早上好，已经第二天啦。醒了吗？"))
            server.start()
            val provider = Provider(id = "test", baseUrl = server.url("/v1").toString())
            val actors = listOf(Character(id = "a", name = "沈禾"), Character(id = "b", name = "许川"),
                Character(id = "scene", name = "场景提示", messageStyle = MessageStyle.SCENE,
                    probability = 0, proactive = false, maxRepliesPerRound = 1)).map {
                it.copy(primary = ModelConfig(providerId = "test", model = "chat-${it.id}"))
            }
            val room = ChatRoom(id = "story", members = actors.map { Member(it.id, 0, true) }, mode = ReplyMode.SMART,
                routing = RoutingSettings(model = ModelConfig(providerId = "test", model = "route-small"),
                    recentMessages = 1, maxAutoReplies = 4))
            val store = StateStore(AppState(providers = listOf(provider), characters = actors, rooms = listOf(room)))
            val engine = ChatEngine(store, SiliconFlowGateway(keyFor = { "local-fixture" }), this)
            engine.send(room.id, task)
            withTimeout(10_000) { engine.busyRooms.first { it.isEmpty() } }
            assertEquals(8, server.requestCount)
            val bodies = (1..8).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            assertEquals(listOf("route-small", "chat-a", "route-small", "chat-b", "route-small", "chat-scene", "route-small", "chat-a"),
                bodies.map { it["model"]!!.jsonPrimitive.content })
            val messages = store.snapshot().messages
            assertEquals(listOf(null, "a", "b", "scene", "a"), messages.map { it.characterId })
            assertEquals(MessageStyle.SCENE, messages[3].messageStyle)
            assertTrue(messages.drop(1).all { it.requestMessageId == messages.first().id })
            assertTrue(messages.last().routingDecision!!.reason.contains("上限"))
            for ((turn, index) in listOf(0, 2, 4, 6).withIndex()) {
                val payload = Json.parseToJsonElement(bodies[index]["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                assertEquals(4 - turn, payload["remaining_replies"]!!.jsonPrimitive.int)
                assertEquals(task, payload["original_request"]!!.jsonObject["text"]!!.jsonPrimitive.content)
                val scene = payload["participants"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == "scene" }
                assertEquals("scene", scene["message_style"]!!.jsonPrimitive.content)
                assertEquals(turn < 3, scene["can_reply"]!!.jsonPrimitive.boolean)
                val completed = payload["scene_messages"]!!.jsonArray
                assertEquals(if (turn == 3) 1 else 0, completed.size)
                if (completed.isNotEmpty()) assertEquals(messages[3].text, completed.single().jsonObject["text"]!!.jsonPrimitive.content)
            }
            val sceneHistory = bodies[5]["messages"]!!.jsonArray.map { it.jsonObject }
            assertTrue(sceneHistory.any { it["role"]!!.jsonPrimitive.content == "user" && it["content"]!!.jsonPrimitive.content.contains(task) })
            assertFalse(sceneHistory.first()["content"]!!.jsonPrimitive.content.contains(task))
            assertTrue(sceneHistory.first()["content"]!!.jsonPrimitive.content.contains("场景"))
            assertTrue(engine.busyRooms.value.isEmpty())
        }
    }

    @Test fun completeChatToRouterToAddressedRoleUsesSeparateRequestsAndThenWaitsForHuman() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse("小萱，你怎么看？"))
            server.enqueue(sse("""{"action":"actor","target_id":"c","reason":"林间正在请小萱回答"}"""))
            server.enqueue(sse("我觉得可以。顾言，你想先去哪？"))
            server.enqueue(sse("""{"action":"wait_user","target_id":"","reason":"小萱的问题指向真人顾言"}"""))
            server.start()
            val provider = Provider(id = "test", baseUrl = server.url("/v1").toString(), prefixMode = PrefixMode.ASSISTANT)
            fun role(id: String, name: String) = Character(id = id, name = name,
                prompt = "PRIVATE_ROLE_$id", primary = ModelConfig(providerId = provider.id, model = "chat-$id"))
            val actors = listOf(role("a", "林间"), role("b", "夏至"), role("c", "小萱"))
            val room = ChatRoom(id = "group", name = "此群", members = actors.map { Member(it.id, 0, true) },
                mode = ReplyMode.SMART, userName = "顾言", userBackground = "PRIVATE_HUMAN_BACKGROUND",
                routing = RoutingSettings(model = ModelConfig(providerId = provider.id, model = "route-small")))
            val foreign = ChatMessage(id = "foreign", roomId = "other", text = "FOREIGN_ROOM_SECRET")
            val store = StateStore(AppState(providers = listOf(provider), characters = actors, rooms = listOf(room),
                messages = listOf(foreign), worlds = listOf(World(id = "everyday", prompt = "PRIVATE_WORLD"))))
            val engine = ChatEngine(store, SiliconFlowGateway(keyFor = { "local-fixture" }), this)
            engine.send(room.id, "我们聊聊周末吧。", setOf("a"))
            withTimeout(10_000) { engine.busyRooms.first { it.isEmpty() } }
            assertEquals(4, server.requestCount)
            val bodies = (1..4).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            assertEquals(listOf("chat-a", "route-small", "chat-c", "route-small"), bodies.map { it["model"]!!.jsonPrimitive.content })
            for (body in listOf(bodies[1], bodies[3])) {
                assertFalse(body["enable_thinking"]!!.jsonPrimitive.boolean)
                assertNull(body["prefix"])
                assertEquals(listOf("system", "user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
                val payload = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
                for (hidden in listOf("PRIVATE_ROLE_", "PRIVATE_HUMAN_BACKGROUND", "PRIVATE_WORLD", "FOREIGN_ROOM_SECRET")) {
                    assertFalse(payload.contains(hidden))
                }
                assertTrue(payload.contains("小萱"))
            }
            val messages = store.snapshot().messages.filter { it.roomId == room.id }
            assertEquals(listOf(null, "a", "c"), messages.map { it.characterId })
            assertEquals("c", messages[1].routingDecision!!.selectedCharacterId)
            assertEquals(RoutingAction.WAIT_USER, messages.last().routingDecision!!.action)
            assertEquals(RoutingStatus.DECIDED, messages.last().routingDecision!!.status)
            assertEquals(messages[1].id, messages.last().replyToMessageId)
            assertEquals(foreign, store.snapshot().messages.first())
            assertTrue(engine.busyRooms.value.isEmpty())
        }
    }
}
