package cn.rolechat.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/**
 * Owns turn scheduling, not HTTP or audio transport. All public commands return immediately.
 * A generation epoch makes callbacks from cancelled streams unable to change a newer turn.
 */
class ChatEngine(
    private val store: StateStore,
    private val gateway: ChatGateway,
    private val scope: CoroutineScope,
    private val speech: SpeechSink = SilentSpeech,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val router: TurnRouter = ModelTurnRouter(gateway)
) {
    private val lock = Any()
    private val epochs = mutableMapOf<String, Long>()
    private val roomJobs = mutableMapOf<String, Job>()
    private val handoffs = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val lastSpoke = mutableMapOf<Pair<String, String>, Long>()
    private val mutableBusy = MutableStateFlow<Set<String>>(emptySet())
    val busyRooms: StateFlow<Set<String>> = mutableBusy.asStateFlow()

    fun send(roomId: String, text: String, mentions: Set<String> = emptySet()) {
        if (text.isBlank()) return
        synchronized(lock) {
            val state = store.snapshot()
            val room = state.rooms.find { it.id == roomId } ?: return
            cancelLocked(roomId, "被新消息打断")
            val roundId = newId()
            val user = ChatMessage(roomId = roomId, senderName = room.userName.trim().ifBlank { "我" },
                text = text.trim(), mentions = mentions.toList(),
                createdAt = now(), roundId = roundId, messageStyle = MessageStyle.CHAT)
            val requestMessageId = if (room.direct) null else continuationRequest(state, room, user.text) ?: user.id
            store.update { it.copy(messages = it.messages + user) }
            startLocked(roomId) { epoch -> runRound(room, roundId, mentions, epoch, initialTriggerId = user.id,
                requestMessageId = requestMessageId) }
        }
    }

    fun stop(roomId: String) = synchronized(lock) { cancelLocked(roomId, "已停止本轮") }

    fun setDirectorInstruction(roomId: String, text: String, continueChat: Boolean): Boolean = synchronized(lock) {
        val value = text.trim()
        if (value.length > MAX_DIRECTOR_INSTRUCTION || (continueChat && value.isBlank())) return@synchronized false
        val room = store.snapshot().rooms.find { it.id == roomId } ?: return@synchronized false
        cancelLocked(roomId, "剧情指令已更新")
        val updated = room.copy(directorInstruction = value)
        val event = ChatMessage(roomId = roomId, senderName = "System · 剧情指令",
            text = value.ifBlank { "已清空剧情指令" }, isDirector = true, createdAt = now(), roundId = newId(),
            directorInstructionValue = value, previousDirectorInstruction = room.directorInstruction)
        store.update { state -> state.copy(rooms = state.rooms.map { if (it.id == roomId) updated else it },
            messages = if (continueChat || room.directorInstruction != value) state.messages + event else state.messages) }
        if (continueChat) startLocked(roomId) { epoch ->
            runRound(updated, event.roundId, emptySet(), epoch, initialTriggerId = event.id,
                requestMessageId = if (room.direct) null else event.id)
        }
        true
    }

    fun clearHistory(roomId: String) = removeRoomHistory(roomId, deleteRoom = false)

    fun deleteRoom(roomId: String) = removeRoomHistory(roomId, deleteRoom = true)

    /** Edits a message in place; future context resolves the same ID to the new text. */
    fun editMessage(messageId: String, text: String): Boolean = synchronized(lock) {
        val value = text.trim()
        if (value.isBlank()) return@synchronized false
        val state = store.snapshot()
        val message = state.messages.find { it.id == messageId } ?: return@synchronized false
        if (message.isDirector) return@synchronized false
        if (message.text == value || state.rooms.none { it.id == message.roomId })
            return@synchronized false
        // A running reply was built from the old text. Stop it before applying the edit,
        // but keep completed later replies and never automatically launch a new request.
        cancelLocked(message.roomId, "因编辑消息停止")
        store.updateMessage(messageId) { manuallyEdited(it, value) }
        true
    }

    /** Rewinds this room to the edited message, then starts a new round (also for unchanged text). */
    fun editAndSendMessage(messageId: String, text: String): Boolean = synchronized(lock) {
        val value = text.trim()
        if (value.isBlank()) return@synchronized false
        val state = store.snapshot()
        val original = state.messages.find { it.id == messageId } ?: return@synchronized false
        if (original.isDirector) return@synchronized false
        val room = state.rooms.find { it.id == original.roomId } ?: return@synchronized false
        val requestMessageId = if (room.direct) null else if (original.characterId == null) original.id
            else rememberedRequest(state, room, original)
        cancelLocked(room.id, "因编辑并发送停止")
        store.update { current ->
            val rewound = rewindConversation(current, messageId)
            rewound.copy(messages = rewound.messages.map { if (it.id == messageId) manuallyEdited(it, value) else it })
        }
        // Replies from the discarded future cannot keep participants in cooldown.
        lastSpoke.keys.removeAll { it.first == room.id }
        store.snapshot().messages.filter { it.roomId == room.id && it.id != messageId &&
            it.characterId != null && it.text.isNotBlank() }.forEach {
            lastSpoke[room.id to it.characterId!!] = it.createdAt.coerceAtMost(now())
        }
        val rewoundRoom = store.snapshot().rooms.first { it.id == room.id }
        val roundId = newId()
        val mentions = if (original.characterId == null) original.mentions.toSet() else emptySet()
        startLocked(room.id) { epoch ->
            runRound(rewoundRoom, roundId, mentions, epoch, resentMessageId = messageId,
                triggeringCharacterId = original.characterId, initialTriggerId = messageId, requestMessageId = requestMessageId)
        }
        true
    }

    private fun manuallyEdited(message: ChatMessage, value: String): ChatMessage =
        // Model provenance and reasoning no longer describe a manually edited
        // reply. Historical identity/context remain valid even if its actor left.
        if (message.characterId == null) message.copy(text = value, speechPreparation = null, routingDecision = null, replyTopicStatus = null)
        else message.copy(text = value, segments = emptyList(), error = "", status = MessageStatus.COMPLETE,
            speechPreparation = null, routingDecision = null, replyTopicStatus = null)

    private fun removeRoomHistory(roomId: String, deleteRoom: Boolean) = synchronized(lock) {
        // Keep the incremented epoch even after deleting the room, so an old
        // transport cannot affect a newly created conversation with the same ID.
        cancelLocked(roomId, if (deleteRoom) "会话已删除" else "聊天记录已清空")
        lastSpoke.keys.removeAll { it.first == roomId }
        store.update { state ->
            val removedIds = state.messages.filter { it.roomId == roomId }.mapTo(mutableSetOf()) { it.id }
            state.copy(
                rooms = if (deleteRoom) state.rooms.filterNot { it.id == roomId } else state.rooms.map {
                    if (it.id == roomId) it.copy(directorInstruction = "") else it
                },
                contextSummaries = state.contextSummaries.filterNot { summary ->
                    summary.roomId == roomId || summary.sourceMessageIds.any { it in removedIds }
                },
                messages = state.messages.filterNot { it.roomId == roomId }.map { message ->
                    val remainingContext = message.contextMessageIds.filterNot { it in removedIds }
                    val remainingTrigger = message.triggerMessageId?.takeUnless { it in removedIds }
                    val remainingReplyTo = message.replyToMessageId?.takeUnless { it in removedIds }
                    val remainingRequest = message.requestMessageId?.takeUnless { it in removedIds }
                    if (remainingContext == message.contextMessageIds && remainingTrigger == message.triggerMessageId &&
                        remainingReplyTo == message.replyToMessageId && remainingRequest == message.requestMessageId) message
                    else message.copy(contextMessageIds = remainingContext, triggerMessageId = remainingTrigger,
                        replyToMessageId = remainingReplyTo, requestMessageId = remainingRequest,
                        replyTopicStatus = if (remainingRequest == null) null else message.replyTopicStatus)
                }
            )
        }
    }

    /** Manual A -> B switch is distinct from stopping a turn or sending a new user message. */
    fun switchToB(messageId: String) = synchronized(lock) {
        handoffs[messageId]?.complete(Unit)
        Unit
    }

    fun retry(messageId: String) = restartMessage(messageId, regenerate = false)

    /** Replaces this reply from A without sending a user message or scheduling other actors. */
    fun regenerate(messageId: String) = restartMessage(messageId, regenerate = true)

    private fun restartMessage(messageId: String, regenerate: Boolean) {
        synchronized(lock) {
            val state = store.snapshot()
            val original = state.messages.find { it.id == messageId } ?: return
            if (original.characterId == null) return
            val room = state.rooms.find { it.id == original.roomId } ?: return
            val actor = state.characters.find { it.id == original.characterId } ?: return
            if (room.members.none { it.characterId == actor.id }) return
            cancelLocked(room.id, if (regenerate) "被重新生成打断" else "被重试打断")
            val current = store.snapshot().messages.first { it.id == messageId }
            // Retrying B discards the previous B attempt, retaining A exactly once.
            val retryB = !regenerate && actor.dualModel && current.segments.any { it.label == "B" }
            val prefixSegments = if (retryB) current.segments.filter { it.label == "A" } else emptyList()
            val resentMessageId = replayTrigger(store.snapshot(), room, current)
            val replyToMessageId = replayReference(store.snapshot(), room, current, current.replyToMessageId)?.takeIf { id ->
                roundHistory(store.snapshot(), room, actor, resentMessageId).any { it.id == id }
            }
            val history = replayHistory(store.snapshot(), room, actor, current, resentMessageId)
            val requestMessageId = if (room.direct) null else rememberedRequest(store.snapshot(), room, current, history.map { it.id }.toSet())
            store.updateMessage(messageId) {
                it.copy(text = prefixSegments.joinToString("") { segment -> segment.text },
                    segments = prefixSegments, status = MessageStatus.QUEUED, error = "", speechPreparation = null,
                    triggerMessageId = resentMessageId, replyToMessageId = replyToMessageId, routingDecision = null,
                    requestMessageId = requestMessageId, replyTopicStatus = if (requestMessageId == null) null else current.replyTopicStatus)
            }
            startLocked(room.id) { epoch ->
                generate(room, actor, messageId, history, epoch, retryB = retryB, resentMessageId = resentMessageId,
                    replyToMessageId = replyToMessageId, requestMessageId = requestMessageId, replyTopicStatus = current.replyTopicStatus)
            }
        }
    }

    private fun replayTrigger(state: AppState, room: ChatRoom, message: ChatMessage): String? {
        return replayReference(state, room, message, message.triggerMessageId)
    }

    private fun replayReference(state: AppState, room: ChatRoom, message: ChatMessage, reference: String?): String? {
        val triggerId = reference ?: return null
        if (triggerId !in message.contextMessageIds) return null
        return state.messages.takeWhile { it.id != message.id }.firstOrNull {
            it.id == triggerId && it.roomId == room.id && it.text.isNotBlank() && it.status !in activeStatuses
        }?.id
    }

    /** A request reference grants no extra history access and can only point to an earlier human message. */
    private fun rememberedRequest(state: AppState, room: ChatRoom, message: ChatMessage,
                                  visibleIds: Set<String>? = null): String? {
        val contextIds = message.contextMessageIds.toSet()
        val humans = state.messages.takeWhile { it.id != message.id }.filter {
            it.roomId == room.id && it.characterId == null && it.text.isNotBlank() && it.status !in activeStatuses &&
                it.id in contextIds && (visibleIds == null || it.id in visibleIds)
        }
        // Only legacy records without a reference use the last human from their actual saved context.
        return if (message.requestMessageId == null) humans.lastOrNull()?.id
        else humans.firstOrNull { it.id == message.requestMessageId }?.id
    }

    private fun continuationRequest(state: AppState, room: ChatRoom, text: String): String? {
        if (!isPureContinuation(text)) return null
        val previous = state.messages.lastOrNull { it.roomId == room.id && it.characterId != null } ?: return null
        if (previous.requestMessageId == null) return null
        // An unanswered intervening instruction is a new request, not permission to revive an older one.
        if (state.messages.dropWhile { it.id != previous.id }.drop(1).any {
                it.roomId == room.id && it.characterId == null && !isPureContinuation(it.text)
            }) return null
        return rememberedRequest(state, room, previous)
    }

    private fun isPureContinuation(text: String): Boolean {
        val normalized = text.filterNot { it.isWhitespace() || it in continuationPunctuation }
        return normalized in continuationPhrases
    }

    private fun replayHistory(state: AppState, room: ChatRoom, actor: Character, message: ChatMessage,
                              resentMessageId: String? = null): List<ChatMessage> {
        // Array order defines the conversation chronology, including messages with
        // identical timestamps. Never replay the target or anything after it, even
        // if an imported context ID list contains those IDs.
        val earlierIds = state.messages.takeWhile { it.id != message.id }.mapTo(mutableSetOf()) { it.id }
        val visible = roundHistory(state, room, actor, resentMessageId)
            .filter { it.id in earlierIds }.associateBy { it.id }
        return message.contextMessageIds.distinct().mapNotNull { visible[it] }
    }

    private fun startLocked(roomId: String, block: suspend (Long) -> Unit) {
        val epoch = epochs.getOrPut(roomId) { 1L }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(lock) {
                    if (isCurrent(roomId, epoch)) {
                        store.update { state -> state.copy(messages = state.messages.map { message ->
                            if (message.roomId == roomId && message.status in activeStatuses)
                                message.copy(status = MessageStatus.ERROR, error = safeError(failure),
                                    segments = message.segments.map { segment ->
                                        if (segment.status == SegmentStatus.RUNNING)
                                            segment.copy(status = SegmentStatus.ERROR) else segment
                                    }) else message
                        }) }
                    }
                }
            }
        }
        roomJobs[roomId] = job
        mutableBusy.value = mutableBusy.value + roomId
        job.invokeOnCompletion {
            synchronized(lock) {
                if (roomJobs[roomId] === job) {
                    roomJobs.remove(roomId)
                    mutableBusy.value = mutableBusy.value - roomId
                }
            }
        }
        job.start()
    }

    private fun cancelLocked(roomId: String, reason: String) {
        epochs[roomId] = (epochs[roomId] ?: 0L) + 1L
        roomJobs.remove(roomId)?.cancel()
        mutableBusy.value = mutableBusy.value - roomId
        val roomIds = store.snapshot().messages.filter { it.roomId == roomId }.map { it.id }.toSet()
        roomIds.forEach { handoffs.remove(it) }
        store.update { state -> state.copy(messages = state.messages.map { message ->
            val stopped = if (message.roomId == roomId && message.status in activeStatuses) {
                message.copy(status = MessageStatus.INTERRUPTED, error = reason,
                    segments = message.segments.map { segment ->
                        if (segment.status == SegmentStatus.RUNNING)
                            segment.copy(status = SegmentStatus.INTERRUPTED, note = reason) else segment
                    })
            } else message
            if (stopped.roomId == roomId && stopped.routingDecision?.status == RoutingStatus.DECIDING)
                stopped.copy(routingDecision = stopped.routingDecision.copy(status = RoutingStatus.CANCELLED, error = reason))
            else stopped
        }) }
        runCatching { speech.stop(roomIds) }
    }

    private suspend fun runRound(room: ChatRoom, roundId: String, mentions: Set<String>, epoch: Long,
                                 resentMessageId: String? = null, triggeringCharacterId: String? = null,
                                 initialTriggerId: String? = null, requestMessageId: String? = null) {
        if (room.mode == ReplyMode.SMART && !room.direct) {
            if (initialTriggerId != null) runSmartRound(room, roundId, initialTriggerId, mentions, epoch, resentMessageId, requestMessageId)
            return
        }
        val state = store.snapshot()
        val members = room.members.mapNotNull { member ->
            state.characters.find { it.id == member.characterId }
        }.distinctBy { it.id }
        // Editing an AI reply invites its peers to respond. With no peer present,
        // explicitly continue as that same actor in a new message (including direct chats).
        val actors = if (triggeringCharacterId != null && members.any { it.id != triggeringCharacterId })
            members.filterNot { it.id == triggeringCharacterId } else members
        val continuingOnlyActor = triggeringCharacterId != null && actors.singleOrNull()?.id == triggeringCharacterId
        val ordered = if (room.shuffleOrder) actors.shuffled(random) else actors
        val chosen = if (room.direct || continuingOnlyActor) ordered.take(1) else synchronized(lock) {
            val mandatory = ordered.filter { it.id in mentions }
            val ordinary = ordered.filter { it.id !in mentions && eligible(room, it) }
                .take((room.maxInitialReplies.coerceAtLeast(0) - mandatory.size).coerceAtLeast(0))
            val ids = (mandatory + ordinary).map { it.id }.toSet()
            ordered.filter { it.id in ids }
        }
        val counts = mutableMapOf<String, Int>()
        var lastActorId: String? = null
        if (room.mode == ReplyMode.PARALLEL) {
            // One frozen history for the batch, before any reply placeholder is inserted.
            val frozen = store.snapshot()
            val messages = chosen.map { actor ->
                actor to placeholder(room, actor, roundId, epoch, resentMessageId)
            }
            val prestartedAudio = room.autoSpeak && frozen.tts.enabled
            if (prestartedAudio) messages.forEach { (actor, messageId) ->
                if (messageId != null) audioCurrent(room.id, epoch) { speech.begin(messageId, actor) }
            }
            coroutineScope {
                messages.forEach { (actor, messageId) ->
                    if (messageId != null) launch {
                        generate(room, actor, messageId, roundHistory(frozen, room, actor, resentMessageId), epoch,
                            speechStarted = prestartedAudio, resentMessageId = resentMessageId, requestMessageId = requestMessageId,
                            allowLegacyRequestFallback = resentMessageId != null)
                    }
                }
            }
            chosen.forEach { counts[it.id] = 1 }
            lastActorId = chosen.lastOrNull()?.id
        } else {
            for (actor in chosen) {
                val history = roundHistory(store.snapshot(), room, actor, resentMessageId)
                val messageId = placeholder(room, actor, roundId, epoch, resentMessageId) ?: return
                generate(room, actor, messageId, history, epoch, resentMessageId = resentMessageId, requestMessageId = requestMessageId,
                    allowLegacyRequestFallback = resentMessageId != null)
                counts[actor.id] = 1
                lastActorId = actor.id
            }
        }
        if (!room.interChat || room.direct || chosen.isEmpty()) return
        repeat(room.maxFollowUps.coerceIn(0, 100)) {
            if (!isCurrent(room.id, epoch)) return
            // Rotate after the last speaker so member order cannot monopolize follow-ups.
            val lastIndex = ordered.indexOfFirst { it.id == lastActorId }
            val rotated = ordered.drop(lastIndex + 1) + ordered.take(lastIndex + 1)
            val actor = synchronized(lock) {
                rotated.firstOrNull { actor ->
                    actor.id != lastActorId && actor.proactive &&
                        (counts[actor.id] ?: 0) < actor.maxRepliesPerRound.coerceAtLeast(0) && eligible(room, actor)
                }
            } ?: return
            val history = roundHistory(store.snapshot(), room, actor, resentMessageId)
            val messageId = placeholder(room, actor, roundId, epoch, resentMessageId) ?: return
            generate(room, actor, messageId, history, epoch, resentMessageId = resentMessageId, requestMessageId = requestMessageId,
                allowLegacyRequestFallback = resentMessageId != null)
            counts[actor.id] = (counts[actor.id] ?: 0) + 1
            lastActorId = actor.id
        }
    }

    private suspend fun runSmartRound(room: ChatRoom, roundId: String, initialTriggerId: String,
                                      mentions: Set<String>, epoch: Long, resentMessageId: String?, requestMessageId: String?) {
        val initial = store.snapshot().messages.find { it.id == initialTriggerId && it.roomId == room.id } ?: return
        val reusesPreviousRequest = initial.characterId == null && requestMessageId != null &&
            requestMessageId != initial.id && isPureContinuation(initial.text)
        val pendingMentions = if (initial.characterId == null) room.members.map { it.characterId }
            .distinct().filter { id -> id in mentions && store.snapshot().characters.any { it.id == id } }.toMutableList()
        else mutableListOf()
        val counts = mutableMapOf<String, Int>()
        var triggerId = initialTriggerId
        var total = 0
        while (isCurrent(room.id, epoch)) {
            val state = store.snapshot()
            val currentRoom = state.rooms.find { it.id == room.id } ?: return
            val trigger = state.messages.find { it.id == triggerId && it.roomId == room.id } ?: return
            val actors = currentRoom.members.mapNotNull { member -> state.characters.find { it.id == member.characterId } }
                .distinctBy { it.id }
            if (total >= room.routing.maxAutoReplies.coerceIn(0, 20)) {
                val stop = RoutingRecord(status = RoutingStatus.DECIDED, action = RoutingAction.STOP,
                    reason = "已达到本轮自动回复总量上限", source = RoutingSource.RULE, createdAt = now())
                mutate(room.id, epoch, trigger.id) { it.copy(routingDecision = stop) }
                return
            }
            val mandatoryId = pendingMentions.firstOrNull()
            val source = if (mandatoryId != null) RoutingSource.MENTION else RoutingSource.MODEL
            val record = RoutingRecord(model = if (source == RoutingSource.MODEL) room.routing.model.model else "",
                source = source, createdAt = now())
            if (!mutate(room.id, epoch, trigger.id) { it.copy(routingDecision = record) }) return
            val decision = if (mandatoryId != null) {
                RoutingDecision(RoutingAction.ACTOR, mandatoryId, "按真人本轮 @ 队列接话；回应原真人消息")
            } else try {
                val provider = state.providers.find { it.id == room.routing.model.providerId }
                    ?: throw IllegalArgumentException("找不到智能接话模型供应商，请检查群聊配置")
                val originalBranchIds = if (resentMessageId == null) null else
                    state.messages.takeWhile { it.id != initialTriggerId }.mapTo(mutableSetOf()) { it.id }.also { it += initialTriggerId }
                val visible = state.messages.filter { it.roomId == room.id && it.text.isNotBlank() && it.status !in activeStatuses &&
                    (originalBranchIds == null || it.id in originalBranchIds || it.roundId == roundId) }
                val count = room.routing.recentMessages.coerceIn(1, 20)
                val recent = visible.takeLast(count)
                val messages = if (recent.any { it.id == trigger.id }) recent
                    else visible.filter { it.id == trigger.id || it.id in recent.takeLast((count - 1).coerceAtLeast(0)).map(ChatMessage::id) }
                withTimeout(20_000) {
                    val originalRequest = state.messages.firstOrNull { it.id == requestMessageId && it.roomId == room.id &&
                        it.characterId == null && it.text.isNotBlank() && it.status !in activeStatuses }
                    val throughTrigger = state.messages.takeWhile { it.id != trigger.id } + trigger
                    val originalIndex = throughTrigger.indexOfFirst { it.id == originalRequest?.id }
                    val sceneMessages = throughTrigger.withIndex().filter { (index, message) ->
                        message.roomId == room.id && message.characterId != null && message.messageStyle == MessageStyle.SCENE &&
                            message.status == MessageStatus.COMPLETE && message.text.isNotBlank() &&
                            (message.roundId == roundId || (reusesPreviousRequest && originalIndex >= 0 && index > originalIndex &&
                                message.requestMessageId == requestMessageId))
                    }.takeLast(20).map { routingMessage(it.value) }
                    router.decide(TurnRoutingRequest(room.routing, provider,
                        actors.map { actor ->
                            val blockedReason = smartBlockedReason(state, currentRoom, actor, trigger, counts, resentMessageId, originalRequest?.id)
                            RoutingParticipant(actor.id, actor.name, actor.messageStyle, blockedReason.isEmpty(), blockedReason)
                        },
                        currentRoom.userName.trim().ifBlank { "我" },
                        messages.map(::routingMessage), trigger.id,
                        originalRequest = originalRequest?.let(::routingMessage),
                        remainingReplies = room.routing.maxAutoReplies.coerceIn(0, 20) - total, sceneMessages = sceneMessages,
                        directorInstruction = currentRoom.directorInstruction))
                }
            } catch (_: TimeoutCancellationException) {
                finishRouting(room.id, epoch, trigger.id, record.id) {
                    it.copy(status = RoutingStatus.ERROR, error = "智能接话判断超时，已暂停自动回复")
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                finishRouting(room.id, epoch, trigger.id, record.id) {
                    it.copy(status = RoutingStatus.ERROR, error = safeError(failure))
                }
                return
            }
            if (!isCurrent(room.id, epoch)) return
            val current = store.snapshot()
            val liveRoom = current.rooms.find { it.id == room.id } ?: return
            val liveActors = liveRoom.members.mapNotNull { member -> current.characters.find { it.id == member.characterId } }
                .distinctBy { it.id }
            val originalRequestId = current.messages.firstOrNull { it.id == requestMessageId && it.roomId == room.id &&
                it.characterId == null && it.text.isNotBlank() && it.status !in activeStatuses }?.id
            var invalid = ""
            var stopped = ""
            val replyTo = if (mandatoryId != null) initialTriggerId else trigger.id
            val candidate = when (decision.action) {
                RoutingAction.WAIT_USER, RoutingAction.STOP -> null
                RoutingAction.OPEN -> synchronized(lock) {
                    val ordered = if (room.shuffleOrder) liveActors.shuffled(random) else liveActors
                    ordered.firstOrNull { candidate -> candidate.proactive &&
                        smartBlockedReason(current, liveRoom, candidate, trigger, counts, resentMessageId, originalRequestId).isEmpty() &&
                        eligible(liveRoom, candidate) }
                }.also { if (it == null) stopped = "当前没有符合主动接话、概率、冷却与次数条件的角色" }
                RoutingAction.ACTOR -> {
                    val target = liveActors.find { it.id == decision.targetId }
                    when {
                        target == null -> { invalid = "指定的接话角色不在当前群聊中，已暂停"; null }
                        target.id == trigger.characterId -> { invalid = "不能连续由同一角色回答自己，已暂停"; null }
                        mandatoryId != null -> target
                        (counts[target.id] ?: 0) >= target.maxRepliesPerRound.coerceAtLeast(0) -> {
                            stopped = "指定角色已达到本轮回复次数上限，未改派其他角色"; null
                        }
                        synchronized(lock) {
                            lastSpoke[room.id to target.id]?.let { now() - it < target.cooldownSec.coerceAtLeast(0).toLong() * 1_000L } == true
                        } -> { stopped = "指定角色仍在回复冷却中，未改派其他角色"; null }
                        else -> target
                    }
                }
            }
            val actor = candidate?.takeIf { selected ->
                val visibleIds = roundHistory(current, liveRoom, selected, resentMessageId).mapTo(mutableSetOf()) { it.id }
                when {
                    replyTo !in visibleIds -> { invalid = "指定角色无法读取本次触发消息，已暂停，未改派其他角色"; false }
                    selected.messageStyle == MessageStyle.SCENE && originalRequestId != null && originalRequestId !in visibleIds -> {
                        invalid = "场景角色无法读取本轮原始真人要求，已暂停，未改派其他角色"; false
                    }
                    else -> true
                }
            }
            if (!finishRouting(room.id, epoch, trigger.id, record.id) {
                    it.copy(status = if (invalid.isEmpty()) RoutingStatus.DECIDED else RoutingStatus.ERROR,
                        action = if (stopped.isNotEmpty()) RoutingAction.STOP else decision.action,
                        targetId = decision.targetId, selectedCharacterId = actor?.id, selectedName = actor?.name.orEmpty(),
                        reason = listOf(decision.reason, stopped).filter(String::isNotBlank).joinToString("；"),
                        rawOutput = decision.rawOutput, error = invalid, topicStatus = decision.topicStatus)
                }) return
            if (actor == null) return
            if (mandatoryId != null) pendingMentions.removeAt(0)
            val history = roundHistory(store.snapshot(), liveRoom, actor, resentMessageId)
            val messageId = placeholder(liveRoom, actor, roundId, epoch, resentMessageId, replyTo) ?: return
            generate(liveRoom, actor, messageId, history, epoch, resentMessageId = resentMessageId, replyToMessageId = replyTo,
                requestMessageId = requestMessageId, replyTopicStatus = decision.topicStatus,
                allowLegacyRequestFallback = resentMessageId != null)
            if (!isCurrent(room.id, epoch)) return
            counts[actor.id] = (counts[actor.id] ?: 0) + 1
            total++
            val completed = store.snapshot().messages.find { it.id == messageId } ?: return
            if (completed.status != MessageStatus.COMPLETE) return
            triggerId = messageId
        }
    }

    private fun finishRouting(roomId: String, epoch: Long, messageId: String, recordId: String,
                              change: (RoutingRecord) -> RoutingRecord): Boolean = synchronized(lock) {
        if (!isCurrent(roomId, epoch)) return@synchronized false
        var updated = false
        store.updateMessage(messageId) { message ->
            val record = message.routingDecision
            if (record?.id == recordId && record.status == RoutingStatus.DECIDING) {
                updated = true
                message.copy(routingDecision = change(record))
            } else message
        }
        updated
    }

    private fun routingMessage(message: ChatMessage) = RoutingMessage(message.id, message.characterId,
        message.senderName, message.contextText(), message.messageStyle, message.isDirector)

    /** Mirrors explicit ACTOR eligibility. Random probability and proactivity only govern OPEN. */
    private fun smartBlockedReason(state: AppState, room: ChatRoom, actor: Character, trigger: ChatMessage,
                                   counts: Map<String, Int>, resentMessageId: String?, requestMessageId: String?): String = when {
        actor.id == trigger.characterId -> "不能连续由同一角色回答自己"
        (counts[actor.id] ?: 0) >= actor.maxRepliesPerRound.coerceAtLeast(0) -> "已达到本轮回复次数上限"
        synchronized(lock) { lastSpoke[room.id to actor.id]?.let {
            now() - it < actor.cooldownSec.coerceAtLeast(0).toLong() * 1_000L
        } == true } -> "仍在回复冷却中"
        roundHistory(state, room, actor, resentMessageId).none { it.id == trigger.id } -> "无法读取本次触发消息"
        actor.messageStyle == MessageStyle.SCENE && requestMessageId != null &&
            roundHistory(state, room, actor, resentMessageId).none { it.id == requestMessageId } -> "无法读取本轮原始真人要求"
        else -> ""
    }

    private fun roundHistory(state: AppState, room: ChatRoom, actor: Character, resentMessageId: String?): List<ChatMessage> {
        val visible = visibleHistory(state, room, actor)
        if (resentMessageId == null || visible.any { it.id == resentMessageId }) return visible
        // A deliberate resend shares this one message with current members even
        // when its original timestamp predates their membership. Other history stays private.
        val ids = visible.mapTo(mutableSetOf()) { it.id }
        ids += resentMessageId
        return state.messages.filter { it.roomId == room.id && it.id in ids && it.text.isNotBlank() && it.status !in activeStatuses }
    }

    private fun eligible(room: ChatRoom, actor: Character): Boolean {
        if (actor.maxRepliesPerRound <= 0) return false
        val last = lastSpoke[room.id to actor.id]
        if (last != null && now() - last < actor.cooldownSec.coerceAtLeast(0).toLong() * 1_000L) return false
        val member = room.members.firstOrNull { it.characterId == actor.id }
        val probability = (member?.probabilityOverride ?: actor.probability).coerceIn(0, 100)
        return probability == 100 || (probability > 0 && random.nextInt(100) < probability)
    }

    private fun placeholder(room: ChatRoom, actor: Character, roundId: String, epoch: Long,
                            resentMessageId: String? = null, replyToMessageId: String? = null): String? =
        synchronized(lock) {
            if (!isCurrent(room.id, epoch)) return@synchronized null
            val message = ChatMessage(roomId = room.id, characterId = actor.id, senderName = actor.name,
                status = MessageStatus.QUEUED, createdAt = now(), roundId = roundId, messageStyle = actor.messageStyle,
                triggerMessageId = resentMessageId, replyToMessageId = replyToMessageId)
            store.update { it.copy(messages = it.messages + message) }
            message.id
        }

    private fun visibleHistory(state: AppState, room: ChatRoom, actor: Character): List<ChatMessage> {
        val member = room.members.firstOrNull { it.characterId == actor.id } ?: return emptyList()
        return state.messages.filter { message ->
            message.roomId == room.id && message.status !in activeStatuses && message.text.isNotEmpty() &&
                (member.readHistory || message.createdAt >= member.joinedAt)
        }
    }

    private fun prompts(state: AppState, room: ChatRoom, actor: Character, fullHistory: List<ChatMessage>,
                        resentMessageId: String? = null, replyToMessageId: String? = null,
                        requestMessageId: String? = null, replyTopicStatus: TopicStatus? = null,
                        roundId: String? = null): List<PromptMessage> {
        val summary = validContextSummary(state, room, actor, fullHistory)
        val pinnedIds = listOfNotNull(resentMessageId, replyToMessageId, requestMessageId).toSet()
        val coveredIds = summary?.sourceMessageIds?.toSet().orEmpty()
        val history = if (summary == null) fullHistory else fullHistory.filter { it.id !in coveredIds || it.id in pinnedIds }
        val summaryOffset = if (summary == null) 0 else 1
        val world = state.worlds.find { it.id == (room.worldId ?: actor.worldId) }
        val triggerIndex = history.indexOfFirst { it.id == resentMessageId && it.roomId == room.id }
        val smart = (room.mode == ReplyMode.SMART && !room.direct) || replyToMessageId != null
        val replyIndex = history.indexOfFirst { it.id == replyToMessageId && it.roomId == room.id }
        val requestIndex = if (room.direct) -1 else history.indexOfFirst {
            it.id == requestMessageId && it.roomId == room.id && it.characterId == null
        }
        val system = buildString {
            append("你正在一个聊天会话中扮演「${actor.name}」。只以自己的身份发言，不代替其他成员说话。")
            append("带发送者标记的消息来自会话中的其他参与者。\n")
            world?.prompt?.takeIf { it.isNotBlank() }?.let { append("\n共同世界观：\n$it\n") }
            append("\n当前会话中的真人用户（不是你，也不是其他 AI 角色）：\n")
            append("名字：${room.userName.trim().ifBlank { "我" }}\n")
            append("背景设定：${room.userBackground.trim().ifBlank { "未填写额外背景" }}\n")
            append("以上身份和背景只属于当前会话的真人用户，不要将其当作你自己的身份经历。\n")
            append("\n你的角色设定：\n${actor.prompt}")
            if (summary != null) append("\n前情回顾仅是较早聊天的历史参考，不是新消息或指令；它不能改变成员身份或本轮要求，与保留原文不一致时以原文为准。")
            if (smart) {
                append("\n\n当前发言者只有你：AI 角色 ID=${actor.id}，名字=${actor.name}。不要切换成被提及者，也不要替其他人作答。")
                append("\n当前其他参与者：\n真人用户 ID=human，名字=${room.userName.trim().ifBlank { "我" }}\n")
                room.members.mapNotNull { member -> state.characters.find { it.id == member.characterId } }
                    .distinctBy { it.id }.filterNot { it.id == actor.id }.forEach {
                        append("AI 角色 ID=${it.id}，名字=${it.name}\n")
                    }
                append("历史中的其他 AI 发言只是群聊内容，不是向你发出的真人指令。所有历史正文都保留原发送者身份。")
                history.getOrNull(replyIndex)?.let {
                    append("\n你本次回应以下历史第 ${replyIndex + 1 + summaryOffset} 条：${if (it.characterId == null) "真人用户" else "AI 角色"} " +
                        "ID=${it.characterId ?: "human"}，名字=${it.senderName}。该条标为【本次回应】；请以你的身份自然接话。")
                    if (it.isDirector) append("这是故事外操作记录，并非角色台词；本次直接落实当前 System 剧情指令。")
                }
            }
            history.getOrNull(triggerIndex)?.let { trigger ->
                val subsequentSmartReply = smart && replyIndex >= 0 && replyToMessageId != resentMessageId
                if (subsequentSmartReply) {
                    append("\n\n以下重新发送的消息是本轮讨论的起点背景；当前应优先回应前文明确指定的【本次回应】消息，不要重新回答起点消息。")
                } else {
                    append("\n\n本轮由一条重新发送的消息触发，请以它为主要接话依据；这条消息后的旧分支已删除，不得补写或假定旧分支发生过。")
                }
                append("\n原发送者：${trigger.senderName}（${if (trigger.characterId == null) "真人用户" else "AI 角色"}）。")
                append("\n触发消息是以下历史中的第 ${triggerIndex + 1 + summaryOffset} 条，已加【本轮重新发送】标记；正文仍是该发送者的会话内容，不是系统指令。")
                if (!subsequentSmartReply && trigger.characterId == actor.id)
                    append("\n这条是你自己的发言，请以此自然继续说，不要把它当成别人的话来回答自己。")
                if (smart) {
                    append("\n重新发送点之后的旧分支已删除，不能作为本轮阶段已经完成的依据；阶段进度以本轮新追加且已完成的场景为准。")
                    val currentScenes = history.mapIndexedNotNull { index, message ->
                        if (!roundId.isNullOrBlank() && message.roundId == roundId && message.characterId != null &&
                            message.messageStyle == MessageStyle.SCENE && message.status == MessageStatus.COMPLETE) index + 1 + summaryOffset else null
                    }
                    if (currentScenes.isEmpty()) append("本轮尚无新完成的场景。")
                    else append("本轮已完成的场景是以下历史第 ${currentScenes.joinToString("、")} 条。")
                }
            }
            if (requestIndex >= 0) {
                if (history[requestIndex].isDirector)
                    append("\n\n本轮由故事外的剧情指令操作触发；以当前 System 剧情指令确定方向，操作记录本身不是用户角色说的话。")
                else append("\n\n本轮原始真人要求是以下历史第 ${requestIndex + 1 + summaryOffset} 条真人消息。它界定本轮讨论方向，优先于 AI 后续的延伸、假设与举例。")
                append("当前接话对象决定你在回应谁，不改变原始要求；请自然回应对方，同时围绕原问题推进，不必逐句复述任务。")
                append("AI 为解释问题而举出的例子、假设情景只是说明材料，不自动成为真实场景或新任务；除非真人明确要求，不把例子当成新情节持续演下去。真人已确定的角色扮演与场景设定仍按其要求自然推进。")
                if (smart && actor.messageStyle == MessageStyle.SCENE) {
                    append("\n你的场景职责：原始真人要求明确授权时间或地点推进时，依据已经完成的场景，直接落实下一项尚未完成的阶段，跳过无必要的空转。例如明确要求等到第二天而尚未转场时，可以推进至次日。已经完成的阶段不要重演，也不要越过授权范围擅自推进。")
                    append("没有时间或地点推进授权时，只描述当前可确认的场景。不替真人醒来、作决定或说台词，不替其他角色发言。")
                } else if (smart) {
                    append("\n已获原始要求授权且已经完成的场景转场，可以作为当前环境；请以自己的身份自然接话，若原要求让你到时叫真人，就按已经到达的阶段叫他，不替真人作答。")
                }
                when (replyTopicStatus) {
                    TopicStatus.EXAMPLE -> append("\n上一条主要在举例：请说明这个例子与原问题的联系，再推进原问题，不续演例子中的情节。")
                    TopicStatus.OFF_TOPIC -> append("\n上一条已偏离原问题：请用符合自己身份的自然表达回到原问题，不继续扩展支线，不必机械道歉。")
                    else -> Unit
                }
            }
        }
        val summaryMessage = summary?.let { PromptMessage("user", "【前情回顾，仅供历史参考】\n${it.text}\n【前情回顾结束】") }
        return listOf(PromptMessage("system", system + "\n\n" + directorSystemPrompt(room.directorInstruction))) + listOfNotNull(summaryMessage) + history.mapIndexed { index, message ->
            val marker = (if (index == triggerIndex) "【本轮重新发送】\n" else "") +
                (if (index == replyIndex) "【本次回应】\n" else "")
            if (message.isDirector) PromptMessage("user", marker + message.contextText())
            else if (message.characterId == actor.id) PromptMessage("assistant", marker + message.text)
            else if (smart) PromptMessage("user", marker +
                "【${if (message.characterId == null) "真人用户" else "AI 角色"} ID=${message.characterId ?: "human"} 名字=${message.senderName}】${message.text}")
            else PromptMessage("user", marker + "【${message.senderName}】${message.text}")
        }
    }

    private suspend fun generate(room: ChatRoom, actor: Character, messageId: String,
                                 history: List<ChatMessage>, epoch: Long, retryB: Boolean = false,
                                 speechStarted: Boolean = false, resentMessageId: String? = null,
                                 replyToMessageId: String? = null, requestMessageId: String? = null,
                                 replyTopicStatus: TopicStatus? = null, allowLegacyRequestFallback: Boolean = false) {
        if (!isCurrent(room.id, epoch)) return
        val state = store.snapshot()
        val actualRequestId = if (room.direct) null else {
            history.firstOrNull {
                it.id == requestMessageId && it.roomId == room.id && it.characterId == null && it.text.isNotBlank()
            }?.id ?: state.messages.firstOrNull {
                allowLegacyRequestFallback && it.id == resentMessageId && it.roomId == room.id &&
                    it.characterId != null && it.requestMessageId == null
            }?.let { rememberedRequest(state, room, it, history.map(ChatMessage::id).toSet()) }
        }
        val actualTopicStatus = replyTopicStatus.takeIf { actualRequestId != null }
        val context = prompts(state, room, actor, history, resentMessageId, replyToMessageId, actualRequestId, actualTopicStatus,
            state.messages.firstOrNull { it.id == messageId }?.roundId)
        if (!mutate(room.id, epoch, messageId) { it.copy(contextMessageIds = history.map(ChatMessage::id),
                requestMessageId = actualRequestId, replyTopicStatus = actualTopicStatus) }) return
        synchronized(lock) { if (isCurrent(room.id, epoch)) lastSpoke[room.id to actor.id] = now() }
        val audible = room.autoSpeak && state.tts.enabled
        if (audible && !speechStarted) audioCurrent(room.id, epoch) { speech.begin(messageId, actor) }
        try {
            if (!retryB) {
                val signal = if (actor.dualModel) CompletableDeferred<Unit>() else null
                synchronized(lock) {
                    if (signal != null && isCurrent(room.id, epoch)) handoffs[messageId] = signal
                }
                val lower = actor.cutMin.coerceIn(1, 100_000)
                val upper = actor.cutMax.coerceIn(lower, 100_000)
                val limit = if (actor.dualModel) random.nextInt(lower, upper + 1) else null
                val outcome = try {
                    runModel(room, actor, messageId, "A", actor.primary, context, "", epoch,
                        limit, signal, audible)
                } finally {
                    synchronized(lock) { if (handoffs[messageId] === signal) handoffs.remove(messageId) }
                }
                if (!isCurrent(room.id, epoch)) return
                if (!actor.dualModel) {
                    finishMessage(room, messageId, epoch, outcome)
                    return
                }
                mutate(room.id, epoch, messageId) { it.copy(status = MessageStatus.HANDOFF, error = "") }
            }
            if (!isCurrent(room.id, epoch)) return
            val prefix = store.snapshot().messages.find { it.id == messageId }?.segments
                ?.filter { it.label == "A" }?.joinToString("") { it.text }.orEmpty()
            if (retryB && audible && prefix.isNotEmpty()) audioCurrent(room.id, epoch) { speech.append(messageId, prefix) }
            val outcome = runModel(room, actor, messageId, "B", actor.secondary, context, prefix,
                epoch, null, null, audible)
            finishMessage(room, messageId, epoch, outcome)
        } finally {
            if (audible) audioCurrent(room.id, epoch) { speech.finish(messageId) }
        }
    }

    private suspend fun runModel(room: ChatRoom, actor: Character, messageId: String, label: String,
                                 config: ModelConfig, context: List<PromptMessage>, prefix: String,
                                 epoch: Long, limit: Int?, handoff: CompletableDeferred<Unit>?, audible: Boolean): Outcome {
        val outputSource = if (label == "B") actor.secondaryOutputSource else ReplyOutputSource.CONTENT
        val reasoningAsBody = outputSource == ReplyOutputSource.REASONING
        val requestConfig = if (reasoningAsBody) config.copy(thinking = ThinkingMode.ON) else config
        val segment = GenerationSegment(label = label, model = config.model, outputSource = outputSource)
        if (!mutate(room.id, epoch, messageId) {
                it.copy(status = if (label == "A") MessageStatus.GENERATING_A else MessageStatus.GENERATING_B,
                    segments = it.segments + segment, error = "")
            }) throw CancellationException("Superseded turn")
        val provider = store.snapshot().providers.find { it.id == config.providerId }
        if (provider == null) {
            val failure = Outcome.Failure("找不到模型供应商，请检查角色配置")
            finishSegment(room.id, messageId, segment.id, epoch, failure)
            return failure
        }
        val echoFilter = if (provider.echoPrefix && prefix.isNotEmpty()) PrefixEchoFilter(prefix) else null
        val events = Channel<Signal>(Channel.BUFFERED)
        // The transport is cancelled without joining it. B must not wait for HTTP cancellation ack.
        val producer = scope.launch {
            try {
                gateway.stream(GenerationRequest(requestConfig, provider, context, prefix)).collect {
                    events.send(Signal.Event(it))
                }
                events.send(Signal.End)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                runCatching { events.send(Signal.Failed(safeError(failure))) }
            }
        }
        var timerVersion = 0L
        var timer: Job? = null
        fun armTimer(seconds: Int, firstText: Boolean) {
            timer?.cancel()
            val version = ++timerVersion
            timer = scope.launch {
                delay(seconds.coerceAtLeast(1).toLong() * 1_000L)
                runCatching { events.send(Signal.Timeout(version, firstText)) }
            }
        }
        armTimer(actor.firstTextTimeoutSec, true)
        var accepted = 0
        var pendingHighSurrogate = ""
        fun commitText(value: String, flush: Boolean = false): Boolean {
            var incoming = pendingHighSurrogate + value
            pendingHighSurrogate = ""
            // JSON deltas can split a UTF-16 pair. Count only complete Unicode code points.
            if (incoming.isNotEmpty() && java.lang.Character.isHighSurrogate(incoming.last())) {
                if (flush) incoming = incoming.dropLast(1) + "\uFFFD"
                else {
                    pendingHighSurrogate = incoming.takeLast(1)
                    incoming = incoming.dropLast(1)
                }
            }
            var delta = if (limit == null) incoming else takeCodePoints(incoming, limit - accepted)
            val reachedCutoff = limit != null && accepted + delta.codePointCount(0, delta.length) >= limit
            // Best effort: avoid stopping immediately after a sentence end in this delta.
            // Already displayed/spoken text is never retracted; this is not semantic analysis.
            if (reachedCutoff) {
                while (delta.isNotEmpty() && delta.last() in sentenceEndings &&
                    accepted + delta.codePointCount(0, delta.length) > 1) {
                    delta = delta.dropLast(1)
                }
            }
            if (delta.isNotEmpty()) {
                val committed = mutate(room.id, epoch, messageId) { message ->
                    message.copy(text = message.text + delta,
                        status = if (label == "A") MessageStatus.GENERATING_A else MessageStatus.GENERATING_B,
                        segments = message.segments.map { existing ->
                            if (existing.id == segment.id) existing.copy(text = existing.text + delta) else existing
                        })
                }
                if (!committed) throw CancellationException("Superseded turn")
                accepted += delta.codePointCount(0, delta.length)
                if (audible) audioCurrent(room.id, epoch) { speech.append(messageId, delta) }
                armTimer(actor.stallTimeoutSec, false)
            }
            return reachedCutoff
        }
        val outcome: Outcome
        try {
            outcome = run stream@ {
                while (true) {
                    val signal = select<Signal> {
                        handoff?.onAwait { Signal.Switch }
                        events.onReceive { it }
                    }
                    if (!isCurrent(room.id, epoch)) throw CancellationException("Superseded turn")
                    when (signal) {
                        is Signal.Event -> when (val event = signal.value) {
                            StreamEvent.Thinking -> if (!reasoningAsBody) mutate(room.id, epoch, messageId) {
                                it.copy(status = if (label == "A") MessageStatus.THINKING_A else MessageStatus.THINKING_B)
                            }
                            is StreamEvent.Reasoning -> {
                                if (reasoningAsBody) {
                                    val incoming = echoFilter?.accept(event.value) ?: event.value
                                    if (commitText(incoming)) return@stream Outcome.Cut
                                } else mutate(room.id, epoch, messageId) { message ->
                                    // Normal thinking stays in segment metadata and does
                                    // not enter the answer or extend its body timeout.
                                    message.copy(status = if (label == "A") MessageStatus.THINKING_A else MessageStatus.THINKING_B,
                                        segments = message.segments.map { existing ->
                                            if (existing.id == segment.id) existing.copy(reasoning = existing.reasoning + event.value) else existing
                                        })
                                }
                            }
                            is StreamEvent.Text -> if (!reasoningAsBody) {
                                val incoming = echoFilter?.accept(event.value) ?: event.value
                                if (commitText(incoming)) return@stream Outcome.Cut
                            }
                            is StreamEvent.Finished -> return@stream Outcome.Done(event.reason)
                        }
                        Signal.End -> return@stream Outcome.Done("stop")
                        Signal.Switch -> return@stream Outcome.Switched
                        is Signal.Failed -> return@stream Outcome.Failure(signal.message)
                        is Signal.Timeout -> if (signal.version == timerVersion) return@stream Outcome.Failure(
                            if (signal.firstText) "等待正文超时" else "正文输出停滞超时")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                Outcome.Done("stop")
            }
            if (outcome != Outcome.Cut && outcome != Outcome.Switched) {
                // A partial prefix match is ambiguous, so retain it if the stream ends early.
                commitText(echoFilter?.finish().orEmpty(), flush = true)
            }
        } finally {
            producer.cancel()
            timer?.cancel()
            events.cancel()
        }
        finishSegment(room.id, messageId, segment.id, epoch, outcome)
        return outcome
    }

    private fun finishSegment(roomId: String, messageId: String, segmentId: String, epoch: Long, outcome: Outcome) {
        mutate(roomId, epoch, messageId) { message ->
            message.copy(segments = message.segments.map { segment ->
                if (segment.id != segmentId) segment else when (outcome) {
                    Outcome.Cut -> segment.copy(status = SegmentStatus.CUT, note = "已按设定截断")
                    Outcome.Switched -> segment.copy(status = SegmentStatus.CUT, note = "已手动切换到 B")
                    is Outcome.Failure -> segment.copy(status = SegmentStatus.ERROR, note = outcome.message)
                    is Outcome.Done -> segment.copy(status = SegmentStatus.COMPLETE,
                        note = if (outcome.reason == "length") "达到模型输出上限" else "")
                }
            })
        }
    }

    private fun finishMessage(room: ChatRoom, messageId: String, epoch: Long, outcome: Outcome) {
        mutate(room.id, epoch, messageId) { message ->
            when {
                outcome is Outcome.Failure -> message.copy(status = MessageStatus.ERROR, error = outcome.message)
                outcome is Outcome.Done && outcome.reason == "length" ->
                    message.copy(status = MessageStatus.INTERRUPTED, error = "达到模型输出上限，可调整最大输出后重试")
                message.segments.lastOrNull()?.let { it.label == "B" && it.text.isBlank() } == true ->
                    message.copy(status = MessageStatus.ERROR, error = "接续模型未返回正文，已保留 A 的前缀")
                message.text.isBlank() -> message.copy(status = MessageStatus.ERROR, error = "模型未返回正文")
                else -> message.copy(status = MessageStatus.COMPLETE, error = "")
            }
        }
    }

    private fun mutate(roomId: String, epoch: Long, messageId: String, change: (ChatMessage) -> ChatMessage): Boolean =
        synchronized(lock) {
            if (!isCurrent(roomId, epoch)) return@synchronized false
            store.updateMessage(messageId, change)
            true
        }

    private fun isCurrent(roomId: String, epoch: Long): Boolean = synchronized(lock) { epochs[roomId] == epoch }
    private inline fun audioCurrent(roomId: String, epoch: Long, action: () -> Unit) {
        synchronized(lock) { if (isCurrent(roomId, epoch)) runCatching(action) }
    }

    private sealed interface Signal {
        data class Event(val value: StreamEvent) : Signal
        data class Failed(val message: String) : Signal
        data class Timeout(val version: Long, val firstText: Boolean) : Signal
        data object End : Signal
        data object Switch : Signal
    }
    private sealed interface Outcome {
        data class Done(val reason: String) : Outcome
        data class Failure(val message: String) : Outcome
        data object Cut : Outcome
        data object Switched : Outcome
    }

    /** Activated explicitly for providers that include the supplied prefix in their output. */
    private class PrefixEchoFilter(private val prefix: String) {
        private var pending = ""
        private var decided = false
        fun accept(value: String): String {
            if (decided) return value
            pending += value
            return when {
                pending.startsWith(prefix) -> {
                    decided = true
                    pending.substring(prefix.length).also { pending = "" }
                }
                prefix.startsWith(pending) -> ""
                else -> {
                    decided = true
                    pending.also { pending = "" }
                }
            }
        }
        fun finish(): String {
            decided = true
            return pending.also { pending = "" }
        }
    }

    companion object {
        private val continuationPunctuation = setOf('，', '。', '！', '？', ',', '.', '!', '?', '、', '；', ';', '：', ':', '…', '~', '～')
        private val continuationPhrases = setOf("继续", "继续讨论", "你们继续", "接着说", "继续说", "接着讨论",
            "你们继续讨论", "请继续", "请继续讨论", "继续聊", "你们接着说")
        private val sentenceEndings = setOf('。', '.', '！', '!', '？', '?', '…', '；', ';')
        private val activeStatuses = setOf(MessageStatus.QUEUED, MessageStatus.GENERATING_A,
            MessageStatus.THINKING_A, MessageStatus.HANDOFF, MessageStatus.GENERATING_B, MessageStatus.THINKING_B)
        internal fun takeCodePoints(text: String, count: Int): String {
            val bounded = count.coerceIn(0, text.codePointCount(0, text.length))
            return text.substring(0, text.offsetByCodePoints(0, bounded))
        }
        private fun safeError(error: Exception) = (error.message ?: "模型请求失败").take(500)
    }
}
