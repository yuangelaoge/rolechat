package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.Character
import cn.rolechat.core.ChatMessage
import cn.rolechat.core.DialogueExtractionSettings
import cn.rolechat.core.GenerationSegment
import cn.rolechat.core.ModelConfig
import cn.rolechat.core.Provider
import cn.rolechat.core.StateStore
import cn.rolechat.core.TtsEngine
import cn.rolechat.core.TtsSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SpeechPreviewTest {
    @Test fun `both engines preview draft voice using the captured service settings with extraction disabled`() {
        val saved = Character(id = "role", name = "已保存名字", voice = "saved-doubao-voice",
            siliconModel = "saved-model", siliconVoice = "saved-silicon-voice")
        val draft = saved.copy(name = "  试听名字  ", voice = "zh_female_vv_uranus_bigtts",
            siliconModel = "draft-model", siliconVoice = "draft-silicon-voice")
        TtsEngine.entries.forEach { engine ->
            val tts = TtsSettings(
                enabled = false, engine = engine, resourceId = "seed-tts-2.0",
                appId = "test-app", endpoint = "wss://speech.example/bidirection",
                siliconProviderId = "speech-provider", siliconModel = "global-model",
                siliconVoice = "global-voice", siliconSpeed = 1.2,
                dialogueExtraction = DialogueExtractionSettings(enabled = true,
                    model = ModelConfig(providerId = "extract-provider", model = "extract-model"),
                    systemPrompt = "This must not be sent by a voice preview")
            )
            val state = AppState(tts = tts, characters = listOf(saved), providers = listOf(
                Provider(id = "speech-provider", baseUrl = "https://speech.example/v1")
            ))
            val preview = prepareSpeechPreview(state, draft)
            assertSame(draft, preview.character)
            assertEquals("zh_female_vv_uranus_bigtts", preview.character.voice)
            assertEquals("draft-model", preview.character.siliconModel)
            assertEquals("draft-silicon-voice", preview.character.siliconVoice)
            assertEquals("你好，我是试听名字，很高兴见到你。", preview.text)
            assertEquals(tts.copy(enabled = true,
                dialogueExtraction = tts.dialogueExtraction.copy(enabled = false)), preview.snapshot.tts)
            assertSame(state.providers, preview.snapshot.providers)
            assertFalse(state.tts.enabled)
            assertTrue(state.tts.dialogueExtraction.enabled)
        }
    }

    @Test fun `preview never persists draft settings or appends a history message`() {
        val saved = Character(id = "existing", name = "原名字", voice = "original-voice")
        val message = ChatMessage(id = "history", roomId = "lobby", characterId = saved.id,
            senderName = saved.name, text = "原来的正文", segments = listOf(
                GenerationSegment(id = "segment", text = "原来的正文", reasoning = "原来的思考")
            ))
        val initial = AppState(characters = listOf(saved), messages = listOf(message),
            tts = TtsSettings(enabled = false, dialogueExtraction = DialogueExtractionSettings(enabled = true)))
        val store = StateStore(initial)
        val draft = Character(id = "unsaved", name = "新角色", voice = "unsaved-voice",
            siliconModel = "", siliconVoice = "")
        val preview = prepareSpeechPreview(store.snapshot(), draft)
        assertSame(initial, store.snapshot())
        assertSame(initial.characters, preview.snapshot.characters)
        assertSame(initial.messages, preview.snapshot.messages)
        assertSame(initial.rooms, preview.snapshot.rooms)
        assertSame(initial.worlds, preview.snapshot.worlds)
        assertEquals(listOf(saved), store.snapshot().characters)
        assertEquals(listOf(message), store.snapshot().messages)
        assertFalse(preview.snapshot.characters.any { it.id == draft.id })
        assertFalse(preview.snapshot.messages.any { it.id == preview.messageId })
        assertEquals("", preview.character.siliconModel)
        assertEquals("", preview.character.siliconVoice)
        assertEquals(initial.tts.siliconModel, preview.snapshot.tts.siliconModel)
        assertEquals(initial.tts.siliconVoice, preview.snapshot.tts.siliconVoice)
        assertEquals("你好，我是新角色，很高兴见到你。", preview.text)
    }

    @Test fun `blank draft names use the role ID and every preview has an independent message ID`() {
        val state = AppState()
        val character = Character(id = "draft-role", name = " \n\t ")
        val first = prepareSpeechPreview(state, character)
        val second = prepareSpeechPreview(state, character)
        assertEquals("你好，我是draft-role，很高兴见到你。", first.text)
        assertNotEquals(first.messageId, second.messageId)
        assertTrue(first.messageId.startsWith("voice-preview-"))
        assertFalse(first.snapshot.tts.dialogueExtraction.enabled)
    }

    @Test fun `preview validation catches default voice after global two preset and encodes a repaired matching pair`() {
        val original = AppState(tts = TtsSettings(enabled = false, resourceId = "seed-tts-2.0"))
        val defaultRole = original.characters.first()
        val incompatible = prepareSpeechPreview(original, defaultRole)
        assertEquals("zh_female_cancan_mars_bigtts", incompatible.character.voice)
        assertThrows(IllegalArgumentException::class.java) {
            resolveDoubaoVoice(incompatible.snapshot.tts.resourceId, incompatible.character.voice)
        }
        val repairedDraft = defaultRole.copy(voice = "  zh_female_vv_uranus_bigtts\n")
        val preview = prepareSpeechPreview(original, repairedDraft)
        val resolved = resolveDoubaoVoice(preview.snapshot.tts.resourceId, preview.character.voice)
        val settings = preview.snapshot.tts.copy(resourceId = resolved.resourceId)
        val params = Json.parseToJsonElement(doubaoRequestBody(DoubaoProtocol.START_SESSION,
            resolved.voiceId, "", 24_000, settings)).jsonObject.getValue("req_params").jsonObject
        assertEquals("seed-tts-2.0", settings.resourceId)
        assertEquals("zh_female_vv_uranus_bigtts", params.getValue("speaker").jsonPrimitive.content)
        assertFalse(params.containsKey("additions"))
        assertEquals("zh_female_cancan_mars_bigtts", original.characters.first().voice)
        assertFalse(original.tts.enabled)
        assertTrue(original.messages.isEmpty())
    }
}
