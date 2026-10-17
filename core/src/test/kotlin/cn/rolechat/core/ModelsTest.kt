package cn.rolechat.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun oldBackupWithoutRoomIdentityFieldsUsesCompatibleDefaults() {
        val restored = json.decodeFromString<AppState>("""
            {
              "rooms": [{"id":"legacy-room","name":"旧群聊","members":[{"characterId":"lin","joinedAt":0,"readHistory":true}]}],
              "messages": [{"id":"legacy-message","roomId":"legacy-room","senderName":"我","text":"旧消息","createdAt":1}]
            }
        """.trimIndent())
        assertEquals("我", restored.rooms.single().userName)
        assertEquals("", restored.rooms.single().userBackground)
        assertEquals("我", restored.messages.single().senderName)
        assertEquals("旧消息", restored.messages.single().text)
        assertTrue(restored.rooms.single().members.single().readHistory)
        assertEquals(false, restored.rooms.single().pinned)
    }

    @Test fun differentRoomIdentitiesAndOriginalMessageSignaturesRoundTripInBackup() {
        val rooms = listOf(
            ChatRoom(id = "forest", name = "森林", userName = "林客", userBackground = "住在森林的小屋里。\n擅长观察鸟类。"),
            ChatRoom(id = "ocean", name = "海洋", userName = "舟舟 🌊", userBackground = "正在远洋航行的船长。"))
        val original = AppState(rooms = rooms, messages = listOf(
            ChatMessage(id = "past", roomId = "forest", senderName = "以前的名字", text = "这条署名不能被回写")))
        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<AppState>(encoded)
        assertEquals(original, restored)
        assertTrue(encoded.contains("\"userName\""))
        assertTrue(encoded.contains("\"userBackground\""))
        assertEquals("以前的名字", restored.messages.single().senderName)
    }

    @Test fun oldSpeechSettingsRemainDoubaoAndNewRoleOverridesDefaultToEmpty() {
        val restored = json.decodeFromString<AppState>("""
            {
              "tts":{"enabled":true,"resourceId":"old-resource","appId":"old-app","endpoint":"wss://old.example/tts"},
              "characters":[{"id":"legacy-role","name":"旧角色","voice":"old-doubao-voice"}]
            }
        """.trimIndent())
        assertEquals(TtsEngine.DOUBAO, restored.tts.engine)
        assertEquals("old-resource", restored.tts.resourceId)
        assertEquals("old-app", restored.tts.appId)
        assertEquals("wss://old.example/tts", restored.tts.endpoint)
        assertEquals("old-doubao-voice", restored.characters.single().voice)
        assertEquals("", restored.characters.single().siliconModel)
        assertEquals("", restored.characters.single().siliconVoice)
    }

    @Test fun siliconSpeechSettingsAndRoleOverridesRoundTripWithoutReplacingDoubaoSettings() {
        val original = AppState(tts = TtsSettings(enabled = true, engine = TtsEngine.SILICONFLOW,
            siliconProviderId = "custom-provider", siliconModel = "any/new-model", siliconVoice = "anna", siliconSpeed = 1.3,
            resourceId = "keep-doubao-resource", appId = "keep-doubao-app"),
            characters = listOf(Character(id = "voice-role", siliconModel = "role/model",
                siliconVoice = "speech:custom:voice-id", voice = "keep-doubao-voice")))
        assertEquals(original, json.decodeFromString<AppState>(json.encodeToString(original)))
    }

    @Test fun oldBackupsKeepOrdinaryCharacterAndMessagePresentationByDefault() {
        val restored = json.decodeFromString<AppState>("""
            {
              "characters":[{"id":"legacy-role","name":"原来的角色"}],
              "messages":[
                {"id":"human","roomId":"room","text":"之前的发言"},
                {"id":"reply","roomId":"room","characterId":"legacy-role","senderName":"原来的角色","text":"之前的回复"}
              ]
            }
        """.trimIndent())
        assertEquals(MessageStyle.CHAT, restored.characters.single().messageStyle)
        assertEquals(listOf(MessageStyle.CHAT, MessageStyle.CHAT), restored.messages.map { it.messageStyle })
        assertEquals(listOf(null, "legacy-role"), restored.messages.map { it.characterId })
        assertEquals("之前的回复", restored.messages.last().text)
    }

    @Test fun backupRoundTripPreservesSceneMessageSnapshotsIndependentlyOfCurrentCharacters() {
        val original = AppState(characters = listOf(
            Character(id = "narrator", name = "新名字", messageStyle = MessageStyle.CHAT),
            Character(id = "scene-role", messageStyle = MessageStyle.SCENE)
        ), messages = listOf(
            ChatMessage(id = "past-scene", roomId = "room", characterId = "narrator", senderName = "旧旁白",
                text = "窗外的雨停了。", messageStyle = MessageStyle.SCENE),
            ChatMessage(id = "deleted-role-scene", roomId = "room", characterId = "removed-role", senderName = "旧场景角色",
                text = "脚步声从走廊传来。", messageStyle = MessageStyle.SCENE),
            ChatMessage(id = "ordinary", roomId = "room", text = "我去开门。")
        ))
        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<AppState>(encoded)
        assertEquals(original, restored)
        assertTrue(encoded.contains("\"messageStyle\":\"SCENE\""))
        assertEquals(MessageStyle.CHAT, restored.characters.first().messageStyle)
        assertEquals(listOf(MessageStyle.SCENE, MessageStyle.SCENE, MessageStyle.CHAT), restored.messages.map { it.messageStyle })
        assertEquals("旧旁白", restored.messages.first().senderName)
    }

    @Test fun legacySegmentsDefaultToNoReasoningAndSeparateAAndBReasoningRoundTrips() {
        val restored = json.decodeFromString<ChatMessage>("""
            {"roomId":"room","characterId":"actor","text":"旧正文",
             "segments":[{"label":"A","model":"old-model","text":"旧正文","status":"COMPLETE"}]}
        """.trimIndent())
        assertEquals("", restored.segments.single().reasoning)
        assertEquals("旧正文", restored.text)
        val updated = restored.copy(segments = listOf(
            restored.segments.single().copy(reasoning = "A 的第一步\nA 的第二步", status = SegmentStatus.CUT),
            GenerationSegment(label = "B", model = "next-model", text = "续文", reasoning = "B 的思考", status = SegmentStatus.COMPLETE)
        ), text = "旧正文续文")
        val backup = AppState(messages = listOf(updated))
        assertEquals(backup, json.decodeFromString<AppState>(json.encodeToString(backup)))
        assertEquals("旧正文续文", updated.text)
    }

    @Test fun pinnedAndUnpinnedRoomsRoundTripIndependentlyInBackups() {
        val original = AppState(rooms = listOf(
            ChatRoom(id = "pinned", name = "置顶的群", pinned = true, userName = "旅人"),
            ChatRoom(id = "ordinary", name = "普通的群", pinned = false)
        ))
        val restored = json.decodeFromString<AppState>(json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(listOf(true, false), restored.rooms.map { it.pinned })
        assertEquals("旅人", restored.rooms.first().userName)
    }

    @Test fun legacyBackupsLeaveReasoningCompatibilityAndDialogueExtractionOff() {
        val restored = json.decodeFromString<AppState>("""{
            "characters":[{"id":"legacy","name":"旧角色"}],
            "tts":{"enabled":true},
            "messages":[{"roomId":"room","segments":[{"text":"原文"}]}]
        }""")
        assertEquals(ReplyOutputSource.CONTENT, restored.characters.single().secondaryOutputSource)
        assertEquals(ReplyOutputSource.CONTENT, restored.messages.single().segments.single().outputSource)
        assertEquals(false, restored.tts.dialogueExtraction.enabled)
        assertEquals(DEFAULT_DIALOGUE_EXTRACTION_PROMPT, restored.tts.dialogueExtraction.systemPrompt)
    }

    @Test fun speechExtractionAndBOutputSourceRoundTripWithoutLeakingIntoGroupPackages() {
        val original = AppState().let { state -> state.copy(
            characters = state.characters.map { it.copy(secondaryOutputSource = ReplyOutputSource.REASONING) },
            tts = state.tts.copy(dialogueExtraction = DialogueExtractionSettings(enabled = true,
                model = ModelConfig(providerId = "extractor", model = "small/speech-filter", thinking = ThinkingMode.OFF,
                    temperature = 0.2, maxTokens = 3000), systemPrompt = "custom-dialogue-prompt-marker\n只读台词")),
            messages = listOf(ChatMessage(roomId = "lobby", text = "续写的正文", segments = listOf(
                GenerationSegment(label = "B", text = "续写的正文", outputSource = ReplyOutputSource.REASONING))))) }
        assertEquals(original, json.decodeFromString<AppState>(json.encodeToString(original)))
        val packageData = exportGroupPackage(original, "lobby")
        assertTrue(packageData.characters.all { it.secondaryOutputSource == ReplyOutputSource.REASONING })
        val packageText = json.encodeToString(packageData)
        assertEquals(false, packageText.contains("custom-dialogue-prompt-marker"))
        val imported = importGroupPackage(AppState(), packageData).state
        assertTrue(imported.characters.takeLast(2).all { it.secondaryOutputSource == ReplyOutputSource.REASONING })
        assertEquals(DialogueExtractionSettings(), imported.tts.dialogueExtraction)
    }
}
