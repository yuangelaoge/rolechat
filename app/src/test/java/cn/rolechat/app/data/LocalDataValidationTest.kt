package cn.rolechat.app.data

import cn.rolechat.core.*
import org.junit.Assert.*
import org.junit.Test

class LocalDataValidationTest {
    @Test fun `dialogue extraction validates its independent model and editable prompt`() {
        val state = AppState()
        val valid = DialogueExtractionSettings(enabled = true)
        val settings = state.tts.copy(dialogueExtraction = valid)
        validateTtsSettings(settings, state.providers)
        listOf(
            valid.copy(model = valid.model.copy(providerId = "missing")),
            valid.copy(model = valid.model.copy(model = " ")),
            valid.copy(model = valid.model.copy(maxTokens = 0)),
            valid.copy(model = valid.model.copy(temperature = Double.NaN)),
            valid.copy(systemPrompt = " "),
            valid.copy(systemPrompt = "x".repeat(32_769))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                validateTtsSettings(settings.copy(dialogueExtraction = invalid), state.providers)
            }
        }
        // Disabling restores the original speech path even if an old saved extraction service is absent.
        validateTtsSettings(settings.copy(dialogueExtraction = valid.copy(enabled = false,
            model = valid.model.copy(providerId = "removed-service"))), state.providers)
    }

    @Test fun `dialogue extraction credentials are still tied to the selected provider origin`() {
        val initial = AppState(tts = TtsSettings(dialogueExtraction = DialogueExtractionSettings(enabled = true)))
        val changed = initial.copy(providers = initial.providers.map { it.copy(baseUrl = "https://changed.example/v1") })
        assertEquals(setOf(providerSecretId(changed.providers.single())), credentialsInvalidatedByImport(initial, changed))
        assertEquals(initial, validateImportedState(AppJson.decodeFromString<AppState>(
            AppJson.encodeToString(AppState.serializer(), initial))))
    }

    @Test fun `active SiliconFlow speech requires a valid service and finite speed`() {
        val original = AppState()
        val active = original.tts.copy(enabled = true, engine = TtsEngine.SILICONFLOW)
        assertEquals(active, validateImportedState(original.copy(tts = active)).tts)
        listOf(active.copy(siliconProviderId = "missing"), active.copy(siliconModel = " "),
            active.copy(siliconVoice = ""), active.copy(siliconSpeed = Double.NaN),
            active.copy(siliconSpeed = 4.1)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { validateImportedState(original.copy(tts = invalid)) }
        }
    }

    @Test fun `SiliconFlow speech credential is invalidated with its selected provider host`() {
        val previous = AppState(tts = TtsSettings(enabled = true, engine = TtsEngine.SILICONFLOW))
        val imported = previous.copy(providers = previous.providers.map { it.copy(baseUrl = "https://new.example/v1") })
        assertEquals(setOf(providerSecretId(imported.providers.single())), credentialsInvalidatedByImport(previous, imported))
    }

    @Test fun `credentials follow service identity even with reused provider ID`() {
        val original = Provider(id = "same", baseUrl = "https://API.example.com/v1/")
        val sameService = original.copy(baseUrl = "https://api.example.com:443/v2")
        val anotherService = original.copy(baseUrl = "https://another.example/v1")
        assertEquals(providerSecretId(original), providerSecretId(sameService))
        assertNotEquals(providerSecretId(original), providerSecretId(anotherService))
        assertNotEquals(providerSecretId(original), providerSecretId(original.copy(id = "another")))
    }

    @Test fun `import cannot reuse a credential for a changed host`() {
        val previous = AppState()
        val imported = previous.copy(providers = previous.providers.map { it.copy(baseUrl = "https://other.example/v1") })
        assertEquals(setOf(providerSecretId(imported.providers.first())), credentialsInvalidatedByImport(previous, imported))
        assertTrue(credentialsInvalidatedByImport(previous, previous).isEmpty())
    }

    @Test fun `legacy TTS token is bound to both origin and app ID`() {
        val original = TtsSettings(appId = "account-one")
        assertNotEquals(ttsTokenSecretId(original), ttsTokenSecretId(original.copy(appId = "account-two")))
        assertNotEquals(ttsApiSecretId(original), ttsApiSecretId(original.copy(endpoint = "wss://other.example/tts")))
    }

    @Test fun `import rejects credential-bearing and insecure addresses`() {
        listOf("http://api.example/v1", "https://user:password@api.example/v1", "https://api.example/v1?key=secret", "https://").forEach {
            assertThrows(IllegalArgumentException::class.java) { serviceOrigin(it) }
        }
    }

    @Test fun `import rejects duplicate actors and dangling membership`() {
        val original = AppState()
        assertThrows(IllegalArgumentException::class.java) {
            validateImportedState(original.copy(characters = original.characters + original.characters.first()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateImportedState(original.copy(rooms = listOf(ChatRoom(members = listOf(Member("missing"))))))
        }
    }

    @Test fun `interrupted generation recovers and deleted character history remains readable`() {
        val original = AppState()
        val message = ChatMessage(roomId = "lobby", characterId = "deleted-character", text = "已有正文",
            status = MessageStatus.GENERATING_B, segments = listOf(GenerationSegment(text = "已有正文")))
        val recovered = validateImportedState(original.copy(messages = listOf(message))).messages.single()
        assertEquals(MessageStatus.INTERRUPTED, recovered.status)
        assertEquals(SegmentStatus.INTERRUPTED, recovered.segments.single().status)
        assertEquals("已有正文", recovered.text)
    }
}
