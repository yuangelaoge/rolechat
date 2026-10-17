package cn.rolechat.app.speech

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DoubaoVoicesTest {
    private val cancan = "zh_female_cancan_mars_bigtts"
    private val vv = "zh_female_vv_uranus_bigtts"

    @Test fun `resource aliases resolve explicit versions and supported defaults`() {
        listOf("seed-tts-1.0", "seed-tts-1.0-concurr", "volc.service_type.10029", "volc.service_type.10048").forEach { resource ->
            assertEquals("1.0", DoubaoVoices.resourceVersion(resource))
            assertEquals(cancan, DoubaoVoices.defaultVoice(resource))
            assertNull(DoubaoVoices.validationError(resource, cancan))
            assertNotNull(DoubaoVoices.validationError(resource, vv))
        }
        assertEquals("2.0", DoubaoVoices.resourceVersion("  seed-tts-2.0\n"))
        assertEquals(vv, DoubaoVoices.defaultVoice("seed-tts-2.0"))
        assertNull(DoubaoVoices.validationError("seed-tts-2.0", vv))
        assertNull(DoubaoVoices.resourceVersion("seed-icl-2.0"))
        assertNull(DoubaoVoices.resourceVersion("future-tts-2.0-concurr"))
        assertEquals("", DoubaoVoices.defaultVoice("future-resource"))
    }

    @Test fun `screenshot combination is rejected before request construction with useful correction`() {
        val error = DoubaoVoices.validationError("seed-tts-2.0", cancan)!!
        assertTrue(error.contains("2.0"))
        assertTrue(error.contains("1.0"))
        assertTrue(error.contains("灿灿"))
        assertTrue(error.contains("匹配音色"))
        assertThrows(IllegalArgumentException::class.java) { resolveDoubaoVoice("seed-tts-2.0", cancan) }
        assertEquals(DoubaoVoiceSelection("seed-tts-1.0", cancan), resolveDoubaoVoice("  seed-tts-1.0\n", " $cancan "))
    }

    @Test fun `known current voices use verified IDs rather than invented name or suffix mappings`() {
        val expected = mapOf(
            "Vivi 2.0" to vv,
            "小何 2.0" to "zh_female_xiaohe_uranus_bigtts",
            "云舟 2.0" to "zh_male_m191_uranus_bigtts",
            "小天 2.0" to "zh_male_taocheng_uranus_bigtts",
            "知性灿灿 2.0" to "zh_female_cancan_uranus_bigtts"
        )
        expected.forEach { (name, id) ->
            assertEquals(id, DoubaoVoices.builtIn.single { it.name == name }.id)
            assertNull(DoubaoVoices.validationError("seed-tts-2.0", id))
            assertNotNull(DoubaoVoices.validationError("seed-tts-1.0", id))
        }
    }

    @Test fun `unknown custom voices and resources remain usable without suffix guessing`() {
        listOf("custom-voice", "S_private_voice", "unknown_mars_bigtts", "unknown_uranus_bigtts").forEach { id ->
            assertNull(DoubaoVoices.validationError("seed-tts-1.0", id))
            assertNull(DoubaoVoices.validationError("seed-tts-2.0", id))
        }
        assertNull(DoubaoVoices.validationError("new-custom-resource", vv))
        assertNotNull(DoubaoVoices.validationError("", vv))
        assertNotNull(DoubaoVoices.validationError("seed-tts-2.0", " \n"))
    }

    @Test fun `catalogue supports the same ID on multiple versions without losing offline restrictions`() {
        val addedVersion = listOf(DoubaoVoice(cancan, "同一ID的新版", "seed-tts-2.0"))
        assertNull(DoubaoVoices.validationError("seed-tts-1.0", cancan, addedVersion))
        assertNull(DoubaoVoices.validationError("seed-tts-2.0", cancan, addedVersion))
        assertNotNull(DoubaoVoices.validationError("seed-tts-2.0", cancan, emptyList()))
        val oneId = listOf(
            DoubaoVoice("multi", "旧版仅单向", "seed-tts-1.0", bidirectional = false),
            DoubaoVoice("multi", "新版支持双向", "seed-tts-2.0")
        )
        assertTrue(DoubaoVoices.validationError("seed-tts-1.0", "multi", oneId)!!.contains("仅支持单向流"))
        assertNull(DoubaoVoices.validationError("seed-tts-2.0", "multi", oneId))
    }

    @Test fun `known unidirectional voices cannot enter bidirectional sessions even with incomplete refreshed metadata`() {
        val sven = "de_male_sven_uranus_bigtts"
        assertFalse(DoubaoVoices.builtIn.single { it.id == sven }.bidirectional)
        assertTrue(DoubaoVoices.validationError("seed-tts-2.0", sven)!!.contains("仅支持单向流"))
        val defaultedApiRecord = listOf(DoubaoVoice(sven, "Sven", "seed-tts-2.0"))
        assertNotNull(DoubaoVoices.validationError("seed-tts-2.0", sven, defaultedApiRecord))
        assertNotNull(DoubaoVoices.validationError("custom-resource", sven))
    }

    @Test fun `cached catalogue preserves multiple versions and bidirectional restrictions`() {
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<List<DoubaoVoice>>(json.encodeToString(DoubaoVoices.builtIn))
        assertEquals(DoubaoVoices.builtIn, restored)
        assertTrue(restored.any { !it.bidirectional })
    }

    @Test fun `only explicit resource voice mismatch enriches generic service error and retains its code`() {
        val error = doubaoServiceError(55_000_000,
            "[resource_id] resource ID is mismatched with voice type", "seed-tts-2.0", cancan)
        assertTrue(error.contains("55000000"))
        assertTrue(error.contains("seed-tts-2.0"))
        assertTrue(error.contains(cancan))
        assertTrue(error.contains("不匹配"))
        assertEquals("语音服务错误 55000000：quota unavailable", doubaoServiceError(55_000_000,
            "quota unavailable", "seed-tts-2.0", cancan))
        assertEquals("语音服务错误 45000000：resource ID does not match voice", doubaoServiceError(45_000_000,
            "resource ID does not match voice", "seed-tts-2.0", cancan))
    }
}
