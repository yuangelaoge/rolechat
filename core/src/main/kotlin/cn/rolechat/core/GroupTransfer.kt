package cn.rolechat.core

import kotlinx.serialization.Serializable

const val GROUP_PACKAGE_FORMAT = "rolechat.group"
const val GROUP_PACKAGE_VERSION = 1

/** Settings-only exchange format. Message history, reasoning, credentials and global TTS are absent. */
@Serializable
data class GroupPackage(
    val format: String,
    val version: Int,
    val room: ChatRoom,
    val characters: List<Character>,
    val worlds: List<World>,
    val providers: List<Provider>
)

data class GroupImportResult(val state: AppState, val roomId: String)

/** Export only the selected group's transitive configuration dependencies. */
fun exportGroupPackage(state: AppState, roomId: String): GroupPackage {
    val room = uniqueIndex(state.rooms, "会话") { it.id }[roomId]
        ?: throw IllegalArgumentException("找不到要导出的群聊：$roomId")
    require(!room.direct) { "只能导出群聊配置，不能导出单聊" }
    val characters = uniqueIndex(state.characters, "角色") { it.id }
    val worlds = uniqueIndex(state.worlds, "世界观") { it.id }
    val providers = uniqueIndex(state.providers, "模型服务") { it.id }
    val members = room.members.map { member ->
        characters[member.characterId] ?: throw IllegalArgumentException("群成员缺少角色配置：${member.characterId}")
    }
    val worldIds = requiredWorldIds(room, members)
    val providerIds = requiredProviderIds(room, members, providers.keys)
    return validateGroupPackage(GroupPackage(
        format = GROUP_PACKAGE_FORMAT,
        version = GROUP_PACKAGE_VERSION,
        room = room,
        characters = members,
        worlds = worldIds.map { worlds[it] ?: throw IllegalArgumentException("缺少世界观配置：$it") },
        providers = providerIds.map { providers[it] ?: throw IllegalArgumentException("缺少模型服务配置：$it") }
    ))
}

/**
 * Validate the complete package before allocating imported entities. The JSON boundary must also
 * use ignoreUnknownKeys=false so extra history/credential fields cannot be silently accepted.
 */
fun validateGroupPackage(packageData: GroupPackage): GroupPackage {
    dependencies(packageData)
    return packageData
}

/** Append a new group and remapped entities without modifying or replacing the input state. */
fun importGroupPackage(
    state: AppState,
    packageData: GroupPackage,
    now: Long = System.currentTimeMillis()
): GroupImportResult {
    val used = dependencies(packageData)
    val occupiedIds = buildSet {
        addAll(state.rooms.map { it.id })
        addAll(state.characters.map { it.id })
        addAll(state.worlds.map { it.id })
        addAll(state.providers.map { it.id })
        addAll(state.messages.map { it.id })
        add(packageData.room.id)
        addAll(packageData.characters.map { it.id })
        addAll(packageData.worlds.map { it.id })
        addAll(packageData.providers.map { it.id })
    }.toMutableSet()
    fun freshId(): String {
        var id: String
        do { id = newId() } while (!occupiedIds.add(id))
        return id
    }

    val providerNames = state.providers.map { it.name.trim() }.toMutableSet()
    val allProviders = state.providers.toMutableList()
    val providerIds = linkedMapOf<String, String>()
    used.providers.forEach { provider ->
        // Equality deliberately includes the full path and protocol options. Imported names and
        // IDs never select an existing credential; only an equivalent connection may be reused.
        val existing = allProviders.firstOrNull { sameConnection(it, provider) }
        val target = existing ?: provider.copy(
            id = freshId(), name = uniqueName(provider.name, providerNames, "模型服务"),
            baseUrl = normalizedEndpoint(provider.baseUrl)
        ).also { allProviders.add(it) }
        providerIds[provider.id] = target.id
    }
    val worldNames = state.worlds.map { it.name.trim() }.toMutableSet()
    val worldIds = linkedMapOf<String, String>()
    val worlds = used.worlds.map { world ->
        world.copy(id = freshId(), name = uniqueName(world.name, worldNames, "世界观"))
            .also { worldIds[world.id] = it.id }
    }
    val characterNames = state.characters.map { it.name.trim() }.toMutableSet()
    val characterIds = linkedMapOf<String, String>()
    val characters = used.characters.map { character ->
        character.copy(
            id = freshId(), name = uniqueName(character.name, characterNames, "角色"),
            worldId = character.worldId?.let(worldIds::getValue),
            primary = character.primary.copy(providerId = providerIds.getValue(character.primary.providerId)),
            secondary = character.secondary.copy(providerId = providerIds.getValue(character.secondary.providerId))
        ).also { characterIds[character.id] = it.id }
    }
    val room = packageData.room.copy(
        id = freshId(), name = uniqueName(packageData.room.name, state.rooms.map { it.name.trim() }.toMutableSet(), "群聊"),
        members = packageData.room.members.map { it.copy(characterId = characterIds.getValue(it.characterId), joinedAt = now) },
        worldId = packageData.room.worldId?.let(worldIds::getValue), createdAt = now,
        routing = packageData.room.routing.let { settings -> settings.copy(model = settings.model.copy(
            providerId = providerIds[settings.model.providerId] ?: settings.model.providerId)) },
        compression = packageData.room.compression.let { settings -> settings.copy(model = settings.model.copy(
            providerId = providerIds[settings.model.providerId] ?: settings.model.providerId)) }
    )
    return GroupImportResult(
        state.copy(providers = allProviders.toList(), worlds = state.worlds + worlds,
            characters = state.characters + characters, rooms = state.rooms + room),
        room.id
    )
}

