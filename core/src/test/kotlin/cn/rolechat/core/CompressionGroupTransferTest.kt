package cn.rolechat.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CompressionGroupTransferTest {
    private val json = Json { encodeDefaults = true }
    private val chatProvider = Provider(id = "chat-provider", baseUrl = "https://chat.example/v1")
    private val summaryProvider = Provider(id = "summary-provider", name = "前情服务", baseUrl = "https://summary.example/v1")
    private val actor = Character(id = "a", worldId = null, primary = ModelConfig(providerId = chatProvider.id),
        secondary = ModelConfig(providerId = chatProvider.id))
    private val room = ChatRoom(id = "room", worldId = null, members = listOf(Member("a", 0, true)))
    private fun empty() = AppState(providers = emptyList(), worlds = emptyList(), characters = emptyList(), rooms = emptyList())

    @Test fun compressionSettingsExportTheirProviderAndRemapItWithoutExportingAnySummaries() {
        val settings = CompressionSettings(keepRecentMessages = 7, model = ModelConfig(providerId = summaryProvider.id,
            model = "my-summary-model", thinking = ThinkingMode.OFF, temperature = .4, maxTokens = 3000), systemPrompt = "保留我们的故事口吻")
        val source = empty().copy(providers = listOf(chatProvider, summaryProvider), characters = listOf(actor),
            rooms = listOf(room.copy(compression = settings)), messages = listOf(ChatMessage(roomId = room.id, text = "私有聊天标记")),
            contextSummaries = listOf(ContextSummary(roomId = room.id, characterId = actor.id, text = "私有前情标记",
                sourceMessageIds = listOf("private-source-id"), sourceFingerprint = "a".repeat(64))))
        val exported = exportGroupPackage(source, room.id)
        assertEquals(settings, exported.room.compression)
        assertEquals(listOf(chatProvider.id, summaryProvider.id), exported.providers.map { it.id })
        val encoded = json.encodeToString(exported)
        listOf("私有前情标记", "私有聊天标记", "private-source-id", "contextSummaries").forEach { assertFalse(encoded.contains(it)) }
        val imported = importGroupPackage(empty(), json.decodeFromString<GroupPackage>(encoded))
        val restored = imported.state.rooms.single().compression
        val provider = imported.state.providers.single { it.id == restored.model.providerId }
        assertEquals(summaryProvider.baseUrl, provider.baseUrl)
        assertNotEquals(summaryProvider.id, provider.id)
        assertEquals(settings, restored.copy(model = restored.model.copy(providerId = settings.model.providerId)))
        assertTrue(imported.state.messages.isEmpty())
        assertTrue(imported.state.contextSummaries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            validateGroupPackage(exported.copy(providers = exported.providers.filterNot { it.id == summaryProvider.id }))
        }
    }

    @Test fun oldPackagesWithoutCompressionOrTheUnusedDefaultProviderStillImportAndReexport() {
        val source = empty().copy(providers = listOf(chatProvider), characters = listOf(actor), rooms = listOf(room))
        val exported = exportGroupPackage(source, room.id)
        assertEquals(listOf(chatProvider), exported.providers)
        val objectValue = json.parseToJsonElement(json.encodeToString(exported)).jsonObject
        val oldJson = JsonObject(objectValue + ("room" to JsonObject(objectValue.getValue("room").jsonObject - "compression")))
        val restored = json.decodeFromString<GroupPackage>(oldJson.toString())
        assertEquals(CompressionSettings(), restored.room.compression)
        validateGroupPackage(restored)
        val imported = importGroupPackage(empty(), restored)
        assertEquals(CompressionSettings(), imported.state.rooms.single().compression)
        assertEquals(1, exportGroupPackage(imported.state, imported.roomId).providers.size)
        assertThrows(IllegalArgumentException::class.java) {
            buildCompressionPlans(imported.state, imported.roomId, imported.state.rooms.single().compression)
        }
        // A fresh export does retain that same default service when it actually exists locally.
        val availableDefault = Provider(id = "siliconflow")
        val freshPackage = exportGroupPackage(source.copy(providers = source.providers + availableDefault), room.id)
        assertEquals(listOf(chatProvider.id, availableDefault.id), freshPackage.providers.map { it.id })
        val freshImport = importGroupPackage(empty(), freshPackage)
        assertEquals(availableDefault.baseUrl, freshImport.state.providers.single {
            it.id == freshImport.state.rooms.single().compression.model.providerId
        }.baseUrl)
    }

    @Test fun anEquivalentLocalCompressionServiceIsReusedAndExistingHistoryAndSummariesStayUntouched() {
        val settings = CompressionSettings(model = CompressionSettings().model.copy(providerId = summaryProvider.id))
        val source = empty().copy(providers = listOf(chatProvider, summaryProvider), characters = listOf(actor),
            rooms = listOf(room.copy(compression = settings)))
        val local = summaryProvider.copy(id = "local-summary", name = "本机已有整理服务")
        val localRoom = room.copy(id = "existing-room")
        val localMessage = ChatMessage(id = "existing-message", roomId = localRoom.id, text = "本机聊天")
        val localSummary = ContextSummary(roomId = localRoom.id, characterId = actor.id, text = "本机前情",
            sourceMessageIds = listOf(localMessage.id), sourceFingerprint = "b".repeat(64))
        val target = empty().copy(providers = listOf(local), rooms = listOf(localRoom), characters = listOf(actor),
            messages = listOf(localMessage), contextSummaries = listOf(localSummary))
        val imported = importGroupPackage(target, exportGroupPackage(source, room.id))
        assertEquals(local.id, imported.state.rooms.last().compression.model.providerId)
        assertEquals(local, imported.state.providers.first())
        assertSame(target.messages, imported.state.messages)
        assertSame(target.contextSummaries, imported.state.contextSummaries)
    }
}
