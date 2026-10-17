package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.SpeechPreparationStatus
import cn.rolechat.core.SpeechVoiceSnapshot

/** Only a past voice instruction is carried forward; the previous dialogue is never copied. */
data class PreviousSpeechInstruction(val messageId: String, val instruction: String)

/**
 * The persisted message order is the boundary even when an old message is read again.
 * Preparation runs in utterance order, so an earlier successful preparation is available
 * before its playback ends without letting a later message influence a historical reply.
 */
fun previousSpeechInstruction(
    state: AppState,
    messageId: String,
    voice: SpeechVoiceSnapshot
): PreviousSpeechInstruction? {
    val currentIndex = state.messages.indexOfFirst { it.id == messageId }
    if (currentIndex <= 0 || voice.voiceId.isBlank()) return null
    val current = state.messages[currentIndex]
    val characterId = current.characterId ?: return null
    if (state.rooms.none { it.id == current.roomId } || state.characters.none { it.id == characterId }) return null
    for (index in currentIndex - 1 downTo 0) {
        val candidate = state.messages[index]
        if (candidate.roomId != current.roomId || candidate.characterId != characterId) continue
        val preparation = candidate.speechPreparation ?: continue
        if (preparation.status != SpeechPreparationStatus.COMPLETE || preparation.modelInvoked != true || !preparation.continuityEligible ||
            preparation.voice != voice || preparation.text.isBlank()) continue
        val instruction = preparation.instruction.trim()
        if (instruction.isNotEmpty() && instruction.length <= 1000) {
            return PreviousSpeechInstruction(candidate.id, instruction)
        }
    }
    return null
}
