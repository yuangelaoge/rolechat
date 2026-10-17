package cn.rolechat.app.speech

import cn.rolechat.app.data.AppJson
import cn.rolechat.app.data.validateImportedState
import cn.rolechat.core.AppState
import cn.rolechat.core.Character
import cn.rolechat.core.ChatMessage
import cn.rolechat.core.SpeechPreparationRecord
import cn.rolechat.core.SpeechPreparationStatus
import cn.rolechat.core.SpeechVoiceSnapshot
import cn.rolechat.core.TtsEngine
import cn.rolechat.core.TtsSettings
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SpeechVoiceSnapshotTest {
    @Test fun `Doubao snapshot records the normalized resource and role voice without guessing a replacement`() {
        val settings = TtsSettings(resourceId = " seed-tts-2.0\n")
        val actor = Character(voice = " zh_female_cancan_mars_bigtts ")
        assertEquals(SpeechVoiceSnapshot(TtsEngine.DOUBAO, "seed-tts-2.0", "zh_female_cancan_mars_bigtts"),
            speechVoiceSnapshot(settings, actor))
        assertEquals(" seed-tts-2.0\n", settings.resourceId)
        assertEquals(" zh_female_cancan_mars_bigtts ", actor.voice)
    }

    @Test fun `SiliconFlow snapshot resolves inherited and overridden values exactly as the HTTP request`() {
        val settings = TtsSettings(engine = TtsEngine.SILICONFLOW, siliconModel = " global/model ", siliconVoice = " alex ")
        assertEquals(SpeechVoiceSnapshot(TtsEngine.SILICONFLOW, "global/model", "global/model:alex"),
            speechVoiceSnapshot(settings, Character(siliconModel = " \n", siliconVoice = "\t")))
        assertEquals(SpeechVoiceSnapshot(TtsEngine.SILICONFLOW, "role/model", "role/model:anna"),
            speechVoiceSnapshot(settings, Character(siliconModel = " role/model ", siliconVoice = " anna ")))
        assertEquals(SpeechVoiceSnapshot(TtsEngine.SILICONFLOW, "role/model", "speech:custom-id"),
            speechVoiceSnapshot(settings, Character(siliconModel = "role/model", siliconVoice = " speech:custom-id ")))
        assertEquals(SpeechVoiceSnapshot(TtsEngine.SILICONFLOW, "", ""),
            speechVoiceSnapshot(settings.copy(siliconModel = "", siliconVoice = ""), Character()))
    }

    @Test fun `legacy records leave unknown invocation and voice unset rather than inventing history`() {
        val old = AppJson.decodeFromString<SpeechPreparationRecord>(
            """{"id":"old-attempt","model":"old-model","status":"ERROR","error":"旧错误","createdAt":100}""")
        assertNull(old.voice)
        assertNull(old.modelInvoked)
        assertEquals("旧错误", old.error)
        assertEquals(old, AppJson.decodeFromString<SpeechPreparationRecord>(AppJson.encodeToString(old)))
    }

    @Test fun `voice and invocation metadata survive message backup and recovery without becoming current settings`() {
        listOf(false, true).forEach { invoked ->
            val record = SpeechPreparationRecord(model = "small-model", status = SpeechPreparationStatus.ERROR,
                voice = SpeechVoiceSnapshot(TtsEngine.DOUBAO, "seed-tts-2.0", "zh_female_vv_uranus_bigtts"),
                modelInvoked = invoked, error = "当次失败", createdAt = 123)
            val state = AppState(messages = listOf(ChatMessage(roomId = "lobby", characterId = "lin", speechPreparation = record)))
            val reloaded = validateImportedState(AppJson.decodeFromString<AppState>(AppJson.encodeToString(state)))
            assertEquals(state, reloaded)
            assertEquals("seed-tts-1.0", reloaded.tts.resourceId)
            assertEquals("seed-tts-2.0", reloaded.messages.single().speechPreparation?.voice?.resourceId)
        }
    }

    @Test fun `import bounds voice metadata and accepts exact limits`() {
        val voice = SpeechVoiceSnapshot(TtsEngine.SILICONFLOW, "m".repeat(2048), "v".repeat(2048))
        val record = SpeechPreparationRecord(status = SpeechPreparationStatus.COMPLETE, voice = voice, modelInvoked = true)
        val message = ChatMessage(roomId = "lobby", characterId = "lin", speechPreparation = record)
        val state = AppState(messages = listOf(message))
        assertEquals(state, validateImportedState(state))
        listOf(voice.copy(resourceId = voice.resourceId + "m"), voice.copy(voiceId = voice.voiceId + "v")).forEach { oversized ->
            assertThrows(IllegalArgumentException::class.java) {
                validateImportedState(state.copy(messages = listOf(message.copy(speechPreparation = record.copy(voice = oversized)))))
            }
        }
    }
}
