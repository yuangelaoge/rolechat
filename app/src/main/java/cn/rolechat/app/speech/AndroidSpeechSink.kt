package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.Character
import cn.rolechat.core.Provider
import cn.rolechat.core.SpeechSink
import cn.rolechat.core.StateStore
import cn.rolechat.core.TtsSettings
import cn.rolechat.core.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class TtsCredentials(
    val apiKey: String = "", val accessToken: String = "",
    val siliconProvider: Provider? = null, val siliconApiKey: String = "",
    val dialogueExtraction: DialogueExtractionSnapshot? = null
)

/**
 * One utterance is one role message, including both A and B model segments.
 * begin() reserves its queue position; append() accepts only committed visible text.
 * Parallel role generation buffers text here while speech plays in begin() order.
 * Network/playback failures are isolated from the chat generation job.
 */
class AndroidSpeechSink(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val credentials: (AppState) -> TtsCredentials,
    private val onStatus: (String) -> Unit,
    private val sampleRate: Int = 24_000,
    private val voiceCatalog: () -> List<DoubaoVoice> = { DoubaoVoices.builtIn },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build(),
    private val siliconHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build(),
    private val pcmPlayerFactory: () -> SpeechPcmPlayer = { AndroidSpeechPcmPlayer() }
) : SpeechSink {
    private class Utterance(
        val messageId: String, val character: Character, val settings: TtsSettings,
        val credentials: TtsCredentials
    ) {
        val text = Channel<String>(Channel.UNLIMITED)
        val bufferedTextUnits = AtomicInteger(0)
        @Volatile var cancelled = false
        @Volatile var socket: WebSocket? = null
        @Volatile var stage = Stage.QUEUED
        @Volatile var startedPlayback = false
        @Volatile var played = false
        @Volatile var audio: PreparedAudio? = null
        var preparation: SpeechPreparationTracker? = null
        var player: SpeechPcmPlayer? = null // Guarded by this utterance's monitor.
    }

    private enum class Stage { QUEUED, PREPARING, READY, SYNTHESIZING, BUFFERED, PLAYING }
    private data class PreparedSpeech(val firstText: String, val text: Channel<String>,
        val onConsumed: (Int) -> Unit, val instruction: String = "")
    private class PreparedAudio(val sampleRate: Int, capacity: Int) {
        val chunks = Channel<ByteArray>(capacity)
        val bufferedBytes = AtomicInteger(0)
    }

    private val lock = Any()
    private val utterances = LinkedHashMap<String, Utterance>()
    private var closed = false
    private val siliconSpeech = SiliconFlowSpeechClient(siliconHttpClient)
    private val dialogueExtractor = DialogueExtractor()

    init {
        require(sampleRate in setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000))
    }

    private val pipeline = OrderedSpeechPipeline<Utterance, PreparedSpeech, PreparedAudio>(
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        prepare = ::prepare,
        synthesize = ::synthesize,
        play = ::play,
        disposeAudio = { it.chunks.cancel() },
        onFailure = ::failed,
        onFinished = ::finished,
        maxPrefetch = 3,
        maxQueued = 32
    )

    override fun begin(messageId: String, character: Character) {
        // The generation round may hold an older actor snapshot. Resolve saved
        // speech settings and voice together when this utterance actually begins.
        val state = store.snapshot()
        begin(messageId, state.characters.find { it.id == character.id }, state)
    }

    /** Audition the unsaved role voice without adding a chat message or running extraction. */
    fun preview(character: Character) {
        stopAll()
        val preview = prepareSpeechPreview(store.snapshot(), character)
        begin(preview.messageId, preview.character, preview.snapshot)
        append(preview.messageId, preview.text)
        finish(preview.messageId)
    }

    private fun begin(messageId: String, character: Character?, state: AppState) {
        synchronized(lock) {
            // Reserve one attempt before preflight. Configuration errors must be
            // visible on this message, not only in a transient audio status line.
            if (closed || utterances.containsKey(messageId)) return
            var settings = state.tts.copy(resourceId = state.tts.resourceId.trim(),
                siliconModel = state.tts.siliconModel.trim(), siliconVoice = state.tts.siliconVoice.trim())
            val configuredSpeaker = character?.copy(voice = character.voice.trim(),
                siliconModel = character.siliconModel.trim(), siliconVoice = character.siliconVoice.trim())
            val preparation = if (settings.dialogueExtraction.enabled)
                SpeechPreparationTracker(store, messageId, settings.dialogueExtraction.model.model,
                    configuredSpeaker?.let { speechVoiceSnapshot(settings, it) }) else null
            fun reject(message: String) {
                preparation?.fail(message)
                report(message)
            }
            var speaker = configuredSpeaker ?: run {
                reject("角色已删除，本次未调用语音前置模型；无法使用旧角色配置朗读")
                return
            }
            if (!settings.enabled) {
                reject("语音服务尚未启用，本次未调用语音前置模型；请在语音设置中启用语音")
                return
            }
            if (settings.engine == TtsEngine.DOUBAO) {
                val selected = try { resolveDoubaoVoice(settings.resourceId, speaker.voice, voiceCatalog()) }
                catch (error: IllegalArgumentException) {
                    reject(error.message ?: "请检查语音资源与音色")
                    return
                }
                settings = settings.copy(resourceId = selected.resourceId)
                speaker = speaker.copy(voice = selected.voiceId)
            }
            val credential = try { credentials(state.copy(tts = settings)) }
            catch (cancelled: CancellationException) { preparation?.cancel(); throw cancelled }
            catch (_: Exception) {
                reject("读取语音或对白提取配置失败，本次未调用语音前置模型；请重新保存语音设置")
                return
            }
            val credentialsMissing = when (settings.engine) {
                TtsEngine.DOUBAO -> if (settings.appId.isNotBlank()) credential.accessToken.isBlank() else credential.apiKey.isBlank()
                TtsEngine.SILICONFLOW -> credential.siliconProvider == null || credential.siliconApiKey.isBlank()
            }
            if (credentialsMissing) {
                reject(if (settings.engine == TtsEngine.SILICONFLOW) "请先为硅基流动语音选择模型服务并保存 API 密钥" else "请先在设置中填写豆包语音凭据")
                return
            }
            if (settings.dialogueExtraction.enabled && credential.dialogueExtraction == null) {
                reject("请先为对白提取选择模型服务并保存 API 密钥；本次未调用语音前置模型")
                return
            }
            // Settings, provider and matching key remain one immutable queued snapshot.
            val item = Utterance(messageId, speaker, settings, credential).apply { this.preparation = preparation }
            utterances[messageId] = item
            if (!pipeline.enqueue(messageId, item)) {
                utterances.remove(messageId)
                item.text.cancel()
                reject("朗读队列已满或已停止，请稍后重新尝试")
            }
        }
    }

    override fun append(messageId: String, text: String) {
        if (text.isEmpty()) return
        val item = synchronized(lock) { utterances[messageId] } ?: return
        if (item.cancelled) return
        if (item.bufferedTextUnits.addAndGet(text.length) > MAX_TEXT_BUFFER_UNITS) {
            synchronized(lock) { if (utterances[messageId] === item) utterances.remove(messageId) }
            cancel(item)
            pipeline.cancel(setOf(messageId))
            updateStatus("语音文字缓冲已满，已停止本条朗读；文字聊天可继续")
        } else if (item.text.trySend(text).isFailure) {
            item.bufferedTextUnits.addAndGet(-text.length)
        }
    }

    override fun finish(messageId: String) {
        synchronized(lock) { utterances[messageId] }?.text?.close()
    }

    override fun stop(roomMessageIds: Set<String>) {
        val items = synchronized(lock) {
            roomMessageIds.mapNotNull { utterances.remove(it) }.also { selected ->
                selected.forEach { it.cancelled = true }
            }
        }
        items.forEach(::cancel)
        pipeline.cancel(roomMessageIds)
        updateStatus()
    }

    fun stopAll() {
        val items = synchronized(lock) {
            utterances.values.toList().also { selected ->
                // Mark every reservation before releasing any playback slot.
                selected.forEach { it.cancelled = true }
                utterances.clear()
            }
        }
        items.forEach(::cancel)
        pipeline.cancelAll()
        updateStatus()
    }

    fun close() {
        synchronized(lock) { closed = true }
        stopAll()
        pipeline.close()
        listOf(client, siliconHttpClient).forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }

    private fun cancel(item: Utterance) {
        synchronized(item) {
            item.cancelled = true
            if (!item.startedPlayback) item.preparation?.discardContinuity()
            item.preparation?.cancel()
            // Stop the device buffer synchronously before coroutine cancellation.
            item.player?.stop()
            item.socket?.cancel()
            item.text.cancel()
            item.audio?.chunks?.cancel()
        }
    }

    private fun failed(item: Utterance, error: Throwable) {
        val message = when (error) {
            is TimeoutCancellationException -> "语音等待超时，文字聊天可继续"
            is DialogueExtractionFailure -> error.message ?: "对白提取失败，本条未朗读"
            is SpeechFailure -> error.message ?: "语音合成失败"
            is IOException -> {
                val secret = item.credentials.siliconApiKey
                val detail = error.message ?: "语音网络连接或播放失败"
                (if (secret.isBlank()) detail else detail.replace(secret, "[已隐藏]")).take(300)
            }
            else -> "语音连接或播放失败，请检查语音设置"
        }
        if (item.cancelled) return
        item.preparation?.fail(message)
        cancel(item)
        updateStatus(message)
    }

    private fun finished(item: Utterance) {
        item.text.cancel()
        item.audio?.chunks?.cancel()
        item.socket?.cancel()
        synchronized(lock) {
            if (utterances[item.messageId] === item) utterances.remove(item.messageId)
        }
        if (!item.cancelled && item.played) updateStatus("${item.character.name} · 朗读完成")
    }

    private suspend fun prepare(item: Utterance): PreparedSpeech? {
        if (item.cancelled) throw CancellationException()
        val consumed: (Int) -> Unit = { item.bufferedTextUnits.addAndGet(-it) }
        if (item.settings.dialogueExtraction.enabled) {
            val snapshot = item.credentials.dialogueExtraction
                ?: throw DialogueExtractionFailure("请先配置对白提取模型服务")
            val previous = previousSpeechInstruction(store.snapshot(), item.messageId,
                speechVoiceSnapshot(item.settings, item.character))
            item.preparation?.previousInstruction(previous?.instruction.orEmpty(), previous?.messageId)
            val dialogue = dialogueExtractor.extract(
                input = item.text,
                settings = item.settings.dialogueExtraction,
                snapshot = snapshot,
                onConsumed = consumed,
                onExtractionStarted = {
                    item.preparation?.modelStarted()
                    item.stage = Stage.PREPARING
                    updateStatus()
                },
                onOutput = { raw, reasoning -> item.preparation?.output(raw, reasoning) },
                previousInstruction = previous?.instruction.orEmpty()
            )
            currentCoroutineContext().ensureActive()
            if (item.cancelled) throw CancellationException()
            item.preparation?.complete(dialogue)
            item.stage = Stage.READY
            if (dialogue.text.isBlank()) {
                updateStatus("${item.character.name} · 未提取到对白，已跳过朗读")
                return null
            }
            // One A+B message remains one extraction. No original-text fallback.
            val prepared = Channel<String>(1).apply { close() }
            return PreparedSpeech(dialogue.text, prepared, {}, dialogue.instruction)
        }
        val first = withTimeout(180_000) { receiveText(item.text, consumed) } ?: return null
        item.stage = Stage.READY
        return PreparedSpeech(first, item.text, consumed)
    }

    private suspend fun synthesize(item: Utterance, prepared: PreparedSpeech,
        publish: suspend (PreparedAudio) -> Unit) {
        currentCoroutineContext().ensureActive()
        if (item.cancelled) throw CancellationException()
        val silicon = item.settings.engine == TtsEngine.SILICONFLOW
        val audio = PreparedAudio(if (silicon) 24_000 else sampleRate,
            if (silicon) 8 else Channel.UNLIMITED)
        item.audio = audio
        item.stage = Stage.SYNTHESIZING
        updateStatus()
        try {
            // Publishing the bounded stream allows playback while synthesis continues.
            publish(audio)
            when (item.settings.engine) {
                TtsEngine.DOUBAO -> synthesizeDoubao(item, prepared.firstText, prepared.text, prepared.onConsumed, prepared.instruction, audio)
                TtsEngine.SILICONFLOW -> synthesizeSiliconFlow(item, prepared.firstText, prepared.text, prepared.onConsumed, audio)
            }
            audio.chunks.close()
            if (item.stage != Stage.PLAYING) item.stage = Stage.BUFFERED
        } catch (error: Throwable) {
            audio.chunks.close(error)
            throw error
        } finally {
            if (prepared.text !== item.text) prepared.text.cancel()
        }
    }

    private suspend fun play(item: Utterance, audio: PreparedAudio) {
        val player = pcmPlayerFactory()
        synchronized(item) {
            if (item.cancelled) { player.stop(); throw CancellationException() }
            item.player = player
        }
        try {
            player.play(audio.chunks, audio.sampleRate,
                onConsumed = { audio.bufferedBytes.addAndGet(-it) },
                onStarted = {
                    if (!item.cancelled) {
                        item.startedPlayback = true
                        item.stage = Stage.PLAYING
                        updateStatus()
                    }
                })
            currentCoroutineContext().ensureActive()
            if (!item.cancelled) item.played = true
        } finally {
            synchronized(item) {
                if (item.player === player) item.player = null
                player.stop()
            }
        }
    }

    private fun offerAudio(item: Utterance, audio: PreparedAudio, bytes: ByteArray) {
        if (item.cancelled) throw CancellationException()
        if (bytes.isEmpty()) return
        if (audio.bufferedBytes.addAndGet(bytes.size) > MAX_AUDIO_BUFFER) {
            audio.bufferedBytes.addAndGet(-bytes.size)
            throw SpeechFailure("语音缓冲过大，已停止本条朗读")
        }
        if (audio.chunks.trySend(bytes).isFailure) {
            audio.bufferedBytes.addAndGet(-bytes.size)
            throw CancellationException("Speech audio stream closed")
        }
    }

    private suspend fun receiveText(text: Channel<String>, onConsumed: (Int) -> Unit): String? {
        val next = text.receiveCatching()
        next.exceptionOrNull()?.let { throw it }
        return next.getOrNull()?.also { onConsumed(it.length) }
    }

    private suspend fun synthesizeSiliconFlow(item: Utterance, firstText: String, text: Channel<String>,
        onConsumed: (Int) -> Unit, audio: PreparedAudio) {
        val credential = item.credentials
        val provider = credential.siliconProvider ?: throw SpeechFailure("未选择硅基流动语音服务")
        val model = item.character.siliconModel.ifBlank { item.settings.siliconModel }.trim()
        val voice = item.character.siliconVoice.ifBlank { item.settings.siliconVoice }.trim()
        if (model.isBlank() || voice.isBlank()) throw SpeechFailure("请填写硅基流动语音模型和音色")
        segmentedSpeechText(text, firstText = firstText, onConsumed = onConsumed).collect { segment ->
            currentCoroutineContext().ensureActive()
            if (item.cancelled) throw CancellationException()
            siliconSpeech.stream(provider, credential.siliconApiKey, model, segment, voice, item.settings.siliconSpeed)
                .buffer(0).collect { pcm ->
                    currentCoroutineContext().ensureActive()
                    var offset = 0
                    while (offset < pcm.size) {
                        val size = minOf(SILICON_AUDIO_CHUNK_BYTES, pcm.size - offset)
                        if (item.cancelled) throw CancellationException()
                        // HTTP reads can suspend. Backpressure keeps a long prefetched
                        // reply bounded until the previous reply releases playback.
                        audio.bufferedBytes.addAndGet(size)
                        try {
                            audio.chunks.send(pcm.copyOfRange(offset, offset + size))
                        } catch (error: Throwable) {
                            audio.bufferedBytes.addAndGet(-size)
                            throw error
                        }
                        offset += size
                    }
                }
        }
    }

    private suspend fun synthesizeDoubao(item: Utterance, firstText: String, textInput: Channel<String>,
        onConsumed: (Int) -> Unit, instruction: String, audio: PreparedAudio) {
        val credential = item.credentials
        val sessionId = UUID.randomUUID().toString()
        val connected = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        fun fail(error: Exception) {
            connected.completeExceptionally(error)
            started.completeExceptionally(error)
            finished.completeExceptionally(error)
            audio.chunks.close(error)
            // Wake a sender currently waiting for the next model text chunk.
            textInput.close(error)
        }

        val request = Request.Builder().url(item.settings.endpoint)
            .header("X-Api-Resource-Id", item.settings.resourceId)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .apply {
                if (item.settings.appId.isBlank()) header("X-Api-Key", credential.apiKey.trim())
                else {
                    header("X-Api-App-Id", item.settings.appId.trim())
                    header("X-Api-Access-Key", credential.accessToken.trim())
                }
            }.build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (item.cancelled) { webSocket.cancel(); return }
                if (!webSocket.send(DoubaoProtocol.request(DoubaoProtocol.START_CONNECTION).toByteString())) {
                    fail(SpeechFailure("语音连接初始化失败"))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (item.cancelled) return
                try {
                    val frame = DoubaoProtocol.parse(bytes.toByteArray())
                    if (frame.errorCode != null) {
                        fail(SpeechFailure(doubaoServiceError(frame.errorCode, serviceMessage(frame.payload),
                            item.settings.resourceId, item.character.voice)))
                        return
                    }
                    if (frame.event != null && frame.event >= 100 && frame.id != sessionId) return
                    when (frame.event) {
                        DoubaoProtocol.CONNECTION_STARTED -> connected.complete(Unit)
                        DoubaoProtocol.SESSION_STARTED -> started.complete(Unit)
                        DoubaoProtocol.AUDIO -> {
                            if (frame.type != 0xb) throw SpeechFailure("语音服务返回了非 PCM 音频帧")
                            offerAudio(item, audio, frame.payload)
                        }
                        DoubaoProtocol.SESSION_FINISHED -> {
                            finished.complete(Unit)
                            audio.chunks.close()
                        }
                        DoubaoProtocol.SESSION_CANCELLED -> fail(SpeechFailure("语音会话已被服务端取消"))
                        DoubaoProtocol.CONNECTION_FAILED, DoubaoProtocol.SESSION_FAILED ->
                            fail(SpeechFailure("语音合成失败：${serviceMessage(frame.payload)}"))
                        DoubaoProtocol.CONNECTION_FINISHED -> if (!finished.isCompleted) {
                            fail(SpeechFailure("语音连接提前结束"))
                        }
                    }
                } catch (error: Exception) {
                    fail(if (error is SpeechFailure) error else SpeechFailure("语音协议解析失败"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!item.cancelled) fail(SpeechFailure("语音服务错误：${serviceMessage(text.toByteArray())}"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!item.cancelled) fail(SpeechFailure(
                    response?.let { "语音连接失败（HTTP ${it.code}），请检查凭据、资源与音色" }
                        ?: "语音网络连接失败，请检查网络与服务地址"
                ))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if (!item.cancelled && !finished.isCompleted) fail(SpeechFailure("语音连接提前关闭（$code）"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!item.cancelled && !finished.isCompleted) fail(SpeechFailure("语音连接提前关闭（$code）"))
            }
        })
        synchronized(item) {
            item.socket = socket
            if (item.cancelled) socket.cancel()
        }

        fun send(event: Int, payload: String = "{}") {
            if (item.cancelled) throw CancellationException()
            val id = if (event >= 100) sessionId else null
            if (!socket.send(DoubaoProtocol.request(event, id, payload).toByteString())) {
                throw SpeechFailure("语音发送失败，连接已关闭")
            }
        }

        var completedNormally = false
        fun requestBody(event: Int, text: String) = doubaoRequestBody(
            event, item.character.voice, text, sampleRate, item.settings, instruction
        )
        try {
            withTimeout(20_000) { connected.await() }
            send(DoubaoProtocol.START_SESSION, requestBody(DoubaoProtocol.START_SESSION, ""))
            withTimeout(20_000) { started.await() }
            send(DoubaoProtocol.TASK_REQUEST, requestBody(DoubaoProtocol.TASK_REQUEST, firstText))
            // No sentence batching: Doubao handles streaming text fragments in the same session.
            while (true) {
                val text = withTimeout(180_000) { receiveText(textInput, onConsumed) } ?: break
                send(DoubaoProtocol.TASK_REQUEST, requestBody(DoubaoProtocol.TASK_REQUEST, text))
            }
            send(DoubaoProtocol.FINISH_SESSION)
            withTimeout(90_000) { finished.await() }
            send(DoubaoProtocol.FINISH_CONNECTION)
            completedNormally = true
            socket.close(1000, "Finished")
        } finally {
            if (!completedNormally) socket.cancel()
            synchronized(item) { item.socket = null }
        }
    }

    private fun updateStatus(fallback: String = "") {
        val status = synchronized(lock) {
            val active = utterances.values.filterNot { it.cancelled }
            val playing = active.firstOrNull { it.stage == Stage.PLAYING }
            val synthesizing = active.firstOrNull { it.stage == Stage.SYNTHESIZING }
            val preparing = active.firstOrNull { it.stage == Stage.PREPARING }
            when {
                playing != null -> "${playing.character.name} · 正在朗读" +
                    if (active.any { it !== playing && it.stage != Stage.QUEUED }) " · 提前准备中" else ""
                synthesizing != null -> "${synthesizing.character.name} · 语音连接中"
                preparing != null -> "${preparing.character.name} · 正在提取对白"
                else -> fallback
            }
        }
        report(status)
    }

    private fun report(message: String) { scope.launch { onStatus(message) } }

    private fun serviceMessage(bytes: ByteArray): String = runCatching {
        val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        (obj?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
            ?: "请检查资源 ID、音色和账户额度").take(180)
    }.getOrDefault("请检查资源 ID、音色和账户额度")

    private class SpeechFailure(message: String) : Exception(message)

    companion object {
        private const val MAX_AUDIO_BUFFER = 16 * 1024 * 1024
        private const val MAX_TEXT_BUFFER_UNITS = 128 * 1024
        private const val SILICON_AUDIO_CHUNK_BYTES = 8192
    }
}
