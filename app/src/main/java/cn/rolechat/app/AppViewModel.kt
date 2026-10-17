package cn.rolechat.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.rolechat.app.data.*
import cn.rolechat.app.network.SiliconFlowGateway
import cn.rolechat.app.speech.AndroidSpeechSink
import cn.rolechat.app.speech.DialogueExtractionSnapshot
import cn.rolechat.app.speech.TtsCredentials
import cn.rolechat.app.speech.DoubaoVoices
import cn.rolechat.app.speech.DoubaoVoiceClient
import cn.rolechat.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.File

@OptIn(FlowPreview::class)
class AppViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val db = ChatDatabase.open(application)
    private val secrets = SecretStore(application)
    private val store = StateStore()
    val state = store.state
    private val mutableRuntime = MutableStateFlow(RuntimeUi())
    val runtime = mutableRuntime.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val persistMutex = Mutex()
    @Volatile private var persistenceReady = false
    private val gateway = SiliconFlowGateway(keyFor = { provider -> secrets.get(providerSecretId(provider)) })
    private val extractionClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val voiceHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val voiceClient = DoubaoVoiceClient(client = voiceHttpClient)
    private val voiceCache = VoiceCatalogCache(File(application.filesDir, "doubao-voice-catalog.json"))
    private var voiceRefreshJob: Job? = null
    private var voiceCredentialGeneration = 0
    private val speech = AndroidSpeechSink(store, viewModelScope,
        voiceCatalog = { runtime.value.doubaoVoices },
        credentials = { snapshot ->
            val settings = snapshot.tts
            val speechCredentials = if (settings.engine == TtsEngine.SILICONFLOW) {
                val provider = snapshot.providers.find { it.id == settings.siliconProviderId }
                TtsCredentials(siliconProvider = provider, siliconApiKey = provider?.let { secrets.get(providerSecretId(it)) }.orEmpty())
            } else TtsCredentials(secrets.get(ttsApiSecretId(settings)), secrets.get(ttsTokenSecretId(settings)))
            val extraction = if (settings.dialogueExtraction.enabled) {
                snapshot.providers.find { it.id == settings.dialogueExtraction.model.providerId }?.let { provider ->
                    val capturedKey = secrets.get(providerSecretId(provider))
                    if (capturedKey.isBlank()) null else DialogueExtractionSnapshot(provider,
                        SiliconFlowGateway(keyFor = { capturedKey }, client = extractionClient))
                }
            } else null
            speechCredentials.copy(dialogueExtraction = extraction)
        },
        onStatus = { status -> mutableRuntime.update { it.copy(audioStatus = status) } })
    private val engine = ChatEngine(store, gateway, viewModelScope, speech)
    private val compression = ManualCompressionController(store, viewModelScope,
        busyRooms = { engine.busyRooms.value },
        prepareGenerator = { provider ->
            val capturedKey = secrets.get(providerSecretId(provider))
            require(capturedKey.isNotBlank()) { "请先为「${provider.name}」填写 API Key" }
            ContextSummaryGenerator(SiliconFlowGateway(keyFor = { capturedKey }, client = extractionClient))
        },
        onChanged = { flush() }, onNotice = { notice(it) },
        errorText = { SiliconFlowGateway.sanitize(it.message ?: "前情整理失败，请重试") })
    var requestExport: () -> Unit = {}
    var requestImport: () -> Unit = {}
    var requestGroupExport: (String, String) -> Unit = { _, _ -> }
    var requestGroupImport: () -> Unit = {}

    init {
        viewModelScope.launch {
            try {
                val saved = db.snapshotDao().read()
                if (saved != null) {
                    val recovered = withContext(Dispatchers.Default) { validateImportedState(AppJson.decodeFromString<AppState>(saved.payload)) }
                    store.update { recovered }
                }
                persistenceReady = true
                refreshCredentialStatus()
                withContext(Dispatchers.IO) { voiceCache.read() }?.let { cached ->
                    mutableRuntime.update { if (it.doubaoVoiceListUpdatedAt > cached.updatedAt) it else
                        it.copy(doubaoVoices = mergedDoubaoVoices(cached.voices), doubaoVoiceListUpdatedAt = cached.updatedAt) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                notice("本地数据读取失败，原数据已保留。可导入有效备份恢复：${e.message.orEmpty()}")
            } finally {
                mutableReady.value = true
            }
            // A failed load remains write-protected, including onStop/flush. A successful
            // explicit backup restore re-enables persistence without restarting this collector.
            store.state.debounce(200).collect {
                try { persist() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { notice("保存失败，将在下一次更改时重试：${error.message.orEmpty()}") }
            }
        }
        viewModelScope.launch { engine.busyRooms.collect { busy -> mutableRuntime.update { it.copy(busyRooms = busy) } } }
        viewModelScope.launch { compression.previews.collect { previews -> mutableRuntime.update { it.copy(compressions = previews) } } }
    }

    private suspend fun persist() = persistMutex.withLock {
        if (!persistenceReady) return@withLock
        val serialized = withContext(Dispatchers.Default) { AppJson.encodeToString(store.snapshot()) }
        db.snapshotDao().write(SnapshotEntity(payload = serialized))
    }
    fun flush() {
        if (mutableReady.value && persistenceReady) viewModelScope.launch {
            try { persist() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice("保存失败：${error.message.orEmpty()}") }
        }
    }
    fun notice(text: String) { mutableRuntime.update { it.copy(notice = SiliconFlowGateway.sanitize(text)) } }
    private fun refreshCredentialStatus() {
        mutableRuntime.update { it.copy(
            voiceCatalogConfigured = voiceCatalogCredentials() != null,
            configuredProviders = store.snapshot().providers.filter { provider -> secrets.get(providerSecretId(provider)).isNotBlank() }.map { p -> p.id }.toSet(),
            ttsConfigured = store.snapshot().tts.let { tts ->
                if (tts.engine == TtsEngine.SILICONFLOW) store.snapshot().providers.find { p -> p.id == tts.siliconProviderId }
                    ?.let { p -> secrets.get(providerSecretId(p)).isNotBlank() } ?: false
                else if (tts.appId.isBlank()) secrets.get(ttsApiSecretId(tts)).isNotBlank() else secrets.get(ttsTokenSecretId(tts)).isNotBlank()
            }
        ) }
    }
    private fun voiceCatalogCredentials(): VoiceCatalogCredentials? = runCatching {
        AppJson.decodeFromString<VoiceCatalogCredentials>(secrets.get(VOICE_CATALOG_SECRET_ID))
            .takeIf { it.accessKey.isNotBlank() && it.secretKey.isNotBlank() }
    }.getOrNull()

    override fun refreshDoubaoVoices() {
        if (runtime.value.loadingDoubaoVoices) return
        val credentials = voiceCatalogCredentials()
        if (credentials == null) {
            mutableRuntime.update { it.copy(doubaoVoiceListError = "从官网刷新需在「设置 → 语音服务 → 音色列表访问密钥」保存 Access Key 和 Secret Key；现有列表仍可选择。") }
            return
        }
        val generation = voiceCredentialGeneration
        mutableRuntime.update { it.copy(loadingDoubaoVoices = true, doubaoVoiceListError = "") }
        voiceRefreshJob = viewModelScope.launch {
            try {
                val voices = voiceClient.listVoices(credentials.accessKey, credentials.secretKey)
                if (generation != voiceCredentialGeneration) return@launch
                val snapshot = VoiceCatalogSnapshot(voices, System.currentTimeMillis())
                val saved = withContext(Dispatchers.IO) { runCatching { voiceCache.write(snapshot) }.isSuccess }
                if (generation == voiceCredentialGeneration) mutableRuntime.update { it.copy(
                    doubaoVoices = mergedDoubaoVoices(voices), doubaoVoiceListUpdatedAt = snapshot.updatedAt,
                    doubaoVoiceListError = if (saved) "" else "音色已更新，但缓存未能保存；重启后可再次刷新。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == voiceCredentialGeneration) mutableRuntime.update { it.copy(
                    doubaoVoiceListError = SiliconFlowGateway.sanitize(error.message ?: "刷新失败，已保留现有音色列表", credentials.accessKey)
                        .replace(credentials.secretKey, "[已隐藏]")) }
            } finally {
                if (generation == voiceCredentialGeneration) mutableRuntime.update { it.copy(loadingDoubaoVoices = false) }
            }
        }
    }
    override fun saveCharacter(character: Character) {
        val normalized = character.copy(voice = character.voice.trim(), siliconModel = character.siliconModel.trim(), siliconVoice = character.siliconVoice.trim())
        val state = store.snapshot()
        state.rooms.filter { room -> room.members.any { it.characterId == character.id } }.forEach { compression.cancel(it.id) }
        val previous = state.characters.find { it.id == character.id }
        if (previous != null && (previous.voice != normalized.voice || previous.siliconVoice != normalized.siliconVoice || previous.siliconModel != normalized.siliconModel))
            speech.stop(state.messages.filter { it.characterId == character.id }.mapTo(mutableSetOf()) { it.id })
        store.update { s -> s.copy(characters = s.characters.upsert(normalized) { it.id }) }
        flush()
        notice("角色已保存")
    }
    override fun setCharacterVoice(characterId: String, voice: String): Boolean {
        val state = store.snapshot()
        val character = state.characters.find { it.id == characterId } ?: run { notice("角色已删除，音色未保存"); return false }
        val normalized = voice.trim()
        val error = DoubaoVoices.validationError(state.tts.resourceId, normalized, runtime.value.doubaoVoices)
        if (error != null) { notice(error); return false }
        // Save just the selected voice, preserving other unsaved editor fields.
        speech.stop(state.messages.filter { it.characterId == characterId }.mapTo(mutableSetOf()) { it.id })
        store.update { s -> s.copy(characters = s.characters.map { if (it.id == characterId) it.copy(voice = normalized) else it }) }
        flush()
        notice("已为「${character.name}」保存音色")
        return true
    }
    override fun deleteCharacter(id: String) {
        store.snapshot().rooms.filter { room -> room.members.any { it.characterId == id } }.forEach { compression.cancel(it.id); engine.stop(it.id) }
        store.update { s -> s.copy(characters = s.characters.filterNot { it.id == id }, rooms = s.rooms.map { room ->
            room.copy(members = room.members.filterNot { it.characterId == id })
        }, contextSummaries = s.contextSummaries.filterNot { it.characterId == id }) }; notice("角色已移除，历史消息已保留")
    }
    override fun saveWorld(world: World) { store.update { it.copy(worlds = it.worlds.upsert(world) { w -> w.id }) }; notice("世界观已保存") }
    override fun saveRoom(room: ChatRoom) {
        if (room.mode == ReplyMode.SMART) {
            try { validateRoutingSettings(room.routing, store.snapshot().providers) }
            catch (error: IllegalArgumentException) { notice(error.message ?: "接话设置无效"); return }
        }
        compression.cancel(room.id)
        engine.stop(room.id)
        store.update { current -> current.copy(rooms = current.rooms.upsert(room) { r -> r.id },
            contextSummaries = current.contextSummaries.filter { it.roomId != room.id || room.members.any { member -> member.characterId == it.characterId } }) }
        notice("会话设置已保存")
    }
    override fun deleteRoom(id: String) {
        compression.cancel(id)
        engine.deleteRoom(id)
        flush()
        notice("对话及全部聊天记录、上下文已删除")
    }
    override fun clearHistory(roomId: String) {
        compression.cancel(roomId)
        engine.clearHistory(roomId)
        flush()
        notice("聊天记录及上下文已清空")
    }
    override fun generateCompression(roomId: String, settings: CompressionSettings) {
        if (!persistenceReady) { notice("请等待本地数据加载完成，或先恢复有效备份"); return }
        compression.generate(roomId, settings)
    }
    override fun applyCompression(roomId: String, previewId: String, texts: Map<String, String>) = compression.apply(roomId, previewId, texts)
    override fun cancelCompression(roomId: String) = compression.cancel(roomId)
    override fun clearCompression(roomId: String) = compression.clear(roomId)
    override fun setRoomPinned(roomId: String, pinned: Boolean) {
        store.update { state -> state.copy(rooms = state.rooms.map { if (it.id == roomId) it.copy(pinned = pinned) else it }) }
        flush()
    }
    override fun exportGroup(roomId: String) {
        try {
            val configuration = exportGroupPackage(store.snapshot(), roomId)
            requestGroupExport(roomId, groupConfigurationFileName(configuration.room.name))
        } catch (error: Exception) { notice("无法导出群聊：${error.message.orEmpty()}") }
    }
    override fun importGroup() = requestGroupImport()
    suspend fun groupExportText(roomId: String): String {
        ready.first { it }
        check(persistenceReady) { "本地数据尚未成功加载，请先恢复数据" }
        return withContext(Dispatchers.Default) { encodeGroupConfiguration(exportGroupPackage(store.snapshot(), roomId)) }
    }
    fun restoreGroup(text: String) {
        viewModelScope.launch {
            try {
                val configuration = withContext(Dispatchers.Default) { decodeGroupConfiguration(text) }
                ready.first { it }
                check(persistenceReady) { "本地数据尚未成功加载，请先恢复数据" }
                var importedName = ""
                // Merge into the latest snapshot while holding StateStore's lock;
                // a reply arriving during file parsing must never be overwritten.
                store.update { current ->
                    val result = importGroupPackage(current, configuration)
                    importedName = result.state.rooms.first { it.id == result.roomId }.name
                    result.state
                }
                refreshCredentialStatus()
                persist()
                notice("群聊「$importedName」已导入；不包含聊天记录")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice("无法导入群聊：${error.message.orEmpty()}") }
        }
    }
    override fun saveProvider(provider: Provider, newApiKey: String?) {
        try {
            val origin = serviceOrigin(provider.baseUrl)
            compression.cancelAll()
            val previous = store.snapshot().providers.find { it.id == provider.id }
            val changedHost = previous != null && serviceOrigin(previous.baseUrl) != origin
            if (store.snapshot().tts.let {
                (it.engine == TtsEngine.SILICONFLOW && it.siliconProviderId == provider.id) ||
                    (it.dialogueExtraction.enabled && it.dialogueExtraction.model.providerId == provider.id)
            }) speech.stopAll()
            if (changedHost) secrets.remove(setOf(providerSecretId(provider)))
            if (!newApiKey.isNullOrBlank()) secrets.put(providerSecretId(provider), newApiKey)
            store.update { it.copy(providers = it.providers.upsert(provider.copy(baseUrl = provider.baseUrl.trim().trimEnd('/'))) { p -> p.id }) }
            refreshCredentialStatus()
            notice(if (changedHost && newApiKey.isNullOrBlank()) "模型服务已保存，服务地址已更换，请重新填写 API Key" else "模型服务已保存")
        } catch (e: Exception) { notice("保存失败：${e.message}") }
    }
    override fun refreshModels(providerId: String) {
        val provider = store.snapshot().providers.find { it.id == providerId } ?: return
        if (providerId in runtime.value.loadingModels) return
        viewModelScope.launch {
            mutableRuntime.update { it.copy(loadingModels = it.loadingModels + providerId) }
            try {
                val models = gateway.listModels(provider)
                mutableRuntime.update { it.copy(modelLists = it.modelLists + (providerId to models)) }
                notice("已获取 ${models.size} 个模型，仍可手动填写模型名称")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { notice(e.message ?: "获取模型失败") }
            finally { mutableRuntime.update { it.copy(loadingModels = it.loadingModels - providerId) } }
        }
    }
    override fun send(roomId: String, text: String, mentions: Set<String>) {
        if (text.isNotBlank()) { compression.cancel(roomId); engine.send(roomId, text.trim(), mentions) }
    }
    override fun stop(roomId: String) = engine.stop(roomId)
    override fun setDirectorInstruction(roomId: String, text: String, continueChat: Boolean) {
        compression.cancel(roomId)
        if (engine.setDirectorInstruction(roomId, text, continueChat)) {
            flush()
            notice(if (text.isBlank()) "剧情指令已清空" else if (continueChat) "已应用剧情指令，正在继续" else "剧情指令已保存，下次回复生效")
        } else notice("剧情指令未保存，请检查会话与内容长度")
    }
    override fun switchToB(messageId: String) = engine.switchToB(messageId)
    private fun cancelMessageCompression(messageId: String) {
        store.snapshot().messages.find { it.id == messageId }?.let { compression.cancel(it.roomId) }
    }
    override fun retry(messageId: String) { cancelMessageCompression(messageId); engine.retry(messageId) }
    override fun regenerate(messageId: String) { cancelMessageCompression(messageId); engine.regenerate(messageId) }
    override fun editMessage(messageId: String, text: String) {
        cancelMessageCompression(messageId)
        if (engine.editMessage(messageId, text)) {
            flush()
            notice("消息已更新")
        }
    }
    override fun editAndSendMessage(messageId: String, text: String) {
        cancelMessageCompression(messageId)
        if (engine.editAndSendMessage(messageId, text)) {
            flush()
            notice("已删除后续消息，从这里重新继续")
        } else {
            notice("未能发送，请确认消息仍在且内容不为空")
        }
    }
    override fun speak(messageId: String) {
        val s = store.snapshot(); val m = s.messages.find { it.id == messageId } ?: return
        val c = s.characters.find { it.id == m.characterId } ?: return
        if (m.status !in setOf(MessageStatus.COMPLETE, MessageStatus.ERROR, MessageStatus.INTERRUPTED)) return
        if (m.text.isBlank()) return
        speech.stop(setOf(m.id))
        speech.begin(m.id, c); speech.append(m.id, m.text); speech.finish(m.id)
    }
    override fun stopAudio() {
        speech.stopAll()
        mutableRuntime.update { it.copy(audioStatus = "") }
    }
    override fun previewVoice(character: Character) {
        mutableRuntime.update { it.copy(audioStatus = "正在准备音色试听") }
        speech.preview(character)
    }
    override fun saveTts(settings: TtsSettings, apiKey: String?, accessToken: String?, voiceAccessKey: String?, voiceSecretKey: String?) {
        try {
            validateTtsSettings(settings, store.snapshot().providers)
            val changeCatalogKey = !voiceAccessKey.isNullOrBlank() || !voiceSecretKey.isNullOrBlank()
            require(!changeCatalogKey || (!voiceAccessKey.isNullOrBlank() && !voiceSecretKey.isNullOrBlank())) { "请成对填写音色列表的 Access Key 和 Secret Key" }
            val origin = serviceOrigin(settings.endpoint, webSocket = true)
            val previous = store.snapshot().tts
            val changedHost = serviceOrigin(previous.endpoint, true) != origin
            speech.stopAll()
            if (changedHost) secrets.remove(setOf(ttsApiSecretId(settings), ttsTokenSecretId(settings)))
            else if (settings.appId.trim() != previous.appId.trim()) secrets.remove(setOf(ttsTokenSecretId(settings)))
            if (!apiKey.isNullOrBlank()) secrets.put(ttsApiSecretId(settings), apiKey)
            if (!accessToken.isNullOrBlank()) secrets.put(ttsTokenSecretId(settings), accessToken)
            if (changeCatalogKey) {
                secrets.put(VOICE_CATALOG_SECRET_ID, AppJson.encodeToString(VoiceCatalogCredentials(voiceAccessKey!!.trim(), voiceSecretKey!!.trim())))
                voiceCredentialGeneration++
                voiceRefreshJob?.cancel()
                mutableRuntime.update { it.copy(loadingDoubaoVoices = false, doubaoVoiceListError = "") }
            }
            store.update { it.copy(tts = settings.copy(endpoint = settings.endpoint.trim(), appId = settings.appId.trim(), resourceId = settings.resourceId.trim(),
                siliconModel = settings.siliconModel.trim(), siliconVoice = settings.siliconVoice.trim(),
                dialogueExtraction = settings.dialogueExtraction.copy(
                    model = settings.dialogueExtraction.model.copy(model = settings.dialogueExtraction.model.model.trim())))) }
            refreshCredentialStatus()
            flush()
            notice(when {
                settings.dialogueExtraction.enabled && settings.dialogueExtraction.model.providerId !in runtime.value.configuredProviders ->
                    "语音设置已保存，请先为对白提取模型的服务填写密钥"
                runtime.value.ttsConfigured || !settings.enabled -> "语音设置已保存"
                else -> "语音设置已保存，请填写当前服务的语音凭据"
            })
        } catch (e: Exception) { notice("保存失败：${e.message}") }
    }
    override fun exportBackup() = requestExport()
    override fun importBackup() = requestImport()
    suspend fun backupText(): String = withContext(Dispatchers.Default) { AppJson.encodeToString(store.snapshot()) }
    fun restoreBackup(text: String) {
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { validateImportedState(AppJson.decodeFromString<AppState>(text)) }
                compression.cancelAll()
                state.value.rooms.forEach { engine.stop(it.id) }; speech.stopAll()
                val invalidated = if (persistenceReady) credentialsInvalidatedByImport(store.snapshot(), parsed)
                    else parsed.providers.map(::providerSecretId).toSet() + setOf(ttsApiSecretId(parsed.tts), ttsTokenSecretId(parsed.tts))
                val removedCredentials = invalidated.any { secrets.get(it).isNotBlank() }
                persistMutex.withLock {
                    val serialized = withContext(Dispatchers.Default) { AppJson.encodeToString(parsed) }
                    // Clear changed-service keys before committing the imported endpoints.
                    secrets.remove(invalidated)
                    db.snapshotDao().write(SnapshotEntity(payload = serialized))
                    // The database write suspends; stop any turn started while it was in flight.
                    store.snapshot().rooms.forEach { engine.stop(it.id) }
                    compression.cancelAll()
                    speech.stopAll()
                    store.update { parsed }
                    persistenceReady = true
                }
                refreshCredentialStatus()
                notice(if (removedCredentials) "备份已恢复；服务地址或账号发生变化，相关密钥需重新填写" else "备份已恢复；密钥保持本机配置")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { notice("无法导入：${e.message}") }
        }
    }
    override fun onCleared() {
        compression.cancelAll()
        state.value.rooms.forEach { engine.stop(it.id) }
        speech.close()
        extractionClient.dispatcher.cancelAll()
        extractionClient.connectionPool.evictAll()
        extractionClient.dispatcher.executorService.shutdown()
        voiceHttpClient.dispatcher.cancelAll()
        voiceHttpClient.connectionPool.evictAll()
        voiceHttpClient.dispatcher.executorService.shutdown()
        db.close()
        super.onCleared()
    }
}

private fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(item) }) map { if (id(it) == id(item)) item else it } else this + item
