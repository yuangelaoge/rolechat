package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.Character
import cn.rolechat.core.ChatEngine
import cn.rolechat.core.ChatGateway
import cn.rolechat.core.ChatMessage
import cn.rolechat.core.ChatRoom
import cn.rolechat.core.DialogueExtractionSettings
import cn.rolechat.core.GenerationRequest
import cn.rolechat.core.GenerationSegment
import cn.rolechat.core.Member
import cn.rolechat.core.MessageStatus
import cn.rolechat.core.ModelConfig
import cn.rolechat.core.PromptMessage
import cn.rolechat.core.Provider
import cn.rolechat.core.ReplyOutputSource
import cn.rolechat.core.SpeechPreparationStatus
import cn.rolechat.core.SpeechVoiceSnapshot
import cn.rolechat.core.StateStore
import cn.rolechat.core.StreamEvent
import cn.rolechat.core.TtsSettings
import cn.rolechat.core.TtsEngine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real speech orchestration, with only model transport replaced. No remote service or AudioTrack is used. */
class SpeechPreparationPipelineTest {
    private val extractionProvider = Provider(id = "extract", baseUrl = "https://never-used.invalid/v1")
    private val extraction = DialogueExtractionSettings(enabled = true,
        model = ModelConfig(providerId = extractionProvider.id, model = "small-model"),
        systemPrompt = "只从当前完整回复提取对白，返回 text 和 instruction。")
    private val actor = Character(id = "actor", name = "测试角色", primary = ModelConfig(model = "model-a"),
        secondary = ModelConfig(model = "model-b"), dualModel = true, cutMin = 2, cutMax = 2,
        secondaryOutputSource = ReplyOutputSource.REASONING)
    private val original = ChatMessage(id = "reply", roomId = "room", characterId = actor.id, senderName = actor.name,
        text = "你好，慢慢来。", segments = listOf(GenerationSegment(label = "A", text = "你好"),
            GenerationSegment(label = "B", text = "，慢慢来。", outputSource = ReplyOutputSource.REASONING)))

    private fun state() = AppState(characters = listOf(actor),
        rooms = listOf(ChatRoom(id = "room", members = listOf(Member(actor.id, 0, true)), autoSpeak = true)),
        providers = listOf(Provider(id = "siliconflow"), extractionProvider), messages = listOf(original),
        tts = TtsSettings(enabled = true, endpoint = "wss://127.0.0.1:1/tts", dialogueExtraction = extraction))

    private fun credential(gateway: ChatGateway) = TtsCredentials(apiKey = "offline-test-key",
        dialogueExtraction = DialogueExtractionSnapshot(extractionProvider, gateway))

    private class RecordingGateway(private val respond: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit) : ChatGateway {
        val requests = ConcurrentLinkedQueue<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow {
            requests += request
            respond(request)
        }
    }

    private class Harness(initial: AppState, credentials: (AppState) -> TtsCredentials) {
        val store = StateStore(initial)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val statuses = Channel<String>(Channel.UNLIMITED)
        val sink = AndroidSpeechSink(store, scope, credentials, onStatus = { statuses.trySend(it) })
        fun close() { sink.close(); scope.cancel() }
    }

