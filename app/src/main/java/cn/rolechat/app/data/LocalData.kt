package cn.rolechat.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import cn.rolechat.core.*
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

@Entity(tableName = "app_snapshot") data class SnapshotEntity(@PrimaryKey val id: Int = 1, val payload: String)
@Dao interface SnapshotDao {
    @Query("SELECT * FROM app_snapshot WHERE id = 1") suspend fun read(): SnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun write(entity: SnapshotEntity)
}
@Database(entities = [SnapshotEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun snapshotDao(): SnapshotDao
    companion object {
        fun open(context: Context): ChatDatabase = Room.databaseBuilder(context, ChatDatabase::class.java, "rolechat.db").build()
    }
}

/** Secret values are excluded from both AppState and exported backups. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE)
    private val alias = "rolechat.credentials.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(id: String, value: String) {
        if (value.isBlank()) { remove(setOf(id)); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(id, encoded).commit()) { "密钥保存失败，请重试" }
    }
    @Synchronized fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        val edit = prefs.edit()
        ids.forEach(edit::remove)
        check(edit.commit()) { "旧密钥移除失败，请重试" }
    }
    @Synchronized fun get(id: String): String = runCatching {
        val parts = prefs.getString(id, null)?.split(':') ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
}

fun recoveredState(state: AppState): AppState = state.copy(messages = state.messages.map { original ->
    val preparation = original.speechPreparation
    val prepared = if (preparation?.status == SpeechPreparationStatus.EXTRACTING)
        original.copy(speechPreparation = preparation.copy(status = SpeechPreparationStatus.CANCELLED, error = "上次语音前置处理已中断"))
        else original
    val routing = prepared.routingDecision
    val message = if (routing?.status == RoutingStatus.DECIDING)
        prepared.copy(routingDecision = routing.copy(status = RoutingStatus.CANCELLED, error = "上次接话判断已中断，发送消息可继续"))
        else prepared
    if (message.status in setOf(MessageStatus.QUEUED, MessageStatus.GENERATING_A, MessageStatus.THINKING_A,
            MessageStatus.HANDOFF, MessageStatus.GENERATING_B, MessageStatus.THINKING_B)) {
        message.copy(status = MessageStatus.INTERRUPTED, error = "上次生成已中断，可重试", segments = message.segments.map {
            if (it.status == SegmentStatus.RUNNING) it.copy(status = SegmentStatus.INTERRUPTED) else it
        })
    } else message
})

fun validateImportedState(state: AppState): AppState {
    fun ids(values: List<String>, label: String) {
        require(values.all(String::isNotBlank)) { "${label} ID 不能为空" }
        require(values.distinct().size == values.size) { "${label} ID 重复" }
    }
    ids(state.providers.map { it.id }, "供应商")
    ids(state.worlds.map { it.id }, "世界观")
    ids(state.characters.map { it.id }, "角色")
    ids(state.rooms.map { it.id }, "会话")
    ids(state.messages.map { it.id }, "消息")
    state.characters.forEach { validateAvatarImageData(it.avatarImage, "角色头像") }
    state.providers.forEach { serviceOrigin(it.baseUrl) }
    validateTtsSettings(state.tts, state.providers)
    require(state.messages.size <= 100000) { "备份消息过多" }
    val characters = state.characters.map { it.id }.toSet()
    val rooms = state.rooms.map { it.id }.toSet()
    state.rooms.forEach { room ->
        require(room.directorInstruction.length <= MAX_DIRECTOR_INSTRUCTION) { "剧情指令过长" }
        validateAvatarImageData(room.userAvatarImage, "我的头像")
        ids(room.members.map { it.characterId }, "群成员")
        require(room.members.all { it.characterId in characters }) { "会话包含不存在的角色" }
        if (room.mode == ReplyMode.SMART) validateRoutingSettings(room.routing, state.providers)
        // This optional feature did not exist in older backups with custom provider IDs.
        // Resolve the provider only when compression is actually requested.
        validateCompressionSettings(room.compression)
    }
    require(state.messages.all { it.roomId in rooms }) { "消息所属会话不存在" }
    require(state.messages.all { (it.requestMessageId?.length ?: 0) <= 1024 }) { "本轮要求引用过长" }
    state.messages.mapNotNull { it.speechPreparation }.forEach {
        require(it.rawOutput.length <= 32768 && it.reasoning.length <= 16384 && it.text.length <= 32768 &&
            it.instruction.length <= 1000 && it.error.length <= 500 &&
            it.previousInstruction.length <= 1000 && (it.previousMessageId?.length ?: 0) <= 1024) {
            "语音前置处理记录过长"
        }
        require(it.voice?.let { voice -> voice.resourceId.length <= 2048 && voice.voiceId.length <= 2048 } != false) {
            "语音前置处理的声音配置记录过长"
        }
    }
    state.messages.mapNotNull { it.routingDecision }.forEach {
        require(it.rawOutput.length <= 16384 && it.reason.length <= 500 && it.error.length <= 500 &&
            it.model.length <= 1024 && it.targetId.length <= 1024 && it.selectedName.length <= 1024 &&
            (it.selectedCharacterId?.length ?: 0) <= 1024) { "接话安排记录过长" }
    }
    require(state.contextSummaries.size <= 10000) { "备份前情记录过多" }
    ids(state.contextSummaries.map { it.id }, "前情")
    require(state.contextSummaries.map { it.roomId to it.characterId }.distinct().size == state.contextSummaries.size) {
        "同一会话角色有重复的当前前情"
    }
    val roomById = state.rooms.associateBy { it.id }
    state.contextSummaries.forEach { summary ->
        require(summary.id.length <= 256 && summary.roomId.isNotBlank() && summary.roomId.length <= 256 &&
            summary.characterId.isNotBlank() && summary.characterId.length <= 256) { "前情 ID 或归属信息无效" }
        require(summary.roomId in rooms && summary.characterId in characters &&
            roomById[summary.roomId]!!.members.any { it.characterId == summary.characterId }) { "前情所属会话或角色不存在" }
        require(summary.text.isNotBlank() && summary.text.length <= 32768) { "前情正文不能为空，且不能超过 32768 个字符" }
        require(summary.sourceMessageIds.isNotEmpty() && summary.sourceMessageIds.size <= 100000 &&
            summary.sourceMessageIds.all { it.isNotBlank() && it.length <= 256 } &&
            summary.sourceMessageIds.distinct().size == summary.sourceMessageIds.size) { "前情来源消息记录无效" }
        require(summary.sourceFingerprint.length == 64 && summary.sourceFingerprint.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }) { "前情来源校验记录无效" }
        // Deleted/edited source messages make a summary stale, not the entire backup invalid.
        // The engine rechecks the fingerprint and falls back to visible original history.
    }
    // Historical messages from deleted characters are deliberately retained.
    return recoveredState(state)
}

fun validateTtsSettings(settings: TtsSettings, providers: List<Provider>) {
    serviceOrigin(settings.endpoint, webSocket = true)
    require(settings.siliconSpeed.isFinite() && settings.siliconSpeed in 0.25..4.0) { "语音速度需在 0.25 到 4.0 之间" }
    if (settings.enabled && settings.engine == TtsEngine.SILICONFLOW) {
        require(providers.any { it.id == settings.siliconProviderId }) { "请选择有效的硅基流动模型服务" }
        require(settings.siliconModel.isNotBlank() && settings.siliconVoice.isNotBlank()) { "请填写默认语音模型和音色" }
    }
    if (settings.dialogueExtraction.enabled) {
        val extraction = settings.dialogueExtraction
        require(providers.any { it.id == extraction.model.providerId }) { "请选择有效的对白提取模型服务" }
        require(extraction.model.model.isNotBlank()) { "请填写对白提取模型名称" }
        require(extraction.systemPrompt.isNotBlank()) { "请填写对白提取提示词" }
        require(extraction.systemPrompt.length <= 32_768) { "对白提取提示词不能超过 32768 个字符" }
        require(extraction.model.maxTokens in 1..32_768) { "对白提取最大输出需在 1 到 32768 之间" }
        require(extraction.model.temperature.isFinite() && extraction.model.temperature in 0.0..2.0) { "对白提取温度需在 0 到 2 之间" }
    }
}

/** Normalize and validate before associating an API credential with a remote service. */
fun serviceOrigin(address: String, webSocket: Boolean = false): String {
    val value = address.trim()
    val scheme = if (webSocket) "wss://" else "https://"
    require(value.startsWith(scheme)) { if (webSocket) "语音地址必须使用 WSS" else "模型地址必须使用 HTTPS" }
    val httpAddress = if (webSocket) "https://" + value.removePrefix("wss://") else value
    val url = requireNotNull(httpAddress.toHttpUrlOrNull()) { "接口地址格式不正确" }
    require(url.username.isEmpty() && url.password.isEmpty()) { "接口地址不能包含账号或密码" }
    require(url.query == null && url.fragment == null) { "接口地址不能包含查询参数或片段" }
    return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
}

fun providerSecretId(provider: Provider): String = "provider:${provider.id}:${serviceOrigin(provider.baseUrl)}"
fun ttsApiSecretId(settings: TtsSettings): String = "tts:key:${serviceOrigin(settings.endpoint, true)}"
fun ttsTokenSecretId(settings: TtsSettings): String = "tts:token:${serviceOrigin(settings.endpoint, true)}:${settings.appId.trim()}"

/** Never send an existing local credential to a different service imported from a file. */
fun credentialsInvalidatedByImport(previous: AppState, imported: AppState): Set<String> = buildSet {
    imported.providers.forEach { incoming ->
        val existing = previous.providers.find { it.id == incoming.id }
        if (existing == null || serviceOrigin(existing.baseUrl) != serviceOrigin(incoming.baseUrl)) {
            add(providerSecretId(incoming))
        }
    }
    if (serviceOrigin(previous.tts.endpoint, true) != serviceOrigin(imported.tts.endpoint, true) ||
        previous.tts.appId.trim() != imported.tts.appId.trim()) {
        add(ttsApiSecretId(imported.tts))
        add(ttsTokenSecretId(imported.tts))
    }
}
