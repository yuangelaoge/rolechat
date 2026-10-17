package cn.rolechat.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable

const val DEFAULT_COMPRESSION_PROMPT = """请把这段角色扮演的旧聊天整理成简洁的前情回顾，帮助后续对话自然接下去。

尽量沿用原文的文风、语气和叙述习惯，保留人物各自的说话特点，以及当前的情绪、氛围和关系。不要把不同人物写成同一种声音，也不要整理成生硬的报告。

留下重要经历、关系变化、当前场景，以及还没说完的话、还没兑现的约定。重复的闲聊和琐碎动作可以略过，但有意味的互动、玩笑和称呼可以留下。遇到特别能体现人物个性的短句，可以少量保留原话。

只整理已有内容，不续写新剧情，不把举例或猜测变成已经发生的事。

直接输出前情回顾，不解释整理过程，不列检查清单。保留原来故事的味道。"""

@Serializable data class CompressionSettings(
    val keepRecentMessages: Int = 20,
    val model: ModelConfig = ModelConfig(thinking = ThinkingMode.OFF, temperature = 0.3, maxTokens = 4096),
    val systemPrompt: String = DEFAULT_COMPRESSION_PROMPT
)

@Serializable data class ContextSummary(
    val id: String = newId(),
    val roomId: String,
    val characterId: String,
    val text: String,
    val sourceMessageIds: List<String>,
    val sourceFingerprint: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** sourceMessages is the complete old prefix; a reusable previousSummary covers its first N entries. */
data class CompressionPlan(
    val roomId: String,
    val characterId: String,
    val characterName: String,
    val sourceMessages: List<ChatMessage>,
    val recentMessages: List<ChatMessage>,
    val previousSummary: ContextSummary?,
    val sourceFingerprint: String
)

fun validateCompressionSettings(settings: CompressionSettings, providers: List<Provider>? = null) {
    require(settings.keepRecentMessages in 1..200) { "保留最近消息数需在 1 到 200 条之间" }
    require(settings.model.providerId.isNotBlank() && settings.model.model.isNotBlank()) { "请选择前情整理模型" }
    require(settings.model.providerId.length <= 256 && settings.model.model.length <= 256) { "前情整理模型名称或服务 ID 不能超过 256 个字符" }
    require(providers == null || providers.any { it.id == settings.model.providerId }) { "请选择有效的前情整理模型服务" }
    require(settings.systemPrompt.isNotBlank() && settings.systemPrompt.length <= 32768) { "前情整理提示词不能为空，且不能超过 32768 个字符" }
    require(settings.model.maxTokens in 128..16384) { "前情整理最大输出需在 128 到 16384 之间" }
    require(settings.model.temperature.isFinite() && settings.model.temperature in 0.0..2.0) { "前情整理温度需在 0 到 2 之间" }
}

fun buildCompressionPlans(state: AppState, roomId: String, settings: CompressionSettings): List<CompressionPlan> {
    validateCompressionSettings(settings, state.providers)
    val room = state.rooms.singleOrNull { it.id == roomId } ?: throw IllegalArgumentException("找不到要整理前情的会话")
    return room.members.distinctBy { it.characterId }.mapNotNull { member ->
        val actor = state.characters.singleOrNull { it.id == member.characterId } ?: return@mapNotNull null
        val history = compressionVisibleHistory(state, room, actor)
        require(history.map { it.id }.distinct().size == history.size) { "聊天消息 ID 重复，无法安全整理前情" }
        val source = history.dropLast(settings.keepRecentMessages)
        if (source.isEmpty()) return@mapNotNull null
        val recent = history.takeLast(settings.keepRecentMessages)
        val previous = validContextSummary(state, room, actor, history)?.takeIf {
            it.sourceMessageIds.size <= source.size && source.take(it.sourceMessageIds.size).map(ChatMessage::id) == it.sourceMessageIds
        }
        CompressionPlan(room.id, actor.id, actor.name, source, recent, previous, compressionFingerprint(room, actor, source))
    }
}

/** Never expands visibility: a summary must match both today's visible prefix and this request's historical prefix. */
fun validContextSummary(state: AppState, room: ChatRoom, actor: Character, history: List<ChatMessage>): ContextSummary? {
    val currentRoom = state.rooms.singleOrNull { it.id == room.id } ?: return null
    val currentActor = state.characters.singleOrNull { it.id == actor.id } ?: return null
    val member = currentRoom.members.firstOrNull { it.characterId == actor.id } ?: return null
    val suppliedMember = room.members.firstOrNull { it.characterId == actor.id } ?: return null
    if (currentActor.name != actor.name || currentActor.messageStyle != actor.messageStyle ||
        member.joinedAt != suppliedMember.joinedAt || member.readHistory != suppliedMember.readHistory) return null
    val visible = compressionVisibleHistory(state, currentRoom, currentActor)
    return state.contextSummaries.asReversed().firstOrNull { summary ->
        val size = summary.sourceMessageIds.size
        summary.roomId == room.id && summary.characterId == actor.id && summary.text.isNotBlank() && summary.text.length <= 32768 &&
            size > 0 && summary.sourceMessageIds.distinct().size == size && size <= visible.size && size <= history.size &&
            summary.sourceMessageIds == visible.take(size).map(ChatMessage::id) &&
            summary.sourceMessageIds == history.take(size).map(ChatMessage::id) &&
            summary.sourceFingerprint == compressionFingerprint(currentRoom, currentActor, visible.take(size)) &&
            summary.sourceFingerprint == compressionFingerprint(currentRoom, currentActor, history.take(size))
    }
}

/** All role drafts are verified against the same current state before anything is committed. */
fun applyCompressionDrafts(state: AppState, settings: CompressionSettings, plans: List<CompressionPlan>,
                           texts: Map<String, String>): AppState {
    require(plans.isNotEmpty()) { "没有可应用的前情整理结果" }
    require(plans.map { it.roomId }.distinct().size == 1 && plans.map { it.characterId }.distinct().size == plans.size) { "前情整理计划不完整" }
    require(texts.keys == plans.map { it.characterId }.toSet()) { "请等待全部角色的前情整理完成" }
    require(texts.values.all { it.isNotBlank() && it.length <= 32768 }) { "前情回顾不能为空，且不能超过 32768 个字符" }
    val roomId = plans.first().roomId
    val room = state.rooms.singleOrNull { it.id == roomId } ?: throw IllegalArgumentException("会话已不存在，无法应用前情")
    val currentPlans = buildCompressionPlans(state, roomId, settings).associateBy { it.characterId }
    require(currentPlans.keys == plans.map { it.characterId }.toSet()) { "群成员或可见历史已改变，请重新整理前情" }
    plans.forEach { draft ->
        val current = currentPlans.getValue(draft.characterId)
        val actor = state.characters.single { it.id == draft.characterId }
        require(current.characterName == draft.characterName && current.sourceFingerprint == draft.sourceFingerprint &&
            current.sourceMessages.map(ChatMessage::id) == draft.sourceMessages.map(ChatMessage::id) &&
            compressionFingerprint(room, actor, draft.sourceMessages) == draft.sourceFingerprint &&
            current.recentMessages.map(ChatMessage::id) == draft.recentMessages.map(ChatMessage::id) &&
            compressionFingerprint(room, actor, current.recentMessages) == compressionFingerprint(room, actor, draft.recentMessages) &&
            current.previousSummary == draft.previousSummary) { "聊天内容、角色或历史权限已改变，请重新整理前情" }
    }
    val replacements = plans.map { plan -> ContextSummary(roomId = roomId, characterId = plan.characterId,
        text = texts.getValue(plan.characterId).trim(), sourceMessageIds = plan.sourceMessages.map(ChatMessage::id),
        sourceFingerprint = plan.sourceFingerprint) }
    val replacedActors = replacements.map { it.characterId }.toSet()
    return state.copy(rooms = state.rooms.map { if (it.id == roomId) it.copy(compression = settings) else it },
        contextSummaries = state.contextSummaries.filterNot { it.roomId == roomId && it.characterId in replacedActors } + replacements)
}

private fun compressionVisibleHistory(state: AppState, room: ChatRoom, actor: Character): List<ChatMessage> {
    val member = room.members.firstOrNull { it.characterId == actor.id } ?: return emptyList()
    return state.messages.filter { it.roomId == room.id && it.text.isNotBlank() && it.status in compressionEndedStatuses &&
        (member.readHistory || it.createdAt >= member.joinedAt) }
}

/** Length-framed UTF-8 fields avoid ambiguous separators; reasoning, routing and speech metadata are deliberately absent. */
internal fun compressionFingerprint(room: ChatRoom, actor: Character, messages: List<ChatMessage>): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        fun field(value: String?) {
            if (value == null) out.writeInt(-1) else value.toByteArray(Charsets.UTF_8).let { out.writeInt(it.size); out.write(it) }
        }
        field("rolechat-context-v1"); field(room.id); field(actor.id); field(actor.name); field(actor.messageStyle.name)
        val member = room.members.firstOrNull { it.characterId == actor.id }
        field(member?.joinedAt?.toString()); field(member?.readHistory?.toString())
        out.writeInt(messages.size)
        messages.forEach { message ->
            field(message.id); field(message.roomId); field(message.characterId); field(message.senderName)
            field(message.messageStyle.name); field(message.status.name); field(message.text); out.writeLong(message.createdAt)
            if (message.isDirector) field("director-operation")
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    val alphabet = "0123456789abcdef"
    return buildString(64) { digest.forEach { byte -> val value = byte.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15]) } }
}

private val compressionEndedStatuses = setOf(MessageStatus.COMPLETE, MessageStatus.INTERRUPTED, MessageStatus.ERROR)