    @Test fun `A and compatible B share one extraction and a later TTS failure cannot erase successful preparation`() = runBlocking {
        val raw = "{\"text\":\"你好，慢慢来。\",\"instruction\":\"轻声安慰\"}"
        val gateway = RecordingGateway { request ->
            when (request.model.model) {
                "model-a" -> {
                    emit(StreamEvent.Reasoning("A 的私有思考"))
                    emit(StreamEvent.Text("你好不应保留的 A 后文"))
                }
                "model-b" -> {
                    emit(StreamEvent.Text("B 的普通字段不能混入正文"))
                    emit(StreamEvent.Reasoning("，慢慢来。"))
                }
                "small-model" -> {
                    emit(StreamEvent.Reasoning("小模型提取的独立思考"))
                    emit(StreamEvent.Text(raw))
                }
                else -> error("Unexpected model")
            }
            emit(StreamEvent.Finished())
        }
        val harness = Harness(state().copy(messages = emptyList())) { credential(gateway) }
        try {
            ChatEngine(harness.store, gateway, harness.scope, harness.sink).send("room", "请安慰一下我")
            val completed = withTimeout(5_000) {
                harness.store.state.first { value -> value.messages.lastOrNull()?.let {
                    it.characterId == actor.id && it.status == MessageStatus.COMPLETE &&
                        it.speechPreparation?.status == SpeechPreparationStatus.COMPLETE
                } == true }
            }.messages.last()
            assertEquals("你好，慢慢来。", completed.text)
            assertEquals(listOf("A", "B"), completed.segments.map { it.label })
            assertEquals("A 的私有思考", completed.segments.first().reasoning)
            assertEquals("", completed.segments.last().reasoning)
            val prepared = requireNotNull(completed.speechPreparation)
            assertEquals("small-model", prepared.model)
            assertEquals(true, prepared.modelInvoked)
            assertEquals(SpeechVoiceSnapshot(TtsEngine.DOUBAO, "seed-tts-1.0", actor.voice), prepared.voice)
            assertEquals(completed.text, prepared.text)
            assertEquals("轻声安慰", prepared.instruction)
            assertEquals(raw, prepared.rawOutput)
            assertEquals("小模型提取的独立思考", prepared.reasoning)
            val extractionRequest = gateway.requests.single { it.model.model == "small-model" }
            assertEquals(listOf(PromptMessage("system", extraction.systemPrompt), PromptMessage("user", completed.text)), extractionRequest.messages)
            assertEquals("", extractionRequest.prefix)
            assertEquals(3, gateway.requests.size)

            // The loopback-only endpoint refuses the audio connection before AudioTrack starts.
            withTimeout(5_000) {
                while (true) {
                    val status = harness.statuses.receive()
                    if (status.contains("语音网络连接失败") || status.contains("语音连接失败")) break
                }
            }
            // The extraction remains visible; an unheard, failed audio attempt
            // must not become the voice-style reference for a later message.
            assertEquals(prepared.copy(continuityEligible = false), harness.store.snapshot().messages.last().speechPreparation)
            assertEquals(completed.text, harness.store.snapshot().messages.last().text)
        } finally { harness.close() }
    }

    @Test fun `manual replay creates a visible record and requests exactly the saved complete reply`() = runBlocking {
        val raw = "{\"text\":\"\",\"instruction\":\"\"}"
        val gateway = RecordingGateway {
            emit(StreamEvent.Reasoning("这次无需朗读"))
            emit(StreamEvent.Text(raw))
            emit(StreamEvent.Finished())
        }
        val harness = Harness(state()) { credential(gateway) }
        try {
            // Same stop -> begin -> append -> finish sequence as AppViewModel.speak.
            harness.sink.stop(setOf(original.id))
            harness.sink.begin(original.id, actor)
            assertEquals(SpeechPreparationStatus.EXTRACTING, harness.store.snapshot().messages.single().speechPreparation?.status)
            harness.sink.append(original.id, original.text)
            harness.sink.finish(original.id)
            val message = withTimeout(5_000) {
                harness.store.state.first { it.messages.single().speechPreparation?.status == SpeechPreparationStatus.EMPTY }
            }.messages.single()
            assertEquals(original, message.copy(speechPreparation = null))
            assertEquals(raw, message.speechPreparation?.rawOutput)
            assertEquals("这次无需朗读", message.speechPreparation?.reasoning)
            assertEquals(true, message.speechPreparation?.modelInvoked)
            assertEquals(listOf(PromptMessage("system", extraction.systemPrompt), PromptMessage("user", original.text)),
                gateway.requests.single().messages)
        } finally { harness.close() }
    }

