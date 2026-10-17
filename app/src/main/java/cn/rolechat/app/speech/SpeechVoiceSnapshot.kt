package cn.rolechat.app.speech

import cn.rolechat.core.Character
import cn.rolechat.core.SpeechVoiceSnapshot
import cn.rolechat.core.TtsEngine
import cn.rolechat.core.TtsSettings

/** The effective request values for this attempt, without credentials or mutable configuration. */
fun speechVoiceSnapshot(settings: TtsSettings, character: Character): SpeechVoiceSnapshot = when (settings.engine) {
    TtsEngine.DOUBAO -> SpeechVoiceSnapshot(settings.engine, settings.resourceId.trim(), character.voice.trim())
    TtsEngine.SILICONFLOW -> {
        val model = character.siliconModel.ifBlank { settings.siliconModel }.trim()
        val voice = character.siliconVoice.ifBlank { settings.siliconVoice }.trim()
        SpeechVoiceSnapshot(settings.engine, model,
            if (model.isBlank() || voice.isBlank()) voice else SiliconFlowSpeechClient.resolvedVoice(model, voice))
    }
}
