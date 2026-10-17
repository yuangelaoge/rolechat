package cn.rolechat.app.data

import cn.rolechat.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SmartRoutingRecoveryTest {
    @Test fun originalRequestAndTopicSnapshotsSurviveBackupWhileLegacyMessagesHaveNoInventedDiagnosis() {
        val request = ChatMessage(id = "request", roomId = "lobby", text = "讨论两个首映方案")
        val reply = ChatMessage(id = "reply", roomId = "lobby", characterId = "lin", text = "用例子说明分歧",
            requestMessageId = request.id, contextMessageIds = listOf(request.id), replyTopicStatus = TopicStatus.EXAMPLE,
            routingDecision = RoutingRecord(status = RoutingStatus.DECIDED, action = RoutingAction.ACTOR,
                targetId = "xia", selectedCharacterId = "xia", selectedName = "夏至", topicStatus = TopicStatus.OFF_TOPIC))
        val state = AppState(messages = listOf(request, reply))
        // Backups use encodeDefaults=true; plain Json can omit a timestamp that happens to equal the current millisecond.
        assertEquals(state, validateImportedState(AppJson.decodeFromString<AppState>(AppJson.encodeToString(state))))
        val old = Json.decodeFromString<ChatMessage>("""{"roomId":"lobby","text":"旧回复"}""")
        assertNull(old.requestMessageId)
        assertNull(old.replyTopicStatus)
        val oldRecord = Json.decodeFromString<RoutingRecord>("""{"status":"DECIDED","action":"STOP"}""")
        assertNull(oldRecord.topicStatus)
        assertTrue(runCatching { validateImportedState(state.copy(messages = listOf(
            reply.copy(requestMessageId = "x".repeat(1025))))) }.isFailure)
    }

    @Test fun interruptedDecisionIsRecoveredWithoutChangingCompletedDecisionsOrReplyReferences() {
        val pending = ChatMessage(id = "pending", roomId = "lobby", text = "谁接话？", replyToMessageId = "earlier",
            routingDecision = RoutingRecord(status = RoutingStatus.DECIDING, model = "router"))
        val complete = pending.copy(id = "complete", routingDecision = pending.routingDecision!!.copy(
            status = RoutingStatus.DECIDED, action = RoutingAction.WAIT_USER, reason = "等待真人"))
        val recovered = validateImportedState(AppState(messages = listOf(pending, complete))).messages
        assertEquals(RoutingStatus.CANCELLED, recovered.first().routingDecision!!.status)
        assertTrue(recovered.first().routingDecision!!.error.contains("中断"))
        assertEquals("earlier", recovered.first().replyToMessageId)
        assertEquals(complete, recovered.last())
    }

    @Test fun backupRejectsMissingSmartProviderAndExcessiveDecisionPayloadButKeepsLegacyRoomsValid() {
        val state = AppState()
        assertEquals(state, validateImportedState(state))
        assertTrue(runCatching { validateImportedState(state.copy(rooms = state.rooms.map { it.copy(
            mode = ReplyMode.SMART, routing = RoutingSettings(model = ModelConfig(providerId = "missing"))) })) }.isFailure)
        assertTrue(runCatching { validateImportedState(state.copy(messages = listOf(ChatMessage(roomId = "lobby",
            routingDecision = RoutingRecord(rawOutput = "x".repeat(16385)))))) }.isFailure)
        assertTrue(runCatching { validateImportedState(state.copy(rooms = state.rooms.map { it.copy(
            mode = ReplyMode.SMART, routing = RoutingSettings(maxAutoReplies = 0)) })) }.isFailure)
    }
}