    @Test fun `every rejected speech preflight leaves an error record when extraction is enabled`() = runBlocking {
        val gateway = RecordingGateway { error("Preflight failures must not request a model") }
        data class Case(val label: String, val initial: AppState, val credentials: TtsCredentials)
        val ready = state()
        val cases = listOf(
            Case("音色与资源不匹配", ready.copy(tts = ready.tts.copy(resourceId = "seed-tts-2.0")), credential(gateway)),
            Case("缺少语音密钥", ready, TtsCredentials(dialogueExtraction = DialogueExtractionSnapshot(extractionProvider, gateway))),
            Case("缺少提取密钥", ready, TtsCredentials(apiKey = "offline-test-key")),
            Case("全局语音已关闭", ready.copy(tts = ready.tts.copy(enabled = false)), credential(gateway))
        )
        cases.forEach { case ->
            val harness = Harness(case.initial) { case.credentials }
            try {
                harness.sink.begin(original.id, actor)
                harness.sink.append(original.id, original.text)
                harness.sink.finish(original.id)
                val message = harness.store.snapshot().messages.single()
                val prepared = message.speechPreparation
                assertNotNull(case.label, prepared)
                assertEquals(case.label, SpeechPreparationStatus.ERROR, prepared?.status)
                assertTrue(case.label, prepared?.error?.isNotBlank() == true)
                assertEquals(case.label, extraction.model.model, prepared?.model)
                assertEquals(case.label, false, prepared?.modelInvoked)
                assertEquals(case.label, speechVoiceSnapshot(case.initial.tts, actor), prepared?.voice)
                assertEquals(case.label, original, message.copy(speechPreparation = null))
                assertTrue(case.label, gateway.requests.isEmpty())
            } finally { harness.close() }
        }
    }

    @Test fun `an empty source finishes without falsely marking the extraction model as invoked`() = runBlocking {
        val gateway = RecordingGateway { error("No model request is needed for empty text") }
        val harness = Harness(state()) { credential(gateway) }
        try {
            harness.sink.begin(original.id, actor)
            harness.sink.finish(original.id)
            val message = withTimeout(5_000) {
                harness.store.state.first { it.messages.single().speechPreparation?.status == SpeechPreparationStatus.EMPTY }
            }.messages.single()
            assertEquals(false, message.speechPreparation?.modelInvoked)
            assertEquals(speechVoiceSnapshot(state().tts, actor), message.speechPreparation?.voice)
            assertTrue(gateway.requests.isEmpty())
        } finally { harness.close() }
    }

    @Test fun `saved voice replaces a frozen actor and the new attempt records one immutable normalized configuration`() = runBlocking {
        val gateway = RecordingGateway {
            emit(StreamEvent.Text("{\"text\":\"\",\"instruction\":\"\"}"))
            emit(StreamEvent.Finished())
        }
        val ready = state().copy(tts = state().tts.copy(resourceId = "  seed-tts-2.0 \n"))
        val harness = Harness(ready) { snapshot ->
            assertEquals("seed-tts-2.0", snapshot.tts.resourceId)
            credential(gateway)
        }
        try {
            harness.sink.begin(original.id, actor)
            val failed = requireNotNull(harness.store.snapshot().messages.single().speechPreparation)
            assertEquals(SpeechPreparationStatus.ERROR, failed.status)
            assertEquals(false, failed.modelInvoked)
            assertEquals(actor.voice, failed.voice?.voiceId)

            val saved = actor.copy(voice = "  zh_female_vv_uranus_bigtts\n")
            harness.store.update { it.copy(characters = listOf(saved)) }
            harness.sink.begin(original.id, actor) // The engine still owns the old actor.
            val queued = requireNotNull(harness.store.snapshot().messages.single().speechPreparation)
            assertNotEquals(failed.id, queued.id)
            assertEquals(SpeechPreparationStatus.EXTRACTING, queued.status)
            assertEquals(false, queued.modelInvoked)
            assertEquals(SpeechVoiceSnapshot(TtsEngine.DOUBAO, "seed-tts-2.0", "zh_female_vv_uranus_bigtts"), queued.voice)
            // Later edits must not rewrite the configuration attached to this queued attempt.
            harness.store.update { it.copy(characters = listOf(actor), tts = it.tts.copy(resourceId = "seed-tts-1.0")) }
            harness.sink.append(original.id, original.text)
            harness.sink.finish(original.id)
            val complete = withTimeout(5_000) {
                harness.store.state.first { it.messages.single().speechPreparation?.status == SpeechPreparationStatus.EMPTY }
            }.messages.single()
            assertEquals(queued.voice, complete.speechPreparation?.voice)
            assertEquals(true, complete.speechPreparation?.modelInvoked)
            assertEquals(1, gateway.requests.size)
        } finally { harness.close() }
    }

