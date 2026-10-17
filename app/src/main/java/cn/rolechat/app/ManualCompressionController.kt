package cn.rolechat.app

import cn.rolechat.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A preview is never active history until the user applies its exact revision. */
class ManualCompressionController(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val busyRooms: () -> Set<String>,
    private val prepareGenerator: (Provider) -> ContextSummaryGenerator,
    private val onChanged: () -> Unit = {},
    private val onNotice: (String) -> Unit = {},
    private val errorText: (Throwable) -> String = { it.message ?: "前情整理失败，请重试" }
) {
    private data class Session(val id: String, val provider: Provider, val plans: List<CompressionPlan>, var job: Job? = null)
    private val sessions = mutableMapOf<String, Session>()
    private val mutablePreviews = MutableStateFlow<Map<String, CompressionUiState>>(emptyMap())
    val previews = mutablePreviews.asStateFlow()

    fun generate(roomId: String, requested: CompressionSettings) {
        if (roomId in busyRooms()) { onNotice("请等待当前回复结束后再整理前情"); return }
        if (mutablePreviews.value[roomId]?.status == CompressionStatus.GENERATING) return
        val settings = requested.copy(model = requested.model.copy(model = requested.model.model.trim(), thinking = ThinkingMode.OFF))
        cancel(roomId)
        try {
            val state = store.snapshot()
            validateCompressionSettings(settings, state.providers)
            val plans = buildCompressionPlans(state, roomId, settings)
            require(plans.isNotEmpty()) { "当前消息还不够多，没有需要压缩的旧聊天；可调整保留条数" }
            val provider = state.providers.single { it.id == settings.model.providerId }
            // Capture credentials now; replacing a service cannot redirect an in-flight request.
            val generator = prepareGenerator(provider)
            val preview = CompressionUiState(roomId = roomId, settings = settings,
                status = CompressionStatus.GENERATING, total = plans.size)
            val session = Session(preview.id, provider, plans)
            sessions[roomId] = session
            mutablePreviews.update { it + (roomId to preview) }
            store.update { current -> current.copy(rooms = current.rooms.map { if (it.id == roomId) it.copy(compression = settings) else it }) }
            onChanged()
            session.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val drafts = mutableListOf<CompressionDraft>()
                    for (plan in plans) {
                        val text = generator.generate(plan, settings, provider)
                        ensureActive()
                        if (sessions[roomId]?.id != preview.id) return@launch
                        drafts += CompressionDraft(plan.characterId, plan.characterName,
                            plan.sourceMessages.size, plan.recentMessages.size, text)
                        mutablePreviews.update { current -> current + (roomId to preview.copy(
                            drafts = drafts.toList(), completed = drafts.size,
                            status = if (drafts.size == plans.size) CompressionStatus.READY else CompressionStatus.GENERATING)) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (sessions[roomId]?.id == preview.id) mutablePreviews.update { current ->
                        current + (roomId to (current[roomId] ?: preview).copy(status = CompressionStatus.ERROR, error = errorText(error)))
                    }
                }
            }
            session.job!!.start()
        } catch (error: Exception) {
            mutablePreviews.update { it + (roomId to CompressionUiState(roomId = roomId, settings = settings,
                status = CompressionStatus.ERROR, error = errorText(error))) }
        }
    }

    fun apply(roomId: String, previewId: String, texts: Map<String, String>) {
        val session = sessions[roomId]
        val preview = mutablePreviews.value[roomId]
        if (session?.id != previewId || preview?.id != previewId || preview.status != CompressionStatus.READY) {
            onNotice("这份前情预览已失效，请重新生成"); return
        }
        if (roomId in busyRooms()) { onNotice("请等待当前回复结束后再确认前情"); return }
        try {
            store.update { current ->
                require(current.providers.singleOrNull { it.id == session.provider.id } == session.provider) { "模型服务已更改，请重新整理前情" }
                applyCompressionDrafts(current, preview.settings, session.plans, texts)
            }
            cancel(roomId)
            onChanged()
            onNotice("前情已确认；后续回复将使用前情和最近原文，原聊天记录仍保留")
        } catch (error: Exception) {
            mutablePreviews.update { current -> current + (roomId to preview.copy(status = CompressionStatus.ERROR, error = errorText(error))) }
        }
    }

    fun cancel(roomId: String) {
        sessions.remove(roomId)?.job?.cancel()
        mutablePreviews.update { it - roomId }
    }

    fun cancelAll() {
        sessions.values.forEach { it.job?.cancel() }
        sessions.clear()
        mutablePreviews.value = emptyMap()
    }

    fun clear(roomId: String) {
        if (roomId in busyRooms()) { onNotice("请等待当前回复结束后再恢复完整上下文"); return }
        cancel(roomId)
        store.update { current -> current.copy(contextSummaries = current.contextSummaries.filterNot { it.roomId == roomId }) }
        onChanged()
        onNotice("已恢复完整上下文，原聊天记录保持不变")
    }
}
