package cn.rolechat.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class GroupTransferTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test fun completeConfigurationRoundTripsWithRemappedIdsAndTargetDeviceSettings() {
        val source = sourceState()
        val packageData = exportGroupPackage(source, "group")
        val restored = json.decodeFromString(GroupPackage.serializer(), json.encodeToString(GroupPackage.serializer(), packageData))
        val target = emptyState().copy(tts = TtsSettings(enabled = true, engine = TtsEngine.SILICONFLOW,
            siliconModel = "target-device/model", siliconVoice = "target-device-voice"))
        val result = importGroupPackage(target, restored, now = 123_456)
        val room = result.state.rooms.single()
        assertEquals(result.roomId, room.id)
        assertNotEquals(packageData.room.id, room.id)
        assertEquals(123_456L, room.createdAt)
        assertTrue(room.members.all { it.joinedAt == 123_456L })
        packageData.room.members.zip(room.members).forEach { (original, imported) ->
            assertEquals(original, imported.copy(characterId = original.characterId, joinedAt = original.joinedAt))
        }
        assertEquals(packageData.room, room.copy(id = packageData.room.id, createdAt = packageData.room.createdAt,
            worldId = packageData.room.worldId, members = packageData.room.members))
        assertSame(target.tts, result.state.tts)
        assertSame(target.messages, result.state.messages)

        packageData.characters.zip(room.members).forEach { (original, member) ->
            val imported = result.state.characters.single { it.id == member.characterId }
            assertNotEquals(original.id, imported.id)
            assertEquals(original, imported.copy(id = original.id, worldId = original.worldId,
                primary = imported.primary.copy(providerId = original.primary.providerId),
                secondary = imported.secondary.copy(providerId = original.secondary.providerId)))
            assertEquals(original.worldId?.let { id -> source.worlds.single { it.id == id }.prompt },
                imported.worldId?.let { id -> result.state.worlds.single { it.id == id }.prompt })
            val primary = result.state.providers.single { it.id == imported.primary.providerId }
            val secondary = result.state.providers.single { it.id == imported.secondary.providerId }
            assertEquals(source.providers.single { it.id == original.primary.providerId }.baseUrl, primary.baseUrl)
            assertEquals(source.providers.single { it.id == original.secondary.providerId }.baseUrl, secondary.baseUrl)
        }
        assertEquals(source.worlds.single { it.id == packageData.room.worldId }.prompt,
            result.state.worlds.single { it.id == room.worldId }.prompt)
        assertTrue(result.state.worlds.none { imported -> source.worlds.any { it.id == imported.id } })
        assertTrue(result.state.providers.none { imported -> source.providers.any { it.id == imported.id } })
        packageData.providers.forEach { original ->
            val imported = result.state.providers.single { it.baseUrl == original.baseUrl }
            assertEquals(original, imported.copy(id = original.id))
        }
    }

    @Test fun exportHasOnlyRequiredSettingsAndNoHistoryReasoningContextOrDeviceTts() {
        val exported = exportGroupPackage(sourceState(), "group")
        assertEquals(listOf("actor-a", "actor-b"), exported.characters.map { it.id })
        assertEquals(listOf("group-world", "role-world"), exported.worlds.map { it.id })
        assertEquals(listOf("provider-a", "provider-b"), exported.providers.map { it.id })
        val encoded = json.encodeToString(GroupPackage.serializer(), exported)
        assertEquals(setOf("format", "version", "room", "characters", "worlds", "providers"), json.parseToJsonElement(encoded).jsonObject.keys)
        listOf("private-room-marker", "private-role-marker", "private-world-marker", "private-provider-marker",
            "message-body-marker", "reasoning-marker", "context-id-marker", "tts-device-marker").forEach { marker ->
            assertFalse("群配置不能携带无关资料：$marker", encoded.contains(marker))
        }
        assertFalse(encoded.contains("\"messages\""))
        assertFalse(encoded.contains("\"reasoning\""))
        assertFalse(encoded.contains("\"contextMessageIds\""))
        assertFalse(encoded.contains("\"tts\""))
    }

    @Test fun repeatedImportsAppendUniqueEntitiesAndIncrementNamesWithoutChangingExistingData() {
        val initial = sourceState()
        val before = json.encodeToString(AppState.serializer(), initial)
        val packageData = exportGroupPackage(initial, "group")
        val first = importGroupPackage(initial, packageData, now = 100)
        val second = importGroupPackage(first.state, packageData, now = 200)
        val firstRoom = second.state.rooms.single { it.id == first.roomId }
        val secondRoom = second.state.rooms.single { it.id == second.roomId }
        assertEquals("树影茶会（2）", firstRoom.name)
        assertEquals("树影茶会（3）", secondRoom.name)
        assertEquals(listOf("小萱（2）", "守望者（2）"), firstRoom.members.map { m -> second.state.characters.single { it.id == m.characterId }.name })
        assertEquals(listOf("小萱（3）", "守望者（3）"), secondRoom.members.map { m -> second.state.characters.single { it.id == m.characterId }.name })
        assertEquals("共享舞台（2）", second.state.worlds.single { it.id == firstRoom.worldId }.name)
        assertEquals("共享舞台（3）", second.state.worlds.single { it.id == secondRoom.worldId }.name)
        assertTrue(firstRoom.members.map { it.characterId }.toSet().intersect(secondRoom.members.map { it.characterId }.toSet()).isEmpty())
        assertNotEquals(firstRoom.worldId, secondRoom.worldId)
        assertEquals(initial.providers, second.state.providers)
        assertEquals(initial.rooms, second.state.rooms.take(initial.rooms.size))
        assertEquals(initial.characters, second.state.characters.take(initial.characters.size))
        assertEquals(initial.worlds, second.state.worlds.take(initial.worlds.size))
        assertSame(initial.messages, second.state.messages)
        assertSame(initial.tts, second.state.tts)
        assertEquals(before, json.encodeToString(AppState.serializer(), initial))
    }

    @Test fun existingNumericSuffixesAndRepeatedNamesWithinOnePackageNeverNestSuffixes() {
        val packageData = minimalPackage().let { it.copy(characters = it.characters.map { c -> c.copy(name = "小萱（2）") }) }
        val existing = packageData.characters.single()
        val target = emptyState().copy(providers = packageData.providers,
            characters = listOf(existing.copy(id = "existing-base", name = "小萱"), existing.copy(id = "existing-two")))
        val first = importGroupPackage(target, packageData)
        val second = importGroupPackage(first.state, packageData)
        assertEquals("小萱（3）", first.state.characters.last().name)
        assertEquals("小萱（4）", second.state.characters.last().name)
        assertFalse(second.state.characters.last().name.contains("）（"))

        val twoActors = packageData.copy(
            room = packageData.room.copy(members = listOf(Member(existing.id), Member("another-actor"))),
            characters = listOf(existing.copy(name = "小萱"), existing.copy(id = "another-actor", name = "小萱"))
        )
        assertEquals(listOf("小萱", "小萱（2）"), importGroupPackage(emptyState(), twoActors).state.characters.map { it.name })
    }

    @Test fun onlyAnIdenticalNormalizedEndpointAndProtocolMayReuseLocalProvider() {
        val local = Provider(id = "local-connection", name = "本机服务", baseUrl = "https://api.example/v1",
            prefixMode = PrefixMode.ASSISTANT_FLAG, echoPrefix = true)
        val incoming = local.copy(id = "foreign-connection", name = "别的名字", baseUrl = "  https://api.example/v1///  ")
        val target = emptyState().copy(providers = listOf(local))
        val imported = importGroupPackage(target, minimalPackage(incoming))
        assertEquals(listOf(local), imported.state.providers)
        assertEquals(local.id, imported.state.characters.single().primary.providerId)
        assertEquals(local.id, imported.state.characters.single().secondary.providerId)
        assertEquals("本机服务", imported.state.providers.single().name)
    }

    @Test fun sameIdAndNameNeverReuseAnotherPathHostOrProtocolConfiguration() {
        val local = Provider(id = "shared-id", name = "同名服务", baseUrl = "https://api.example/v1")
        val variants = listOf(
            local.copy(baseUrl = "https://elsewhere.example/v1"),
            local.copy(baseUrl = "https://api.example/other-path"),
            local.copy(prefixMode = PrefixMode.ASSISTANT),
            local.copy(echoPrefix = true),
            local.copy(baseUrl = "https://API.example/v1")
        )
        variants.forEach { incoming ->
            val target = emptyState().copy(providers = listOf(local))
            val imported = importGroupPackage(target, minimalPackage(incoming))
            assertEquals(2, imported.state.providers.size)
            assertEquals(local, imported.state.providers.first())
            val added = imported.state.providers.last()
            assertNotEquals("不同连接必须有新 ID，不能继承同名服务的密钥", local.id, added.id)
            assertEquals("同名服务（2）", added.name)
            assertEquals(added.id, imported.state.characters.single().primary.providerId)
            assertEquals(incoming.baseUrl, added.baseUrl)
            assertEquals(incoming.prefixMode, added.prefixMode)
            assertEquals(incoming.echoPrefix, added.echoPrefix)
        }
    }

    @Test fun equivalentConnectionsWithinPackageShareOneNewProviderAndIgnoreMisleadingIds() {
        val first = Provider(id = "remote-a", name = "第一服务", baseUrl = "https://same.example/v1")
        val second = first.copy(id = "remote-b", name = "第二服务", baseUrl = "https://same.example/v1/")
        val packageData = minimalPackage(first).let { it.copy(
            providers = listOf(first, second), characters = it.characters.map { c -> c.copy(secondary = c.secondary.copy(providerId = second.id)) }
        ) }
        val imported = importGroupPackage(emptyState(), packageData)
        assertEquals(1, imported.state.providers.size)
        assertEquals(imported.state.characters.single().primary.providerId, imported.state.characters.single().secondary.providerId)

        val wrongIdMatch = first.copy(baseUrl = "https://wrong.example/v1")
        val correctConnection = first.copy(id = "actual-local-connection", name = "现有正确服务")
        val target = emptyState().copy(providers = listOf(wrongIdMatch, correctConnection))
        val reused = importGroupPackage(target, minimalPackage(first))
        assertEquals(target.providers, reused.state.providers)
        assertEquals(correctConnection.id, reused.state.characters.single().primary.providerId)
    }

    @Test fun incompleteDuplicateWrongVersionAndUnusedDependenciesFailWithoutMutatingState() {
        val original = sourceState()
        val valid = exportGroupPackage(original, "group")
        val invalid = listOf(
            valid.copy(format = "rolechat.backup"),
            valid.copy(version = 99),
            valid.copy(room = valid.room.copy(direct = true)),
            valid.copy(room = valid.room.copy(id = " ")),
            valid.copy(room = valid.room.copy(members = valid.room.members + valid.room.members.first())),
            valid.copy(characters = valid.characters + valid.characters.first()),
            valid.copy(worlds = valid.worlds + valid.worlds.first()),
            valid.copy(providers = valid.providers + valid.providers.first()),
            valid.copy(characters = valid.characters.drop(1)),
            valid.copy(worlds = valid.worlds.filterNot { it.id == "group-world" }),
            valid.copy(worlds = valid.worlds.filterNot { it.id == "role-world" }),
            valid.copy(providers = valid.providers.filterNot { it.id == "provider-a" }),
            valid.copy(providers = valid.providers.filterNot { it.id == "provider-b" }),
            valid.copy(characters = valid.characters + valid.characters.first().copy(id = "unused-character")),
            valid.copy(worlds = valid.worlds + World(id = "unused-world")),
            valid.copy(providers = valid.providers + Provider(id = "unused-provider")),
            valid.copy(providers = valid.providers.map { it.copy(baseUrl = "   ") }),
            valid.copy(characters = valid.characters.map { it.copy(id = "") })
        )
        val before = json.encodeToString(AppState.serializer(), original)
        invalid.forEach { bad ->
            val packageBefore = json.encodeToString(GroupPackage.serializer(), bad)
            val failure = assertThrows(IllegalArgumentException::class.java) { importGroupPackage(original, bad) }
            assertFalse(failure.message.isNullOrBlank())
            assertEquals(before, json.encodeToString(AppState.serializer(), original))
            assertEquals(packageBefore, json.encodeToString(GroupPackage.serializer(), bad))
        }
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            importGroupPackage(original, valid.copy(providers = valid.providers.filterNot { it.id == "provider-b" }))
        }.message!!.contains("provider-b"))
    }

    @Test fun inactiveSecondaryModelStillNeedsItsProviderAndNullWorldsNeedNone() {
        val packageData = minimalPackage().let { it.copy(characters = it.characters.map { c -> c.copy(
            dualModel = false, secondary = c.secondary.copy(providerId = "missing-secondary")
        ) }) }
        assertTrue(assertThrows(IllegalArgumentException::class.java) { validateGroupPackage(packageData) }.message!!.contains("missing-secondary"))
        val valid = minimalPackage()
        assertTrue(valid.worlds.isEmpty())
        val imported = importGroupPackage(emptyState(), valid)
        assertNull(imported.state.rooms.single().worldId)
        assertNull(imported.state.characters.single().worldId)
        assertTrue(imported.state.worlds.isEmpty())
    }

    @Test fun strictJsonRejectsHistoryReasoningCredentialsAndMissingFormatMetadata() {
        val valid = json.parseToJsonElement(json.encodeToString(GroupPackage.serializer(), minimalPackage())).jsonObject
        val extra = JsonArray(listOf(JsonPrimitive("must-not-be-imported")))
        val providers = valid.getValue("providers").jsonArray
        val characters = valid.getValue("characters").jsonArray
        val invalid = listOf(
            JsonObject(valid + ("messages" to extra)),
            JsonObject(valid + ("tts" to JsonObject(emptyMap()))),
            JsonObject(valid + ("room" to JsonObject(valid.getValue("room").jsonObject + ("messages" to extra)))),
            JsonObject(valid + ("room" to JsonObject(valid.getValue("room").jsonObject + ("contextMessageIds" to extra)))),
            JsonObject(valid + ("characters" to JsonArray(listOf(JsonObject(characters.single().jsonObject + ("reasoning" to extra)))))),
            JsonObject(valid + ("providers" to JsonArray(listOf(JsonObject(providers.single().jsonObject + ("apiKey" to JsonPrimitive("synthetic-key"))))))),
            JsonObject(valid - "format"),
            JsonObject(valid - "version")
        )
        invalid.forEach { encoded ->
            assertThrows(SerializationException::class.java) { json.decodeFromString(GroupPackage.serializer(), encoded.toString()) }
        }
    }

    @Test fun missingGroupDirectChatAndBrokenExportDependenciesAreExplicitErrors() {
        val state = sourceState()
        assertThrows(IllegalArgumentException::class.java) { exportGroupPackage(state, "not-found") }
        assertThrows(IllegalArgumentException::class.java) { exportGroupPackage(state.copy(rooms = state.rooms.map { it.copy(direct = true) }), "group") }
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            exportGroupPackage(state.copy(characters = state.characters.filterNot { it.id == "actor-a" }), "group")
        }.message!!.contains("actor-a"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            exportGroupPackage(state.copy(worlds = state.worlds.filterNot { it.id == "role-world" }), "group")
        }.message!!.contains("role-world"))
    }

    private fun emptyState() = AppState(providers = emptyList(), worlds = emptyList(), characters = emptyList(), rooms = emptyList())

    private fun minimalPackage(provider: Provider = Provider(id = "incoming-provider", baseUrl = "https://api.example/v1")): GroupPackage {
        val actor = Character(id = "incoming-role", name = "小萱", worldId = null,
            primary = ModelConfig(providerId = provider.id), secondary = ModelConfig(providerId = provider.id))
        val state = emptyState().copy(providers = listOf(provider), characters = listOf(actor),
            rooms = listOf(ChatRoom(id = "incoming-room", name = "示例群", worldId = null, members = listOf(Member(actor.id)))))
        return exportGroupPackage(state, "incoming-room")
    }

    private fun sourceState(): AppState {
        val first = Provider(id = "provider-a", name = "模型服务 A", baseUrl = "https://a.example/v1", prefixMode = PrefixMode.ASSISTANT_FLAG, echoPrefix = true)
        val second = Provider(id = "provider-b", name = "模型服务 B", baseUrl = "https://b.example/v1", prefixMode = PrefixMode.ASSISTANT)
        val actorA = Character(id = "actor-a", name = "小萱", avatar = "萱", color = 0xFF123456, prompt = "角色完整人设。", worldId = "role-world",
            primary = ModelConfig(providerId = first.id, model = "custom/model-a", thinking = ThinkingMode.ON, temperature = 0.42, maxTokens = 345),
            secondary = ModelConfig(providerId = second.id, model = "custom/model-b", thinking = ThinkingMode.OFF, temperature = 0.31, maxTokens = 678),
            dualModel = true, cutMin = 3, cutMax = 7, firstTextTimeoutSec = 55, stallTimeoutSec = 24,
            probability = 57, cooldownSec = 13, proactive = false, maxRepliesPerRound = 3,
            voice = "doubao-role-voice", siliconModel = "custom/speech-model", siliconVoice = "speech:role-voice", messageStyle = MessageStyle.SCENE)
        val actorB = actorA.copy(id = "actor-b", name = "守望者", worldId = "group-world", dualModel = false, messageStyle = MessageStyle.CHAT)
        val room = ChatRoom(id = "group", name = "树影茶会", worldId = "group-world",
            members = listOf(Member(actorA.id, 10, true, 21), Member(actorB.id, 20, false)),
            mode = ReplyMode.PARALLEL, interChat = true, maxFollowUps = 5, maxInitialReplies = 2, shuffleOrder = true,
            autoSpeak = true, createdAt = 11, userName = "旅客", userBackground = "这个群里的身份背景。", pinned = true)
        return AppState(
            providers = listOf(first, second, Provider(id = "private-provider", name = "private-provider-marker", baseUrl = "https://private.example/v1")),
            worlds = listOf(World("group-world", "共享舞台", "群世界观。"), World("role-world", "角色世界", "角色独立世界观。"),
                World("private-world", "private-world-marker", "其他会话的私有背景。")),
            characters = listOf(actorA, actorB, actorA.copy(id = "private-role", name = "private-role-marker", worldId = "private-world")),
            rooms = listOf(room, ChatRoom(id = "private-room", name = "private-room-marker", members = listOf(Member("private-role")), worldId = "private-world")),
            messages = listOf(ChatMessage(id = "private-message", roomId = room.id, characterId = actorA.id, text = "message-body-marker",
                segments = listOf(GenerationSegment(reasoning = "reasoning-marker")), contextMessageIds = listOf("context-id-marker"))),
            tts = TtsSettings(enabled = true, appId = "tts-device-marker")
        )
    }
}
