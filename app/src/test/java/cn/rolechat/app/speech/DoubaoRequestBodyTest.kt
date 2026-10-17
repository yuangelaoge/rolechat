package cn.rolechat.app.speech

import cn.rolechat.core.TtsEngine
import cn.rolechat.core.TtsSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DoubaoRequestBodyTest {
    private val settings = TtsSettings(engine = TtsEngine.DOUBAO, resourceId = "seed-tts-2.0")
    private val voice = "zh_female_vv_uranus_bigtts"

    @Test fun `synthesis 2 instruction is a single context string only at StartSession`() {
        val instruction = "用温柔、略带迟疑的语气说，\"你好\"前稍作停顿。"
        val request = Json.parseToJsonElement(doubaoRequestBody(
            DoubaoProtocol.START_SESSION, voice, "", 24_000,
            settings.copy(resourceId = "  seed-tts-2.0  "), instruction
        )).jsonObject
        val params = request.getValue("req_params").jsonObject
        assertEquals(DoubaoProtocol.START_SESSION.toString(), request.getValue("event").jsonPrimitive.content)
        assertEquals("", params.getValue("text").jsonPrimitive.content)
        assertEquals(voice, params.getValue("speaker").jsonPrimitive.content)
        assertEquals("pcm", params.getValue("audio_params").jsonObject.getValue("format").jsonPrimitive.content)
        assertEquals("24000", params.getValue("audio_params").jsonObject.getValue("sample_rate").jsonPrimitive.content)
        val encodedAdditions = params.getValue("additions").jsonPrimitive
        assertTrue(encodedAdditions.isString)
        val additions = Json.parseToJsonElement(encodedAdditions.content).jsonObject
        assertEquals(setOf("context_texts"), additions.keys)
        assertEquals(listOf(instruction), additions.getValue("context_texts").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(params.containsKey("model"))
        assertFalse(params.containsKey("section_id"))
        assertFalse(params.containsKey("use_tag_parser"))
    }

    @Test fun `TaskRequest carries extracted dialogue without the instruction or raw result JSON`() {
        val dialogue = "你好，我们慢慢说。"
        val instruction = "压低音量，放慢语速。"
        val request = Json.parseToJsonElement(doubaoRequestBody(
            DoubaoProtocol.TASK_REQUEST, voice, dialogue, 24_000, settings, instruction
        )).jsonObject
        val params = request.getValue("req_params").jsonObject
        assertEquals(dialogue, params.getValue("text").jsonPrimitive.content)
        assertFalse(params.containsKey("additions"))
        assertFalse(request.toString().contains(instruction))
        assertFalse(request.toString().contains("instruction"))
        assertFalse(request.toString().contains("context_texts"))
    }

    @Test fun `other resources and engines ignore instructions without changing the spoken text`() {
        val cases = listOf(
            settings.copy(resourceId = "seed-tts-1.0"),
            settings.copy(resourceId = "seed-tts-1.0-concurr"),
            settings.copy(resourceId = "seed-icl-2.0"),
            settings.copy(engine = TtsEngine.SILICONFLOW)
        )
        cases.forEach { setting ->
            val matchingVoice = when (DoubaoVoices.resourceVersion(setting.resourceId)) {
                "1.0" -> "zh_female_cancan_mars_bigtts"
                "2.0" -> voice
                else -> "S_custom-cloned-voice"
            }
            val params = Json.parseToJsonElement(doubaoRequestBody(
                DoubaoProtocol.START_SESSION, matchingVoice, "正常对白。", 24_000, setting, "开心一点。"
            )).jsonObject.getValue("req_params").jsonObject
            assertEquals("正常对白。", params.getValue("text").jsonPrimitive.content)
            assertFalse(params.containsKey("additions"))
        }
    }

    @Test fun `missing or blank instruction does not add any context or tag parsing parameters`() {
        listOf("", " \n\t ").forEach { instruction ->
            val params = Json.parseToJsonElement(doubaoRequestBody(
                DoubaoProtocol.START_SESSION, voice, "", 24_000, settings, instruction
            )).jsonObject.getValue("req_params").jsonObject
            assertEquals(setOf("speaker", "text", "audio_params"), params.keys)
        }
    }
}
