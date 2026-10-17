package cn.rolechat.app.data

import cn.rolechat.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContextSummaryBackupTest {
    private fun state(): AppState {
        val actor = AppState().characters.first()
        val room = ChatRoom(id = "summary-room", members = listOf(Member(actor.id, 0, true)),
            compression = CompressionSettings(keepRecentMessages = 1))
        val original = AppState(characters = listOf(actor), rooms = listOf(room), messages = listOf(
            ChatMessage(id = "old-human", roomId = room.id, text = "周末一起散步吧。"),
            ChatMessage(id = "old-role", roomId = room.id, characterId = actor.id, senderName = actor.name, text = "好呀。"),
            ChatMessage(id = "recent", roomId = room.id, text = "明天几点见？")))
        val plans = buildCompressionPlans(original, room.id, room.compression)
        return applyCompressionDrafts(original, room.compression, plans, mapOf(actor.id to "PRIVATE_SUMMARY：两人约好周末散步，时间未定。"))
    }

    @Test fun completePrivateBackupRoundTripsSummarySettingsAndAllOriginalMessages() {
        val original = state()
        val encoded = AppJson.encodeToString(original)
        assertTrue(encoded.contains("PRIVATE_SUMMARY"))
        assertTrue(encoded.contains("周末一起散步吧。"))
        val restored = validateImportedState(AppJson.decodeFromString<AppState>(encoded))
        assertEquals(original, restored)
        val room = restored.rooms.single()
        assertEquals(restored.contextSummaries.single(), validContextSummary(restored, room, restored.characters.single(), restored.messages))
        assertTrue(credentialsInvalidatedByImport(original, restored).isEmpty())
    }

    @Test fun oldBackupWithoutCompressionFieldsLoadsEvenWhenOnlyACustomProviderExists() {
        val provider = Provider(id = "custom", baseUrl = "https://custom.example/v1")
        val original = AppState(providers = listOf(provider), characters = AppState().characters.map {
            it.copy(primary = it.primary.copy(providerId = provider.id), secondary = it.secondary.copy(providerId = provider.id))
        })
        val current = AppJson.parseToJsonElement(AppJson.encodeToString(original)).jsonObject
        val legacy = JsonObject(current.filterKeys { it != "contextSummaries" }.toMutableMap().apply {
            put("rooms", JsonArray(current["rooms"]!!.jsonArray.map { JsonObject(it.jsonObject.filterKeys { key -> key != "compression" }) }))
        })
        val restored = validateImportedState(AppJson.decodeFromString<AppState>(legacy.toString()))
        assertTrue(restored.contextSummaries.isEmpty())
        assertEquals(CompressionSettings(), restored.rooms.single().compression)
        assertEquals(listOf(provider), restored.providers)
        // An unavailable default is validated when the user actually asks to compress.
        assertThrows(IllegalArgumentException::class.java) {
            buildCompressionPlans(restored, restored.rooms.single().id, restored.rooms.single().compression)
        }
    }

    @Test fun staleFingerprintOrMissingSourceDoesNotRejectTheBackupAndCannotReplaceOriginalContext() {
        val original = state()
        val variants = listOf(original.copy(messages = original.messages.map {
            if (it.id == "old-human") it.copy(text = "改为周末去看电影。") else it
        }), original.copy(messages = original.messages.filterNot { it.id == "old-human" }),
            original.copy(contextSummaries = original.contextSummaries.map { it.copy(sourceFingerprint = "0".repeat(64)) }))
        for (candidate in variants) {
            val restored = validateImportedState(AppJson.decodeFromString<AppState>(AppJson.encodeToString(candidate)))
            assertEquals(candidate.messages, restored.messages)
            assertEquals(candidate.contextSummaries, restored.contextSummaries)
            assertNull(validContextSummary(restored, restored.rooms.single(), restored.characters.single(), restored.messages))
        }
    }

    @Test fun malformedOrDanglingActiveSummariesAreRejectedWithoutSourceContentInErrors() {
        val original = state()
        val summary = original.contextSummaries.single()
        val invalid = listOf(summary.copy(id = ""), summary.copy(id = "x".repeat(257)), summary.copy(roomId = "missing"),
            summary.copy(characterId = "missing"), summary.copy(text = " "), summary.copy(text = "x".repeat(32769)),
            summary.copy(sourceMessageIds = emptyList()), summary.copy(sourceMessageIds = listOf(" ")),
            summary.copy(sourceMessageIds = listOf("same", "same")), summary.copy(sourceMessageIds = listOf("x".repeat(257))),
            summary.copy(sourceMessageIds = List(100001) { "source-$it" }), summary.copy(sourceFingerprint = "bad"),
            summary.copy(sourceFingerprint = "z".repeat(64)))
        for (bad in invalid) {
            val error = assertThrows(IllegalArgumentException::class.java) { validateImportedState(original.copy(contextSummaries = listOf(bad))) }
            assertFalse(error.message.orEmpty().contains("PRIVATE_SUMMARY"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateImportedState(original.copy(contextSummaries = listOf(summary, summary.copy(id = "different"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateImportedState(original.copy(contextSummaries = List(10001) { summary.copy(id = "summary-$it") }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateImportedState(original.copy(rooms = original.rooms.map { it.copy(members = emptyList()) }))
        }
    }

    @Test fun exactTextBoundaryAndHistoricalSourceIdsArePreservedForEngineFallback() {
        val original = state()
        val summary = original.contextSummaries.single().copy(text = "前".repeat(32768),
            sourceMessageIds = listOf("deleted-old-source"), sourceFingerprint = "F".repeat(64))
        assertEquals(summary, validateImportedState(original.copy(contextSummaries = listOf(summary))).contextSummaries.single())
    }

    @Test fun invalidSavedCompressionConfigurationIsRejectedButMissingProviderIsNot() {
        val original = state()
        val valid = original.rooms.single().compression
        val invalid = listOf(valid.copy(keepRecentMessages = 0), valid.copy(keepRecentMessages = 201),
            valid.copy(systemPrompt = " "), valid.copy(systemPrompt = "x".repeat(32769)),
            valid.copy(model = valid.model.copy(maxTokens = 127)), valid.copy(model = valid.model.copy(maxTokens = 16385)),
            valid.copy(model = valid.model.copy(temperature = Double.NaN)))
        for (settings in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                validateImportedState(original.copy(rooms = original.rooms.map { it.copy(compression = settings) }))
            }
        }
        val missing = valid.copy(model = valid.model.copy(providerId = "removed-service"))
        assertEquals(missing, validateImportedState(original.copy(rooms = original.rooms.map { it.copy(compression = missing) })).rooms.single().compression)
    }

    @Test fun groupPackageCarriesOnlyCompressionPreferencesAndCannotTransferPrivateSummaries() {
        val original = state()
        val encoded = encodeGroupConfiguration(exportGroupPackage(original, original.rooms.single().id))
        assertTrue(encoded.contains("compression"))
        assertFalse(encoded.contains("contextSummaries"))
        assertFalse(encoded.contains("PRIVATE_SUMMARY"))
        assertFalse(encoded.contains("周末一起散步吧。"))
        val imported = importGroupPackage(original, decodeGroupConfiguration(encoded))
        assertEquals(original.contextSummaries, imported.state.contextSummaries)
        assertEquals(original.messages, imported.state.messages)
        assertTrue(imported.state.contextSummaries.none { it.roomId == imported.roomId })
        assertEquals(original.rooms.single().compression, imported.state.rooms.single { it.id == imported.roomId }.compression)
    }
}