    @Test fun `deleted actors are rejected while preview still uses an unsaved matching draft`() = runBlocking {
        val gateway = RecordingGateway { error("Neither deleted actor nor audition may request extraction") }
        val ready = state().copy(characters = emptyList(), tts = state().tts.copy(resourceId = "seed-tts-2.0"))
        val captured = CompletableDeferred<AppState>()
        val harness = Harness(ready) { snapshot ->
            captured.complete(snapshot)
            // Successful voice validation reaches here without making an audio call.
            TtsCredentials()
        }
        try {
            harness.sink.begin(original.id, actor)
            val failed = requireNotNull(harness.store.snapshot().messages.single().speechPreparation)
            assertEquals(SpeechPreparationStatus.ERROR, failed.status)
            assertEquals(false, failed.modelInvoked)
            assertNull(failed.voice)
            assertTrue(failed.error.contains("角色已删除"))
            assertFalse(captured.isCompleted)

            val draft = actor.copy(voice = "zh_female_vv_uranus_bigtts")
            harness.sink.preview(draft)
            val previewState = withTimeout(5_000) { captured.await() }
            assertFalse(previewState.tts.dialogueExtraction.enabled)
            assertTrue(previewState.tts.enabled)
            assertTrue(previewState.characters.isEmpty())
            assertEquals(failed, harness.store.snapshot().messages.single().speechPreparation)
            assertTrue(harness.store.snapshot().characters.isEmpty())
            assertTrue(gateway.requests.isEmpty())
        } finally { harness.close() }
    }

    @Test fun `stopping and replaying replaces the attempt while delayed old cleanup cannot cancel the new record`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val oldCleanupFinished = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val gateway = RecordingGateway {
            if (calls.incrementAndGet() == 1) {
                try {
                    emit(StreamEvent.Reasoning("旧提取思考"))
                    emit(StreamEvent.Text("{\"text\":\"旧的未完成结果"))
                    oldStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        releaseCleanup.await()
                        oldCleanupFinished.complete(Unit)
                    }
                }
            } else {
                emit(StreamEvent.Reasoning("本次提取思考"))
                emit(StreamEvent.Text("{\"text\":\"\",\"instruction\":\"\"}"))
                emit(StreamEvent.Finished())
            }
        }
        val harness = Harness(state()) { credential(gateway) }
        try {
            harness.sink.begin(original.id, actor)
            harness.sink.append(original.id, original.text)
            harness.sink.finish(original.id)
            withTimeout(5_000) { oldStarted.await() }
            val oldId = harness.store.snapshot().messages.single().speechPreparation?.id
            harness.sink.stop(setOf(original.id))
            val cancelled = requireNotNull(harness.store.snapshot().messages.single().speechPreparation)
            assertEquals(SpeechPreparationStatus.CANCELLED, cancelled.status)
            assertEquals("旧提取思考", cancelled.reasoning)

            harness.sink.begin(original.id, actor)
            val fresh = requireNotNull(harness.store.snapshot().messages.single().speechPreparation)
            assertNotEquals(oldId, fresh.id)
            assertEquals(SpeechPreparationStatus.EXTRACTING, fresh.status)
            harness.sink.append(original.id, original.text)
            harness.sink.finish(original.id)
            releaseCleanup.complete(Unit)
            withTimeout(5_000) { oldCleanupFinished.await() }
            val finished = withTimeout(5_000) {
                harness.store.state.first { it.messages.single().speechPreparation?.status == SpeechPreparationStatus.EMPTY }
            }.messages.single()
            assertEquals(fresh.id, finished.speechPreparation?.id)
            assertEquals("本次提取思考", finished.speechPreparation?.reasoning)
            assertEquals(original, finished.copy(speechPreparation = null))
            assertEquals(2, gateway.requests.size)
        } finally { releaseCleanup.complete(Unit); harness.close() }
    }
}
