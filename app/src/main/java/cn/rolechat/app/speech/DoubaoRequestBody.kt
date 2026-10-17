package cn.rolechat.app.speech

import cn.rolechat.core.TtsEngine
import cn.rolechat.core.TtsSettings
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** V3 synthesis settings apply at StartSession; TaskRequest carries only spoken text. */
internal fun doubaoRequestBody(
    event: Int,
    voice: String,
    text: String,
    sampleRate: Int,
    settings: TtsSettings,
    instruction: String = ""
): String = buildJsonObject {
    putJsonObject("user") { put("uid", "rolechat-android") }
    put("event", event)
    put("namespace", "BidirectionalTTS")
    putJsonObject("req_params") {
        put("speaker", voice)
        put("text", text)
        putJsonObject("audio_params") {
            put("format", "pcm")
            put("sample_rate", sampleRate)
        }
        if (event == DoubaoProtocol.START_SESSION && settings.engine == TtsEngine.DOUBAO &&
            settings.resourceId.trim() == "seed-tts-2.0" && instruction.isNotBlank()
        ) {
            // additions is a JSON string in the native V3 API, not an inline text tag.
            put("additions", buildJsonObject {
                putJsonArray("context_texts") { add(instruction.trim()) }
            }.toString())
        }
    }
}.toString()
