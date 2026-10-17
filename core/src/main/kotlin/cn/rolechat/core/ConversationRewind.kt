package cn.rolechat.core

/** Stored list order is authoritative, including imported or equal timestamps. */
fun rewindConversation(state: AppState, messageId: String): AppState {
    val index = state.messages.indexOfFirst { it.id == messageId }
    if (index < 0) return state
    val target = state.messages[index]
    val removed = state.messages.drop(index + 1).filter { it.roomId == target.roomId }
    val removedIds = removed.mapTo(mutableSetOf()) { it.id }
    val firstRemovedDirector = removed.firstOrNull { it.isDirector }
    val precedingDirector = state.messages.take(index + 1).lastOrNull { it.roomId == target.roomId && it.isDirector }
    return state.copy(
        rooms = state.rooms.map { room ->
            if (room.id != target.roomId || firstRemovedDirector == null) room
            else room.copy(directorInstruction = firstRemovedDirector.previousDirectorInstruction
                ?: precedingDirector?.let { it.directorInstructionValue ?: it.text.takeUnless { text -> text == "已清空剧情指令" } }.orEmpty())
        },
        // Even a summary of earlier messages may have read later messages for style.
        // Rebuild this room from retained originals rather than reusing future-informed memory.
        contextSummaries = state.contextSummaries.filterNot {
            it.roomId == target.roomId || it.sourceMessageIds.any { id -> id in removedIds }
        },
        messages = state.messages.filterNot { it.id in removedIds }.map { message ->
            val request = message.requestMessageId?.takeUnless { it in removedIds }
            message.copy(contextMessageIds = message.contextMessageIds.filterNot { it in removedIds },
                triggerMessageId = message.triggerMessageId?.takeUnless { it in removedIds },
                replyToMessageId = message.replyToMessageId?.takeUnless { it in removedIds },
                requestMessageId = request,
                replyTopicStatus = if (message.requestMessageId != null && request == null) null else message.replyTopicStatus,
                speechPreparation = message.speechPreparation?.takeUnless { it.previousMessageId in removedIds })
        }
    )
}
