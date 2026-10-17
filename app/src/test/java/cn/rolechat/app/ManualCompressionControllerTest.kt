package cn.rolechat.app

import cn.rolechat.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualCompressionControllerTest {
    private val settings = CompressionSettings(keepRecentMessages = 2)
    private fun initial() = AppState().let { state -> state.copy(messages = (1..5).map { number ->
        ChatMessage(id = "m$number", roomId = "lobby", text = "旧聊天 $number", createdAt = number.toLong())
    }) }
    private fun generator(text: String = "两人约好明天见面。") = ContextSummaryGenerator(object : ChatGateway {
        override fun stream(request: GenerationRequest) = flow {
            emit(StreamEvent.Text(text)); emit(StreamEvent.Finished("stop"))
        }
    })

    @Test fun previewDoesNotChangeContextAndApplyUsesEditedDraftForEachRole() = runTest {
        val store = StateStore(initial())
        val original = store.snapshot().messages
        val controller = ManualCompressionController(store, this, { emptySet() }, { generator() })
        controller.generate("lobby", settings)
        runCurrent()
        val preview = controller.previews.value.getValue("lobby")
        assertEquals(CompressionStatus.READY, preview.status)
        assertEquals(2, preview.drafts.size)
        assertTrue(store.snapshot().contextSummaries.isEmpty())
        assertEquals(settings, store.snapshot().rooms.single().compression)
        val edited = preview.drafts.associate { it.characterId to "${it.characterName}的前情（手动修改）" }
        controller.apply("lobby", preview.id, edited)
        assertEquals(edited, store.snapshot().contextSummaries.associate { it.characterId to it.text })
        assertEquals(original, store.snapshot().messages)
        assertTrue(controller.previews.value.isEmpty())
    }

    @Test fun oneRoleFailureNeverAppliesPartialResult() = runTest {
        val store = StateStore(initial())
        var calls = 0
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest) = flow {
                calls++
                if (calls == 2) error("模拟服务失败")
                emit(StreamEvent.Text("第一位角色的前情")); emit(StreamEvent.Finished())
            }
        }
        val controller = ManualCompressionController(store, this, { emptySet() }, { ContextSummaryGenerator(gateway) })
        controller.generate("lobby", settings)
        runCurrent()
        val preview = controller.previews.value.getValue("lobby")
        assertEquals(CompressionStatus.ERROR, preview.status)
        assertEquals(1, preview.completed)
        controller.apply("lobby", preview.id, preview.drafts.associate { it.characterId to it.text })
        assertTrue(store.snapshot().contextSummaries.isEmpty())
    }

    @Test fun cancelStopsRequestAndLateResultCannotReappear() = runTest {
        val store = StateStore(initial())
        val gate = CompletableDeferred<Unit>()
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest) = flow {
                gate.await(); emit(StreamEvent.Text("已取消的前情")); emit(StreamEvent.Finished())
            }
        }
        val controller = ManualCompressionController(store, this, { emptySet() }, { ContextSummaryGenerator(gateway) })
        controller.generate("lobby", settings)
        runCurrent()
        controller.cancel("lobby")
        gate.complete(Unit)
        runCurrent()
        assertTrue(controller.previews.value.isEmpty())
        assertTrue(store.snapshot().contextSummaries.isEmpty())
    }

    @Test fun outdatedPreviewCannotConfirmNewGeneration() = runTest {
        val store = StateStore(initial())
        val controller = ManualCompressionController(store, this, { emptySet() }, { generator() })
        controller.generate("lobby", settings)
        runCurrent()
        val old = controller.previews.value.getValue("lobby")
        controller.generate("lobby", settings)
        runCurrent()
        controller.apply("lobby", old.id, old.drafts.associate { it.characterId to it.text })
        assertTrue(store.snapshot().contextSummaries.isEmpty())
        assertNotEquals(old.id, controller.previews.value.getValue("lobby").id)
    }

    @Test fun sourceEditedDuringPreviewRejectsEveryRoleAtomically() = runTest {
        val store = StateStore(initial())
        val controller = ManualCompressionController(store, this, { emptySet() }, { generator() })
        controller.generate("lobby", settings)
        runCurrent()
        val preview = controller.previews.value.getValue("lobby")
        store.updateMessage("m1") { it.copy(text = "已改写旧剧情") }
        controller.apply("lobby", preview.id, preview.drafts.associate { it.characterId to it.text })
        assertTrue(store.snapshot().contextSummaries.isEmpty())
        assertEquals(CompressionStatus.ERROR, controller.previews.value.getValue("lobby").status)
        assertEquals("已改写旧剧情", store.snapshot().messages.first().text)
    }

    @Test fun newMessagesOrChangedProviderInvalidatePendingConfirmation() = runTest {
        for (mutate in listOf<(AppState) -> AppState>(
            { it.copy(messages = it.messages + ChatMessage(roomId = "lobby", text = "新消息", createdAt = 6)) },
            { it.copy(providers = it.providers.map { provider -> provider.copy(baseUrl = "https://example.com/v1") }) }
        )) {
            val store = StateStore(initial())
            val controller = ManualCompressionController(store, this, { emptySet() }, { generator() })
            controller.generate("lobby", settings)
            runCurrent()
            val preview = controller.previews.value.getValue("lobby")
            store.update(mutate)
            controller.apply("lobby", preview.id, preview.drafts.associate { it.characterId to it.text })
            assertTrue(store.snapshot().contextSummaries.isEmpty())
            assertEquals(CompressionStatus.ERROR, controller.previews.value.getValue("lobby").status)
        }
    }

    @Test fun busyRoomAndMissingCredentialsNeverStartGeneration() = runTest {
        val store = StateStore(initial())
        var prepared = 0
        val busy = ManualCompressionController(store, this, { setOf("lobby") }, { prepared++; generator() })
        busy.generate("lobby", settings)
        assertEquals(0, prepared)
        val missing = ManualCompressionController(store, this, { emptySet() }, { error("缺少密钥") })
        missing.generate("lobby", settings)
        runCurrent()
        assertEquals(CompressionStatus.ERROR, missing.previews.value.getValue("lobby").status)
        assertEquals(CompressionSettings(), store.snapshot().rooms.single().compression)
        assertTrue(store.snapshot().contextSummaries.isEmpty())
    }

    @Test fun restoringFullContextRetainsEveryOriginalMessage() = runTest {
        val store = StateStore(initial())
        val original = store.snapshot().messages
        val controller = ManualCompressionController(store, this, { emptySet() }, { generator() })
        controller.generate("lobby", settings)
        runCurrent()
        val preview = controller.previews.value.getValue("lobby")
        controller.apply("lobby", preview.id, preview.drafts.associate { it.characterId to it.text })
        assertEquals(2, store.snapshot().contextSummaries.size)
        controller.clear("lobby")
        assertTrue(store.snapshot().contextSummaries.isEmpty())
        assertEquals(original, store.snapshot().messages)
    }
}
