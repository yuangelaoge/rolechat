package cn.rolechat.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SmartRoutingConfigurationTest {
    @Test fun routingProviderAndSettingsSurviveGroupTransferWithoutHistoryOrDecisionRecords() {
        for (mode in listOf(ReplyMode.SMART, ReplyMode.SEQUENTIAL)) {
            val source = AppState().let { state -> state.copy(
                providers = state.providers + Provider(id = "router-only", name = "判断服务", baseUrl = "https://routing.example/v1"),
                rooms = state.rooms.map { it.copy(mode = mode, routing = RoutingSettings(
                    model = ModelConfig(providerId = "router-only", model = "test/routing"),
                    systemPrompt = "用户自定义接话规则", recentMessages = 5, maxAutoReplies = 4)) },
                messages = listOf(ChatMessage(roomId = "lobby", text = "不导出的聊天", routingDecision = RoutingRecord(
                    status = RoutingStatus.DECIDED, action = RoutingAction.WAIT_USER, reason = "不导出的判断记录")))) }
            val packaged = exportGroupPackage(source, "lobby")
            assertEquals(setOf("siliconflow", "router-only"), packaged.providers.map { it.id }.toSet())
            val encoded = Json.encodeToString(packaged)
            assertFalse(encoded.contains("不导出的聊天"))
            assertFalse(encoded.contains("不导出的判断记录"))
            val result = importGroupPackage(AppState(), Json.decodeFromString<GroupPackage>(encoded))
            val imported = result.state.rooms.last()
            assertEquals(mode, imported.mode)
            val providerId = imported.routing.model.providerId
            assertNotEquals("router-only", providerId)
            assertEquals("https://routing.example/v1", result.state.providers.single { it.id == providerId }.baseUrl)
            assertEquals(source.rooms.single().routing, imported.routing.copy(model = imported.routing.model.copy(providerId = "router-only")))
            assertTrue(result.state.messages.isEmpty())
        }
    }

    @Test fun legacyGroupUsingOnlyCustomProviderDoesNotAcquireUnusedRoutingDependencies() {
        val provider = Provider(id = "custom", baseUrl = "https://custom.example/v1")
        val character = Character(id = "role", worldId = null,
            primary = ModelConfig(providerId = provider.id), secondary = ModelConfig(providerId = provider.id))
        val room = ChatRoom(id = "legacy", worldId = null, members = listOf(Member(character.id)))
        val source = AppState(providers = listOf(provider), characters = listOf(character), worlds = emptyList(), rooms = listOf(room))
        val packaged = exportGroupPackage(source, room.id)
        assertEquals(listOf(provider), packaged.providers)
        val imported = importGroupPackage(AppState(), packaged).state.rooms.last()
        assertEquals(ReplyMode.SEQUENTIAL, imported.mode)
        assertEquals(RoutingSettings(), imported.routing)
    }

    @Test fun oldJsonDefaultsToLegacySchedulingAndNewDecisionSnapshotsRoundTrip() {
        val old = Json.decodeFromString<ChatRoom>("""{"id":"old","name":"旧群"}""")
        assertEquals(ReplyMode.SEQUENTIAL, old.mode)
        assertEquals(RoutingSettings(), old.routing)
        val message = ChatMessage(roomId = "old", replyToMessageId = "question", routingDecision = RoutingRecord(
            status = RoutingStatus.DECIDED, action = RoutingAction.ACTOR, targetId = "c", selectedCharacterId = "c",
            selectedName = "小萱", reason = "发言者请小萱回答", source = RoutingSource.MODEL, model = "router"))
        val backupJson = Json { encodeDefaults = true }
        assertEquals(message, backupJson.decodeFromString<ChatMessage>(backupJson.encodeToString(message)))
        val legacy = Json.decodeFromString<ChatMessage>("""{"roomId":"old"}""")
        assertNull(legacy.replyToMessageId)
        assertNull(legacy.routingDecision)
    }

    @Test fun invalidSmartConfigurationCannotBeExportedAsAUsableGroup() {
        val base = AppState()
        for (settings in listOf(RoutingSettings(recentMessages = 0), RoutingSettings(maxAutoReplies = 21),
            RoutingSettings(systemPrompt = " "), RoutingSettings(model = ModelConfig(providerId = "missing")))) {
            val invalid = base.copy(rooms = base.rooms.map { it.copy(mode = ReplyMode.SMART, routing = settings) })
            assertTrue(runCatching { exportGroupPackage(invalid, "lobby") }.isFailure)
        }
    }

    @Test fun inactiveIncompleteRoutingDraftCanStillBeExportedAndImported() {
        val settings = RoutingSettings(model = ModelConfig(model = ""), systemPrompt = "")
        val source = AppState().let { it.copy(rooms = it.rooms.map { room -> room.copy(routing = settings) }) }
        val imported = importGroupPackage(AppState(), exportGroupPackage(source, "lobby")).state.rooms.last()
        assertEquals(ReplyMode.SEQUENTIAL, imported.mode)
        assertEquals(settings, imported.routing)
        assertTrue(runCatching { validateRoutingSettings(imported.routing) }.isFailure)
    }
}
