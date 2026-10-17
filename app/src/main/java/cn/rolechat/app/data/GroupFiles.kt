package cn.rolechat.app.data

import cn.rolechat.core.GroupPackage
import cn.rolechat.core.validateGroupPackage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Group configurations are deliberately a different format from full chat backups. */
private val GroupJson = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

fun encodeGroupConfiguration(configuration: GroupPackage): String {
    validateGroupPackage(configuration)
    configuration.providers.forEach { serviceOrigin(it.baseUrl) }
    return GroupJson.encodeToString(configuration)
}

fun decodeGroupConfiguration(text: String): GroupPackage {
    val configuration = try { GroupJson.decodeFromString<GroupPackage>(text) }
    catch (error: SerializationException) {
        throw IllegalArgumentException("不是有效的群聊配置文件，请选择通过“导出群聊”生成的 JSON 文件")
    }
    configuration.providers.forEach { serviceOrigin(it.baseUrl) }
    return validateGroupPackage(configuration)
}

fun groupConfigurationFileName(name: String): String {
    val safeName = name.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().take(60).ifBlank { "群聊" }
    return "片刻-$safeName-群聊配置.json"
}
