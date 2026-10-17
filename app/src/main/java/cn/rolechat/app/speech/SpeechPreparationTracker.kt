package cn.rolechat.app.speech

import cn.rolechat.core.SpeechPreparationRecord
import cn.rolechat.core.SpeechPreparationStatus
import cn.rolechat.core.SpeechVoiceSnapshot
import cn.rolechat.core.StateStore

/** An attempt ID prevents late events from rewriting edited/regenerated messages. */
internal class SpeechPreparationTracker(private val store: StateStore, private val messageId: String, model: String,
    voice: SpeechVoiceSnapshot? = null) {
    private val record = SpeechPreparationRecord(model = model, voice = voice, modelInvoked = false)
    init { store.updateMessage(messageId) { it.copy(speechPreparation = record) } }
    private fun change(update: (SpeechPreparationRecord) -> SpeechPreparationRecord) {
        store.updateMessage(messageId) { message ->
            val current = message.speechPreparation
            if (current?.id == record.id && current.status == SpeechPreparationStatus.EXTRACTING)
                message.copy(speechPreparation = update(current)) else message
        }
    }
    fun modelStarted() = change { it.copy(modelInvoked = true) }
    fun previousInstruction(instruction: String, messageId: String? = null) = change {
        it.copy(previousInstruction = instruction.trim().take(1000),
            previousMessageId = messageId?.takeIf { id -> id.isNotBlank() && id.length <= 1024 })
    }
    fun output(raw: String, reasoning: String) = change {
        it.copy(rawOutput = raw.take(32 * 1024), reasoning = reasoning.take(16 * 1024))
    }
    fun complete(result: PreparedDialogue) = change {
        it.copy(text = result.text, instruction = result.instruction,
            status = if (result.text.isBlank()) SpeechPreparationStatus.EMPTY else SpeechPreparationStatus.COMPLETE)
    }
    fun fail(error: String) = change { it.copy(status = SpeechPreparationStatus.ERROR, error = error.take(500)) }
    fun cancel() = change {
        it.copy(status = SpeechPreparationStatus.CANCELLED, error = "本次语音前置处理已停止", continuityEligible = false)
    }
    /** A prepared but unheard utterance must not set the mood of a later replay. */
    fun discardContinuity() {
        store.updateMessage(messageId) { message ->
            val current = message.speechPreparation
            if (current?.id == record.id) message.copy(speechPreparation = current.copy(continuityEligible = false)) else message
        }
    }
}
