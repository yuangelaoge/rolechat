package cn.rolechat.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ContextCompressionTest {
    private val a = Character(id = "a", name = "A")
    private val b = Character(id = "b", name = "B")
    private val settings = CompressionSettings(keepRecentMessages = 2)
    private val room = ChatRoom(id = "room", members = listOf(Member("a", 0, true), Member("b", 3, false)))
    private fun message(index: Int) = ChatMessage(id = "m$index", roomId = "room", characterId = if (index % 2 == 0) "a" else null,
        senderName = if (index % 2 == 0) "A" else "我", text = "原文-$index", createdAt = index.toLong())
    private fun state() = AppState(characters = listOf(a, b), rooms = listOf(room), messages = (1..6).map(::message))
    private fun applied(original: AppState = state()): AppState {
        val plans = buildCompressionPlans(original, room.id, settings)
        return applyCompressionDrafts(original, settings, plans, plans.associate { it.characterId to "${it.characterName} 的独立前情" })
    }

    @Test fun plansRespectEachMembersVisibilityAndApplyAllDraftsWithoutChangingMessages() {
        val original = state().let { it.copy(messages = it.messages + listOf(message(7).copy(status = MessageStatus.GENERATING_A),
            message(8).copy(text = "  "), message(9).copy(roomId = "other"))) }
        val plans = buildCompressionPlans(original, room.id, settings)
        assertEquals(listOf("a", "b"), plans.map { it.characterId })
        assertEquals(listOf("m1", "m2", "m3", "m4"), plans[0].sourceMessages.map { it.id })
        assertEquals(listOf("m3", "m4"), plans[1].sourceMessages.map { it.id })
        assertTrue(plans.all { it.recentMessages.map(ChatMessage::id) == listOf("m5", "m6") })
        val compressed = applyCompressionDrafts(original, settings, plans, mapOf("a" to "A看过早期秘密", "b" to "B只有入群后经历"))
        assertEquals(original.messages, compressed.messages)
        assertEquals(settings, compressed.rooms.single().compression)
        plans.forEach { plan ->
            val actor = compressed.characters.single { it.id == plan.characterId }
            val summary = validContextSummary(compressed, compressed.rooms.single(), actor, plan.sourceMessages + plan.recentMessages)
            assertEquals(plan.sourceMessages.map { it.id }, summary?.sourceMessageIds)
            assertEquals(plan.sourceFingerprint, summary?.sourceFingerprint)
            assertEquals(64, summary!!.sourceFingerprint.length)
        }
        assertFalse(compressed.contextSummaries.single { it.characterId == "b" }.sourceMessageIds.contains("m1"))
    }

    @Test fun aValidOlderSummaryCanBeReusedOnlyAsTheNewSourcePrefix() {
        val compressed = applied()
        val expanded = compressed.copy(messages = compressed.messages + listOf(message(7), message(8)))
        val plans = buildCompressionPlans(expanded, room.id, settings)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), plans.first().sourceMessages.map { it.id })
        assertEquals(compressed.contextSummaries.single { it.characterId == "a" }, plans.first().previousSummary)
        assertEquals(listOf("m5", "m6"), plans.first().sourceMessages.drop(plans.first().previousSummary!!.sourceMessageIds.size).map { it.id })
        val next = applyCompressionDrafts(expanded, settings, plans, plans.associate { it.characterId to "更新的前情-${it.characterId}" })
        assertEquals(2, next.contextSummaries.size)
        assertEquals(plans.first().sourceFingerprint, next.contextSummaries.single { it.characterId == "a" }.sourceFingerprint)
        val shorterPrefix = buildCompressionPlans(compressed, room.id, settings.copy(keepRecentMessages = 4))
        assertTrue(shorterPrefix.all { it.previousSummary == null })
    }

    @Test fun textIdentityStatusAndPermissionsInvalidateSummariesWhileCotRoutingAndSpeechMetadataDoNot() {
        val compressed = applied()
        val aHistory = compressed.messages
        val decorated = compressed.copy(messages = compressed.messages.map { it.copy(
            segments = listOf(GenerationSegment(reasoning = "新的COT")),
            routingDecision = RoutingRecord(reason = "仅路由元数据"), speechPreparation = SpeechPreparationRecord(rawOutput = "提取数据")) })
        assertNotNull(validContextSummary(decorated, room, a, decorated.messages))
        val variants = listOf(
            compressed.copy(messages = compressed.messages.map { if (it.id == "m1") it.copy(text = "已编辑原文") else it }),
            compressed.copy(messages = compressed.messages.map { if (it.id == "m2") it.copy(senderName = "更正署名") else it }),
            compressed.copy(messages = compressed.messages.map { if (it.id == "m2") it.copy(messageStyle = MessageStyle.SCENE) else it }),
            compressed.copy(messages = compressed.messages.map { if (it.id == "m2") it.copy(status = MessageStatus.INTERRUPTED) else it }),
            compressed.copy(rooms = listOf(room.copy(members = room.members.map { if (it.characterId == "a") it.copy(readHistory = false) else it }))),
            compressed.copy(characters = listOf(a.copy(name = "改名A"), b))
        )
        variants.forEach { changed ->
            assertNull(validContextSummary(changed, changed.rooms.single(), changed.characters.first(), changed.messages))
            assertNull(validContextSummary(changed, room, a, aHistory))
        }
    }

    @Test fun draftApplicationRejectsChangedSourcesRecentRangesMembersAndPartialResultsAtomically() {
        val original = state()
        val plans = buildCompressionPlans(original, room.id, settings)
        val texts = plans.associate { it.characterId to "候选前情" }
        val variants = listOf(
            original.copy(messages = original.messages.map { if (it.id == "m1") it.copy(text = "改旧段") else it }),
            original.copy(messages = original.messages.map { if (it.id == "m6") it.copy(text = "改近期段") else it }),
            original.copy(messages = original.messages + message(7)),
            original.copy(characters = listOf(a.copy(name = "新A"), b)),
            original.copy(rooms = listOf(room.copy(members = listOf(Member("a", 0, true))))),
            original.copy(rooms = listOf(room.copy(members = room.members.map { if (it.characterId == "b") it.copy(readHistory = true) else it })))
        )
        variants.forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { applyCompressionDrafts(changed, settings, plans, texts) }
            assertTrue(changed.contextSummaries.isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) { applyCompressionDrafts(original, settings, plans, mapOf("a" to "只完成一个")) }
        assertThrows(IllegalArgumentException::class.java) { applyCompressionDrafts(original, settings, plans, texts + ("a" to " ")) }
        assertThrows(IllegalArgumentException::class.java) { applyCompressionDrafts(original, settings, plans, texts + ("a" to "x".repeat(32769))) }
        val decorated = original.copy(messages = original.messages.map { it.copy(routingDecision = RoutingRecord()) })
        assertEquals(2, applyCompressionDrafts(decorated, settings, plans, texts).contextSummaries.size)
        val alreadyCompressed = applied()
        val reuse = buildCompressionPlans(alreadyCompressed, room.id, settings)
        assertThrows(IllegalArgumentException::class.java) {
            applyCompressionDrafts(alreadyCompressed.copy(contextSummaries = emptyList()), settings, reuse, texts)
        }
    }

    @Test fun legacyJsonDefaultsAndCompleteBackupsRoundTripWithoutChangingTheConfirmedPrompt() {
        val json = Json { encodeDefaults = true }
        val legacy = json.decodeFromString<AppState>("""{"rooms":[{"id":"old"}]}""")
        assertTrue(legacy.contextSummaries.isEmpty())
        assertEquals(CompressionSettings(), legacy.rooms.single().compression)
        val compressed = applied()
        assertEquals(compressed, json.decodeFromString<AppState>(json.encodeToString(compressed)))
        assertTrue(DEFAULT_COMPRESSION_PROMPT.startsWith("请把这段角色扮演的旧聊天整理成简洁的前情回顾"))
        assertTrue(DEFAULT_COMPRESSION_PROMPT.endsWith("保留原来故事的味道。"))
        assertFalse(DEFAULT_COMPRESSION_PROMPT.contains("尽量精简"))
        assertThrows(IllegalArgumentException::class.java) { validateCompressionSettings(settings.copy(keepRecentMessages = 0)) }
        assertThrows(IllegalArgumentException::class.java) { validateCompressionSettings(settings.copy(model = settings.model.copy(temperature = Double.NaN))) }
        validateCompressionSettings(CompressionSettings(), null)
        assertThrows(IllegalArgumentException::class.java) { validateCompressionSettings(CompressionSettings(), emptyList()) }
    }
}
