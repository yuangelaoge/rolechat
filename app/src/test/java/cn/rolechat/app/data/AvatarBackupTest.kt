package cn.rolechat.app.data

import cn.rolechat.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AvatarBackupTest {
    private fun image(): String = AVATAR_IMAGE_DATA_PREFIX + Base64.getEncoder().encodeToString(
        requireNotNull(javaClass.getResourceAsStream("/avatar-256.jpg")).use { it.readBytes() })

    private fun state(): AppState {
        val original = AppState()
        return original.copy(characters = original.characters.map { it.copy(avatarImage = image()) },
            rooms = original.rooms.map { it.copy(userAvatarImage = image()) }, messages = listOf(
                ChatMessage(id = "user-message", roomId = original.rooms.single().id, text = "之前的聊天记录")))
    }

    @Test fun completeBackupRoundTripsImagesAndKeepsExistingChatData() {
        val original = state()
        val encoded = AppJson.encodeToString(original)
        val restored = validateImportedState(AppJson.decodeFromString<AppState>(encoded))
        assertEquals(original, restored)
        assertEquals(original.messages, restored.messages)
        assertEquals(original.characters.map { it.avatar }, restored.characters.map { it.avatar })
        assertTrue(credentialsInvalidatedByImport(original, restored).isEmpty())
    }

    @Test fun oldBackupWithoutImageFieldsRetainsTextAvatarsAndPerRoomIdentity() {
        val original = state()
        val current = AppJson.parseToJsonElement(AppJson.encodeToString(original)).jsonObject
        val legacy = JsonObject(current.toMutableMap().apply {
            put("characters", JsonArray(current.getValue("characters").jsonArray.map {
                JsonObject(it.jsonObject - "avatarImage")
            }))
            put("rooms", JsonArray(current.getValue("rooms").jsonArray.map {
                JsonObject(it.jsonObject - "userAvatarImage")
            }))
        })
        val restored = validateImportedState(AppJson.decodeFromString<AppState>(legacy.toString()))
        assertTrue(restored.characters.all { it.avatarImage.isEmpty() })
        assertTrue(restored.rooms.all { it.userAvatarImage.isEmpty() })
        assertEquals(original.characters.map { it.avatar }, restored.characters.map { it.avatar })
        assertEquals(original.rooms.map { it.userName }, restored.rooms.map { it.userName })
        assertEquals(original.messages, restored.messages)
    }

    @Test fun fullBackupRejectsMalformedAndOversizedImagesInBothOwners() {
        val original = state()
        val invalidValues = listOf("content://photos/private", AVATAR_IMAGE_DATA_PREFIX + "@@@@",
            AVATAR_IMAGE_DATA_PREFIX + Base64.getEncoder().encodeToString(ByteArray(MAX_AVATAR_IMAGE_BYTES + 1)))
        invalidValues.forEach { value ->
            val invalidStates = listOf(original.copy(characters = original.characters.map { it.copy(avatarImage = value) }),
                original.copy(rooms = original.rooms.map { it.copy(userAvatarImage = value) }))
            invalidStates.forEach { invalid ->
                val error = assertThrows(IllegalArgumentException::class.java) { validateImportedState(invalid) }
                assertFalse(error.message.orEmpty().contains("private"))
            }
        }
    }

    @Test fun strictGroupFileCarriesBothAvatarsWithoutChatHistory() {
        val original = state()
        val encoded = encodeGroupConfiguration(exportGroupPackage(original, original.rooms.single().id))
        val decoded = decodeGroupConfiguration(encoded)
        assertEquals(original.rooms.single().userAvatarImage, decoded.room.userAvatarImage)
        assertEquals(original.characters.map { it.avatarImage }, decoded.characters.map { it.avatarImage })
        assertFalse(encoded.contains("之前的聊天记录"))
        assertFalse(encoded.contains("\"messages\""))
        val invalid = decoded.copy(room = decoded.room.copy(userAvatarImage = "file:///private/photo.jpg"))
        assertThrows(IllegalArgumentException::class.java) { encodeGroupConfiguration(invalid) }
        assertThrows(IllegalArgumentException::class.java) {
            decodeGroupConfiguration(AppJson.encodeToString(invalid))
        }
    }
}