private data class GroupDependencies(
    val characters: List<Character>, val worlds: List<World>, val providers: List<Provider>
)

private fun dependencies(packageData: GroupPackage): GroupDependencies {
    require(packageData.format == GROUP_PACKAGE_FORMAT) { "不是片刻群聊配置文件" }
    require(packageData.version == GROUP_PACKAGE_VERSION) { "不支持的群聊配置版本：${packageData.version}" }
    require(packageData.room.id.isNotBlank()) { "群聊 ID 不能为空" }
    require(!packageData.room.direct) { "群聊配置不能包含单聊" }
    require(packageData.room.directorInstruction.length <= MAX_DIRECTOR_INSTRUCTION) { "剧情指令过长" }
    validateAvatarImageData(packageData.room.userAvatarImage, "我的头像")
    packageData.characters.forEach { validateAvatarImageData(it.avatarImage, "角色头像") }
    val characters = uniqueIndex(packageData.characters, "角色") { it.id }
    val worlds = uniqueIndex(packageData.worlds, "世界观") { it.id }
    val providers = uniqueIndex(packageData.providers, "模型服务") { it.id }
    uniqueIndex(packageData.room.members, "群成员") { it.characterId }
    val members = packageData.room.members.map { member ->
        characters[member.characterId] ?: throw IllegalArgumentException("群成员缺少角色配置：${member.characterId}")
    }
    require(characters.keys == members.map { it.id }.toSet()) { "群配置包含未被该群使用的角色" }
    val worldIds = requiredWorldIds(packageData.room, members)
    val providerIds = requiredProviderIds(packageData.room, members, providers.keys)
    val selectedWorlds = worldIds.map { worlds[it] ?: throw IllegalArgumentException("缺少世界观配置：$it") }
    val selectedProviders = providerIds.map { providers[it] ?: throw IllegalArgumentException("缺少模型服务配置：$it") }
    require(worlds.keys == worldIds) { "群配置包含未被该群使用的世界观" }
    require(providers.keys == providerIds) { "群配置包含未被该群使用的模型服务" }
    selectedProviders.forEach { require(normalizedEndpoint(it.baseUrl).isNotEmpty()) { "模型服务地址不能为空：${it.id}" } }
    // Inactive draft settings are preserved, just as they are when saving a legacy room.
    // They are validated when SMART is enabled; an empty draft must not prevent export.
    if (packageData.room.mode == ReplyMode.SMART) validateRoutingSettings(packageData.room.routing, selectedProviders)
    validateCompressionSettings(packageData.room.compression)
    return GroupDependencies(members, selectedWorlds, selectedProviders)
}

private fun requiredWorldIds(room: ChatRoom, characters: List<Character>): Set<String> = linkedSetOf<String>().apply {
    room.worldId?.let(::add)
    characters.forEach { it.worldId?.let(::add) }
}

private fun usesRoutingSettings(room: ChatRoom): Boolean = room.mode == ReplyMode.SMART || room.routing != RoutingSettings()

private fun requiredProviderIds(room: ChatRoom, characters: List<Character>, availableProviders: Set<String>): Set<String> = linkedSetOf<String>().apply {
    characters.forEach { add(it.primary.providerId); add(it.secondary.providerId) }
    if (usesRoutingSettings(room)) add(room.routing.model.providerId)
    // Preserve an available default connection, but tolerate old packages that never included it.
    if (room.compression != CompressionSettings() || room.compression.model.providerId in availableProviders)
        add(room.compression.model.providerId)
}

private fun <T> uniqueIndex(values: List<T>, label: String, id: (T) -> String): Map<String, T> {
    val result = linkedMapOf<String, T>()
    values.forEach { value ->
        val key = id(value)
        require(key.isNotBlank()) { "$label ID 不能为空" }
        require(result.put(key, value) == null) { "$label ID 重复：$key" }
    }
    return result
}

private fun normalizedEndpoint(endpoint: String): String = endpoint.trim().trimEnd('/')

private fun sameConnection(first: Provider, second: Provider): Boolean =
    normalizedEndpoint(first.baseUrl) == normalizedEndpoint(second.baseUrl) &&
        first.prefixMode == second.prefixMode && first.echoPrefix == second.echoPrefix

private val NUMBERED_NAME = Regex("(?:（([0-9]+)）|\\(([0-9]+)\\))$")

private fun uniqueName(requested: String, occupied: MutableSet<String>, fallback: String): String {
    val candidate = requested.trim().ifBlank { fallback }
    if (occupied.add(candidate)) return candidate
    val lastSuffix = NUMBERED_NAME.find(candidate)
    val previous = lastSuffix?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
    var number = previous?.takeIf { it in 2 until Int.MAX_VALUE }?.plus(1) ?: 2
    var stem = candidate
    while (true) {
        val suffix = NUMBERED_NAME.find(stem) ?: break
        stem = stem.substring(0, suffix.range.first).trimEnd()
    }
    stem = stem.ifBlank { fallback }
    while (true) {
        val name = "$stem（$number）"
        if (occupied.add(name)) return name
        number = if (number == Int.MAX_VALUE) 2 else number + 1
    }
}
