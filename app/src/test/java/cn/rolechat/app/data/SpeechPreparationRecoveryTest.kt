package cn.rolechat.app.data

import cn.rolechat.core.AppState
import cn.rolechat.core.ChatMessage
import cn.rolechat.core.GenerationSegment
import cn.rolechat.core.SegmentStatus
import cn.rolechat.core.SpeechPreparationRecord
import cn.rolechat.core.SpeechPreparationStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SpeechPreparationRecoveryTest {
    @Test fun `loading an unfinished extraction cancels only its attempt and keeps saved diagnostics`() {
        val record = SpeechPreparationRecord(model = "small-model", rawOutput = "半个 JSON", reasoning = "提取中的思考")
        val reply = ChatMessage(id = "reply", roomId = "lobby", characterId = "lin", text = "已经完成的聊天正文",
            segments = listOf(GenerationSegment(text = "已经完成的聊天正文", status = SegmentStatus.COMPLETE)),
            speechPreparation = record)
        val recovered = validateImportedState(AppState(messages = listOf(reply))).messages.single()
        assertEquals(reply, recovered.copy(speechPreparation = record))
        val cancelled = requireNotNull(recovered.speechPreparation)
        assertEquals(SpeechPreparationStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.error.isNotBlank())
        assertEquals(record, cancelled.copy(status = record.status, error = record.error))
        assertEquals(recovered, validateImportedState(AppState(messages = listOf(recovered))).messages.single())
    }

    @Test fun `loading terminal extraction records preserves their complete contents`() {
        SpeechPreparationStatus.entries.filter { it != SpeechPreparationStatus.EXTRACTING }.forEach { status ->
            val record = SpeechPreparationRecord(model = "small-model", text = "对白", instruction = "轻声",
                rawOutput = "原始 JSON", reasoning = "提取思考", status = status, error = "记录说明")
            val reply = ChatMessage(roomId = "lobby", characterId = "lin", text = "完整聊天正文", speechPreparation = record)
            val state = AppState(messages = listOf(reply))
            assertEquals(state, validateImportedState(state))
        }
    }

    @Test fun `import rejects oversize diagnostic fields while accepting their exact limits`() {
        val record = SpeechPreparationRecord(model = "small-model", text = "t".repeat(32768), instruction = "i".repeat(1000),
            rawOutput = "j".repeat(32768), reasoning = "r".repeat(16384), error = "e".repeat(500),
            status = SpeechPreparationStatus.COMPLETE, previousInstruction = "p".repeat(1000), previousMessageId = "m".repeat(1024))
        val reply = ChatMessage(roomId = "lobby", characterId = "lin", speechPreparation = record)
        val state = AppState(messages = listOf(reply))
        assertEquals(state, validateImportedState(state))
        listOf(record.copy(text = record.text + "t"), record.copy(instruction = record.instruction + "i"),
            record.copy(rawOutput = record.rawOutput + "j"), record.copy(reasoning = record.reasoning + "r"),
            record.copy(error = record.error + "e"), record.copy(previousInstruction = record.previousInstruction + "p"),
            record.copy(previousMessageId = record.previousMessageId + "m")).forEach { oversized ->
            assertThrows(IllegalArgumentException::class.java) {
                validateImportedState(state.copy(messages = listOf(reply.copy(speechPreparation = oversized))))
            }
        }
    }

    @Test fun `old full backups without continuity fields remain loadable`() {
        val record = SpeechPreparationRecord(model = "small-model", text = "你好", instruction = "平静自然",
            status = SpeechPreparationStatus.COMPLETE)
        val state = AppState(messages = listOf(ChatMessage(roomId = "lobby", characterId = "lin", speechPreparation = record)))
        val serialized = AppJson.parseToJsonElement(AppJson.encodeToString(state)).jsonObject
        val legacy = JsonObject(serialized.toMutableMap().apply {
            put("messages", JsonArray(serialized.getValue("messages").jsonArray.map { message ->
                JsonObject(message.jsonObject.toMutableMap().apply {
                    put("speechPreparation", JsonObject(getValue("speechPreparation").jsonObject.filterKeys {
                        it != "previousInstruction" && it != "previousMessageId" && it != "continuityEligible"
                    }))
                })
            }))
        })
        val restored = validateImportedState(AppJson.decodeFromString<AppState>(legacy.toString()))
        assertEquals(state, restored)
        assertEquals("", restored.messages.single().speechPreparation!!.previousInstruction)
        assertNull(restored.messages.single().speechPreparation!!.previousMessageId)
        assertTrue(restored.messages.single().speechPreparation!!.continuityEligible)
    }

    @Test fun `backup preserves referenced voice instructions even when the source message was deleted`() {
        val record = SpeechPreparationRecord(model = "small-model", text = "我在这里", instruction = "继续轻声安慰",
            previousInstruction = "轻声，稍慢", previousMessageId = "deleted-original-message",
            status = SpeechPreparationStatus.COMPLETE)
        val state = AppState(messages = listOf(ChatMessage(roomId = "lobby", characterId = "lin", speechPreparation = record)))
        val restored = validateImportedState(AppJson.decodeFromString<AppState>(AppJson.encodeToString(state)))
        assertEquals(state, restored)
    }
}
