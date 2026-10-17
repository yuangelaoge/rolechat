package cn.rolechat.app

import cn.rolechat.core.*
import cn.rolechat.app.speech.DoubaoVoice
import cn.rolechat.app.speech.DoubaoVoices

/** All callbacks are non-blocking; implementations own their coroutine scope. */
interface AppActions {
    fun saveCharacter(character: Character)
    fun setCharacterVoice(characterId: String, voice: String): Boolean
    fun deleteCharacter(id: String)
    fun saveWorld(world: World)
    fun saveRoom(room: ChatRoom)
    fun deleteRoom(id: String)
    fun clearHistory(roomId: String)
    fun generateCompression(roomId: String, settings: CompressionSettings)
    fun applyCompression(roomId: String, previewId: String, texts: Map<String, String>)
    fun cancelCompression(roomId: String)
    fun clearCompression(roomId: String)
    fun setRoomPinned(roomId: String, pinned: Boolean)
    fun exportGroup(roomId: String)
    fun importGroup()
    fun saveProvider(provider: Provider, newApiKey: String?)
    fun refreshModels(providerId: String)
    fun refreshDoubaoVoices()
    fun send(roomId: String, text: String, mentions: Set<String>)
    fun setDirectorInstruction(roomId: String, text: String, continueChat: Boolean)
    fun stop(roomId: String)
    fun switchToB(messageId: String)
    fun retry(messageId: String)
    fun regenerate(messageId: String)
    fun editMessage(messageId: String, text: String)
    fun editAndSendMessage(messageId: String, text: String)
    fun speak(messageId: String)
    fun previewVoice(character: Character)
    fun stopAudio()
    fun saveTts(settings: TtsSettings, apiKey: String?, accessToken: String?, voiceAccessKey: String? = null, voiceSecretKey: String? = null)
    fun exportBackup()
    fun importBackup()
}

data class RuntimeUi(
    val busyRooms: Set<String> = emptySet(),
    val configuredProviders: Set<String> = emptySet(),
    val modelLists: Map<String, List<String>> = emptyMap(),
    val loadingModels: Set<String> = emptySet(),
    val notice: String = "", val audioStatus: String = "", val ttsConfigured: Boolean = false,
    val doubaoVoices: List<DoubaoVoice> = DoubaoVoices.builtIn,
    val loadingDoubaoVoices: Boolean = false, val doubaoVoiceListUpdatedAt: Long = 0,
    val doubaoVoiceListError: String = "", val voiceCatalogConfigured: Boolean = false,
    val compressions: Map<String, CompressionUiState> = emptyMap()
)

enum class CompressionStatus { GENERATING, READY, ERROR }

data class CompressionDraft(
    val characterId: String, val characterName: String,
    val sourceCount: Int, val recentCount: Int, val text: String
)

data class CompressionUiState(
    val id: String = newId(), val roomId: String, val settings: CompressionSettings,
    val status: CompressionStatus, val drafts: List<CompressionDraft> = emptyList(),
    val completed: Int = 0, val total: Int = 0, val error: String = ""
)
