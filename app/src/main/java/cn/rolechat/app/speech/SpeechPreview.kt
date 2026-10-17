package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.Character
import java.util.UUID

/** A transient request: no room membership, saved character, or persisted TTS toggle is changed. */
internal data class SpeechPreview(
    val messageId: String,
    val snapshot: AppState,
    val character: Character,
    val text: String
)

internal fun prepareSpeechPreview(snapshot: AppState, character: Character): SpeechPreview {
    val name = character.name.trim().ifBlank { character.id }
    return SpeechPreview(
        messageId = "voice-preview-${UUID.randomUUID()}",
        snapshot = snapshot.copy(tts = snapshot.tts.copy(
            enabled = true,
            dialogueExtraction = snapshot.tts.dialogueExtraction.copy(enabled = false)
        )),
        character = character,
        text = "你好，我是${name}，很高兴见到你。"
    )
}
