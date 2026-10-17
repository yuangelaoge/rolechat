package cn.rolechat.app.data

import cn.rolechat.app.speech.DoubaoVoice
import cn.rolechat.app.speech.DoubaoVoices
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class VoiceCatalogSnapshot(val voices: List<DoubaoVoice>, val updatedAt: Long)

/** Only public voice metadata is cached. Credentials are stored separately in SecretStore. */
class VoiceCatalogCache(private val file: File) {
    fun read(): VoiceCatalogSnapshot? = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        AppJson.decodeFromString<VoiceCatalogSnapshot>(file.readText()).also(::validate)
    }.getOrNull()

    fun write(snapshot: VoiceCatalogSnapshot) {
        validate(snapshot)
        val data = AppJson.encodeToString(snapshot).toByteArray()
        require(data.size <= MAX_BYTES) { "音色列表过大" }
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            pending.outputStream().use { it.write(data) }
            check(pending.renameTo(file)) { "音色列表缓存保存失败" }
        } finally { pending.delete() }
    }

    private fun validate(snapshot: VoiceCatalogSnapshot) {
        require(snapshot.updatedAt > 0 && snapshot.voices.size in 1..5000)
        require(snapshot.voices.all {
            it.id.isNotBlank() && it.id.length <= 256 && it.name.isNotBlank() && it.name.length <= 256 &&
                it.description.length <= 2000 && it.resourceId in setOf("seed-tts-1.0", "seed-tts-2.0")
        })
    }

    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}

fun mergedDoubaoVoices(fetched: List<DoubaoVoice>): List<DoubaoVoice> {
    val known = DoubaoVoices.builtIn.associateBy { it.resourceId to it.id }
    val result = known.toMutableMap()
    fetched.forEach { voice ->
        val key = voice.resourceId to voice.id
        // ListSpeakers may omit transport restrictions; retain official known exclusions.
        result[key] = voice.copy(bidirectional = voice.bidirectional && known[key]?.bidirectional != false)
    }
    return result.values.toList()
}

@Serializable data class VoiceCatalogCredentials(val accessKey: String, val secretKey: String)
const val VOICE_CATALOG_SECRET_ID = "volc.official.voice.catalog.credentials.v1"
