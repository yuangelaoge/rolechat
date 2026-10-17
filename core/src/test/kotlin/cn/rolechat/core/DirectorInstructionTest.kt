package cn.rolechat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectorInstructionTest {
    private val actor = Character(id = "a", primary = ModelConfig(model = "A"), secondary = ModelConfig(model = "B"),
        dualModel = true, cutMin = 2, cutMax = 2)
    private val room = ChatRoom(id = "r", members = listOf(Member("a", 0, true)))
    private class Gateway : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest) = flow {
            requests += request
            emit(StreamEvent.Text(if (request.model.model == "router")
                """{"action":"actor","target_id":"a","reason":"按剧情要求接话","topic_status":"on_topic"}""" else "新的场景已经开始"))
            emit(StreamEvent.Finished())
        }
    }

    @Test fun directiveReachesBothModelsAsSystemAndNeverBecomesSpokenDialogue() = runTest {
        for (mode in listOf(ReplyMode.SEQUENTIAL, ReplyMode.PARALLEL)) {
            val store = StateStore(AppState(characters = listOf(actor), rooms = listOf(room.copy(mode = mode))))
            val gateway = Gateway()
            val engine = ChatEngine(store, gateway, this)
            assertTrue(engine.setDirectorInstruction("r", "争吵从未发生，现在转到清晨", true))
            advanceUntilIdle()
            assertEquals(listOf("A", "B"), gateway.requests.map { it.model.model })
            gateway.requests.forEach { request ->
                assertTrue(request.messages.first().content.contains("争吵从未发生，现在转到清晨"))
                assertEquals("system", request.messages.first().role)
                assertFalse(request.messages.drop(1).any { it.content.contains("争吵从未发生") })
            }
            assertTrue(store.snapshot().messages.first().isDirector)
            assertEquals("System · 剧情指令", store.snapshot().messages.first().senderName)
            assertFalse(engine.editMessage(store.snapshot().messages.first().id, "不能从旧记录复活指令"))
        }
    }

    @Test fun smartRouterGetsDirectiveAndWorksEvenForAnEmptyRoomHistory() = runTest {
        val smart = room.copy(mode = ReplyMode.SMART, routing = RoutingSettings(model = ModelConfig(model = "router"), maxAutoReplies = 1))
        val store = StateStore(AppState(characters = listOf(actor), rooms = listOf(smart)))
        val gateway = Gateway()
        val engine = ChatEngine(store, gateway, this)
        engine.setDirectorInstruction("r", "下一步由 a 接话", true)
        advanceUntilIdle()
        assertEquals(listOf("router", "A", "B"), gateway.requests.map { it.model.model })
        assertTrue(gateway.requests.all { it.messages.first().content.contains("下一步由 a 接话") })
        assertTrue(gateway.requests.first().messages[1].content.contains("\"is_director\":true"))
        assertFalse(gateway.requests.first().messages[1].content.contains("下一步由 a 接话"))
    }

    @Test fun replacementClearAndOtherRoomsDoNotReviveArchivedDirectives() = runTest {
        val store = StateStore(AppState(characters = listOf(actor.copy(dualModel = false)), rooms = listOf(room, room.copy(id = "other"))))
        val gateway = Gateway()
        val engine = ChatEngine(store, gateway, this)
        engine.setDirectorInstruction("r", "旧安排甲", false)
        engine.setDirectorInstruction("r", "新安排乙", true)
        advanceUntilIdle()
        assertFalse(gateway.requests.last().messages.any { "旧安排甲" in it.content })
        assertTrue(gateway.requests.last().messages.first().content.contains("新安排乙"))
        engine.send("other", "你好")
        advanceUntilIdle()
        assertFalse(gateway.requests.last().messages.any { "新安排乙" in it.content })
        engine.setDirectorInstruction("r", "", false)
        engine.send("r", "继续")
        advanceUntilIdle()
        assertFalse(gateway.requests.last().messages.any { "新安排乙" in it.content || "旧安排甲" in it.content })
        assertTrue(store.snapshot().messages.any { it.isDirector && it.text == "旧安排甲" })
    }

    @Test fun saveStopsAnOldGenerationWithoutLaunchingAnotherAndClearHistoryRemovesInstruction() = runTest {
        val requests = mutableListOf<GenerationRequest>()
        val stalled = object : ChatGateway {
            override fun stream(request: GenerationRequest) = flow<StreamEvent> {
                requests += request
                emit(StreamEvent.Text("旧回复"))
                awaitCancellation()
            }
        }
        val store = StateStore(AppState(characters = listOf(actor.copy(dualModel = false)), rooms = listOf(room)))
        val engine = ChatEngine(store, stalled, this)
        engine.send("r", "原话")
        runCurrent()
        assertTrue(engine.busyRooms.value.isNotEmpty())
        assertTrue(engine.setDirectorInstruction("r", "暂停争吵", false))
        runCurrent()
        assertTrue(engine.busyRooms.value.isEmpty())
        assertEquals(1, requests.size)
        assertEquals(MessageStatus.INTERRUPTED, store.snapshot().messages.first { it.characterId != null }.status)
        engine.clearHistory("r")
        assertEquals("", store.snapshot().rooms.single().directorInstruction)
        assertTrue(store.snapshot().messages.isEmpty())
    }

    @Test fun persistenceGroupTransferLegacyDefaultsAndLengthLimit() = runTest {
        val state = AppState(characters = listOf(actor), rooms = listOf(room.copy(directorInstruction = "保持日常")))
        val json = Json { encodeDefaults = true }
        assertEquals(state, json.decodeFromString<AppState>(json.encodeToString(state)))
        val imported = importGroupPackage(AppState(), exportGroupPackage(state, "r"))
        assertEquals("保持日常", imported.state.rooms.last().directorInstruction)
        assertEquals("", json.decodeFromString<ChatRoom>("""{"id":"legacy"}""").directorInstruction)
        val store = StateStore(state)
        val engine = ChatEngine(store, Gateway(), this)
        assertFalse(engine.setDirectorInstruction("r", "x".repeat(MAX_DIRECTOR_INSTRUCTION + 1), false))
        assertFalse(engine.setDirectorInstruction("r", "", true))
        assertEquals(state, store.snapshot())
    }

    @Test fun compressionDoesNotArchiveOldCommandBodiesAndKeepsTheCurrentInstructionOutsideMemory() = runTest {
        val current = room.copy(directorInstruction = "当前指令保持轻松")
        val state = AppState(characters = listOf(actor.copy(dualModel = false)), rooms = listOf(current), messages = listOf(
            ChatMessage(id = "old-director", roomId = "r", text = "旧命令让所有人争吵", isDirector = true, createdAt = 1),
            ChatMessage(id = "human", roomId = "r", text = "你好", createdAt = 2),
            ChatMessage(id = "reply", roomId = "r", characterId = "a", text = "早上好", createdAt = 3)))
        val settings = CompressionSettings(keepRecentMessages = 1)
        val plans = buildCompressionPlans(state, "r", settings)
        val gateway = Gateway()
        val summary = ContextSummaryGenerator(gateway).generate(plans.single(), settings, state.providers.single())
        assertFalse(gateway.requests.single().messages.any { "旧命令让所有人争吵" in it.content })
        assertFalse(gateway.requests.single().messages.any { "当前指令保持轻松" in it.content })
        val store = StateStore(applyCompressionDrafts(state, settings, plans, mapOf("a" to summary)))
        ChatEngine(store, gateway, this).send("r", "接着聊")
        advanceUntilIdle()
        assertTrue(gateway.requests.last().messages.first().content.contains("当前指令保持轻松"))
        assertFalse(gateway.requests.last().messages.any { "旧命令让所有人争吵" in it.content })
    }
}
