package cn.rolechat.app.speech

import kotlinx.serialization.Serializable

@Serializable data class DoubaoVoice(
    val id: String,
    val name: String,
    val resourceId: String,
    val description: String = "",
    val bidirectional: Boolean = true
)

/** A small offline catalogue; versions/capabilities are explicit, never inferred from IDs. */
object DoubaoVoices {
    const val sourceUrl = "https://docs.volcengine.com/docs/DoubaoVoice/Tonelist-1?lang=zh"
    const val verifiedOn = "2026-10-17"

    // Checked against the official 1.0, 2.0 and foreign-language tables above.
    val builtIn: List<DoubaoVoice> = listOf(
        DoubaoVoice("zh_female_cancan_mars_bigtts", "灿灿 / Shiny", "seed-tts-1.0", "中文、英语"),
        DoubaoVoice("zh_female_shuangkuaisisi_moon_bigtts", "爽快思思 / Skye", "seed-tts-1.0", "中文、英语"),
        DoubaoVoice("zh_male_wennuanahu_moon_bigtts", "温暖阿虎 / Alvin", "seed-tts-1.0", "中文、英语"),
        DoubaoVoice("zh_male_shaonianzixin_moon_bigtts", "少年梓辛 / Brayan", "seed-tts-1.0", "中文、英语"),
        DoubaoVoice("zh_female_vv_uranus_bigtts", "Vivi 2.0", "seed-tts-2.0", "中文及多语种，支持语音指令"),
        DoubaoVoice("zh_female_xiaohe_uranus_bigtts", "小何 2.0", "seed-tts-2.0", "中文及多语种，支持语音指令"),
        DoubaoVoice("zh_male_m191_uranus_bigtts", "云舟 2.0", "seed-tts-2.0", "中文及多语种，支持语音指令"),
        DoubaoVoice("zh_male_taocheng_uranus_bigtts", "小天 2.0", "seed-tts-2.0", "中文及多语种，支持语音指令"),
        DoubaoVoice("zh_female_cancan_uranus_bigtts", "知性灿灿 2.0", "seed-tts-2.0", "中文及多语种，支持语音指令"),
        DoubaoVoice("de_male_sven_uranus_bigtts", "Sven", "seed-tts-2.0", "德语；仅支持单向流", bidirectional = false)
    )

    fun resourceVersion(resourceId: String): String? = when (resourceId.trim()) {
        "seed-tts-1.0", "seed-tts-1.0-concurr", "volc.service_type.10029", "volc.service_type.10048" -> "1.0"
        "seed-tts-2.0" -> "2.0"
        else -> null
    }

    fun validationError(resourceId: String, voiceId: String, voices: List<DoubaoVoice> = builtIn): String? {
        val resource = resourceId.trim()
        val id = voiceId.trim()
        if (resource.isBlank()) return "请先在语音设置中填写资源 ID"
        if (id.isBlank()) return "请先选择或填写角色音色"
        // Keep offline restrictions when a refresh omits a known entry. A refreshed
        // catalogue may add another supported version of the same voice ID.
        val known = (builtIn + voices).filter { it.id.trim() == id }
        if (known.isEmpty()) return null // Custom and newly released voices remain usable.
        val version = resourceVersion(resource)
        val compatible = if (version == null) known else known.filter { resourceVersion(it.resourceId) == version }
        if (compatible.isNotEmpty() && compatible.all { !it.bidirectional })
            return "「${compatible.first().name}」仅支持单向流，当前使用双向流语音，请选择支持双向流的音色"
        if (version == null) return null // Unknown resource IDs must not be guessed from their spelling.
        if (compatible.isEmpty()) {
            val required = known.mapNotNull { resourceVersion(it.resourceId) }.distinct()
            if (required.isEmpty()) return null
            return "当前资源为 $version，音色「${known.first().name}」属于 ${required.joinToString(" / ")}；请选择匹配音色，或在语音设置中切换资源版本"
        }
        // A confirmed single-direction restriction cannot be undone by an API
        // response which omits the capability field and therefore defaults to true.
        if (compatible.any { !it.bidirectional })
            return "「${compatible.first().name}」仅支持单向流，当前使用双向流语音，请选择支持双向流的音色"
        return null
    }

    fun defaultVoice(resourceId: String): String = when (resourceVersion(resourceId)) {
        "1.0" -> "zh_female_cancan_mars_bigtts"
        "2.0" -> "zh_female_vv_uranus_bigtts"
        else -> ""
    }
}

/** The validated pair is frozen before credentials are read or a network request is queued. */
internal data class DoubaoVoiceSelection(val resourceId: String, val voiceId: String)

internal fun resolveDoubaoVoice(
    resourceId: String, voiceId: String, voices: List<DoubaoVoice> = DoubaoVoices.builtIn
): DoubaoVoiceSelection {
    val error = DoubaoVoices.validationError(resourceId, voiceId, voices)
    require(error == null) { error ?: "语音资源与音色配置无效" }
    return DoubaoVoiceSelection(resourceId.trim(), voiceId.trim())
}

/** Do not label every generic 55000000 service failure as a voice/version mismatch. */
internal fun doubaoServiceError(code: Int?, message: String, resourceId: String, voiceId: String): String {
    val normalized = message.lowercase().replace(Regex("[-_\\s]"), "")
    val mismatch = (normalized.contains("resourceid") || normalized.contains("资源")) &&
        (normalized.contains("voice") || normalized.contains("speaker") || normalized.contains("音色")) &&
        (normalized.contains("mismatch") || normalized.contains("notmatch") || normalized.contains("不匹配"))
    val prefix = if (code == null) "语音服务错误" else "语音服务错误 $code"
    return if (code == 55_000_000 && mismatch)
        "$prefix：资源 ID「${resourceId.trim()}」与音色「${voiceId.trim()}」不匹配，请在角色声音页选择匹配音色，或修改语音设置中的资源 ID"
    else "$prefix：${message.take(180)}"
}
