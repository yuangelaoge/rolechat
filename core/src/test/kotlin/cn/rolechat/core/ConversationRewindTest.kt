package cn.rolechat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRewindTest {
    private val room = ChatRoom(id = "r", members = listOf(Member("a", 0, true)))
    private val actor = Character(id = "a")

    @Test fun storedOrderDefinesCutPointAndOtherRoomsSurviveWithEqualAndReversedTimestamps() {
        val before = ChatMessage(id = "before", roomId = "r", text = "保留", createdAt = 100)
        val target = ChatMessage(id = "target", roomId = "r", text = "重发这里", createdAt = 100)
        val foreign = ChatMessage(id = "foreign", roomId = "elsewhere", text = "别的群", createdAt = 101)
        val later = ChatMessage(id = "later", roomId = "r", text = "绝不留在上下文", createdAt = 1,
            segments = listOf(GenerationSegment(reasoning = "未来思考")),
            speechPreparation = SpeechPreparationRecord(rawOutput = "未来对白处理"))
        val state = AppState(rooms = listOf(room), messages = listOf(before, target, foreign, later))
        val rewound = rewindConversation(state, target.id)
        assertEquals(listOf(before, target, foreign), rewound.messages)
        val json = Json { encodeDefaults = true }
        val saved = json.encodeToString(rewound)
        assertFalse(saved.contains("绝不留在上下文"))
        assertFalse(saved.contains("未来思考"))
        assertFalse(saved.contains("未来对白处理"))
        assertEquals(rewound, json.decodeFromString<AppState>(saved))
    }

    @Test fun summariesAndDanglingReplayOrVoiceReferencesCannotCarryTheDiscardedFuture() {
        val target = ChatMessage(id = "target", roomId = "r", text = "这里", contextMessageIds = listOf("removed"),
            triggerMessageId = "removed", replyToMessageId = "removed", requestMessageId = "removed",
            speechPreparation = SpeechPreparationRecord(previousMessageId = "removed", previousInstruction = "未来情绪"))
        val later = ChatMessage(id = "removed", roomId = "r", text = "后来")
        val summary = ContextSummary(roomId = "r", characterId = "a", text = "带有未来影响的前情", sourceMessageIds = listOf("target"), sourceFingerprint = "test")
        val otherSummary = summary.copy(id = "other", roomId = "other", sourceMessageIds = listOf("unrelated"))
        val rewound = rewindConversation(AppState(rooms = listOf(room), messages = listOf(target, later),
            contextSummaries = listOf(summary, otherSummary)), target.id)
        assertEquals(listOf(otherSummary), rewound.contextSummaries)
        with(rewound.messages.single()) {
            assertTrue(contextMessageIds.isEmpty())
            assertNull(triggerMessageId); assertNull(replyToMessageId); assertNull(requestMessageId)
            assertNull(speechPreparation)
        }
    }

    @Test fun removedDirectorChangesAreRolledBackBeforeGeneratingAndRoomSettingsRemain() = runTest {
        val requests = mutableListOf<GenerationRequest>()
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest) = flow {
                requests += request; emit(StreamEvent.Text("重走剧情")); emit(StreamEvent.Finished())
            }
        }
        val target = ChatMessage(id = "target", roomId = "r", text = "起点")
        val originalRoom = room.copy(directorInstruction = "导入模板的日常基调", userBackground = "我的身份")
        val store = StateStore(AppState(rooms = listOf(originalRoom), characters = listOf(actor), messages = listOf(target)))
        val engine = ChatEngine(store, gateway, this)
        engine.setDirectorInstruction("r", "删除的未来命令", false)
        engine.setDirectorInstruction("r", "", false)
        assertTrue(engine.editAndSendMessage(target.id, "重新开始"))
        advanceUntilIdle()
        assertEquals(originalRoom, store.snapshot().rooms.single())
        assertTrue(requests.single().messages.first().content.contains("导入模板的日常基调"))
        assertFalse(requests.single().messages.any { "删除的未来命令" in it.content })
        assertFalse(store.snapshot().messages.any { it.isDirector })
    }

    @Test fun legacyDirectorRecordsRestoreTheLastRetainedValueOrEmptyWhenThereIsNone() {
        val original = ChatMessage(id = "old-director", roomId = "r", text = "保留的日常约束", isDirector = true)
        val target = ChatMessage(id = "target", roomId = "r", text = "重新开始")
        val future = ChatMessage(id = "future-director", roomId = "r", text = "已清空剧情指令", isDirector = true)
        val state = AppState(rooms = listOf(room), messages = listOf(original, target, future))
        assertEquals("保留的日常约束", rewindConversation(state, target.id).rooms.single().directorInstruction)
        assertEquals("", rewindConversation(state.copy(messages = listOf(target, future)), target.id).rooms.single().directorInstruction)
    }
}
