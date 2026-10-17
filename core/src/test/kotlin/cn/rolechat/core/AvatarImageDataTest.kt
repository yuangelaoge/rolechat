package cn.rolechat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class AvatarImageDataTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private fun jpeg(): ByteArray = requireNotNull(javaClass.getResourceAsStream("/avatar-256.jpg")).use { it.readBytes() }
    private fun image(bytes: ByteArray = jpeg()) = AVATAR_IMAGE_DATA_PREFIX + Base64.getEncoder().encodeToString(bytes)

    @Test fun blankKeepsTextAvatarAndNormalizedJpegDecodesExactly() {
        assertNull(decodeAvatarImageData(""))
        validateAvatarImageData("")
        assertArrayEquals(jpeg(), decodeAvatarImageData(image()))
        assertEquals(256, AVATAR_IMAGE_SIZE)
    }

    @Test fun externalUrisOtherFormatsAndInvalidBase64AreRejectedWithoutEchoingInput() {
        val invalid = listOf("content://photos/private", "file:///private/photo.jpg", "https://private.example/avatar.jpg",
            "data:image/png;base64,AA==", "data:image/jpeg;base64,", AVATAR_IMAGE_DATA_PREFIX + "SGVsbG8=",
            image().dropLast(1), image() + "\n", image().replaceFirst("/9j/", "!!!!"), " ")
        invalid.forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) { validateAvatarImageData(value) }
            assertFalse(error.message.orEmpty().contains("private"))
            assertFalse(error.message.orEmpty().contains("base64"))
        }
    }

    @Test fun malformedJpegAndWrongFrameDimensionsAreRejectedBeforeBitmapDecode() {
        val source = jpeg()
        val frame = (0 until source.size - 1).first {
            (source[it].toInt() and 0xff) == 0xff && (source[it + 1].toInt() and 0xff) == 0xc0
        }
        val wrongSize = source.clone().apply { this[frame + 6] = 1 }
        val badLength = source.clone().apply { this[frame + 2] = 0x7f; this[frame + 3] = 0xff.toByte() }
        val noEnd = source.copyOf(source.size - 2)
        listOf(wrongSize, badLength, noEnd).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { decodeAvatarImageData(image(bytes)) }
        }
    }

    @Test fun maximumRawSizeIsAllowedButOversizedRawOrEncodedImagesAreRejected() {
        fun padded(size: Int): ByteArray {
            val source = jpeg()
            val extra = size - source.size
            val commentLength = extra - 2
            val comment = byteArrayOf(0xff.toByte(), 0xfe.toByte(), (commentLength shr 8).toByte(), commentLength.toByte()) +
                ByteArray(extra - 4) { 32 }
            return source.copyOfRange(0, 2) + comment + source.copyOfRange(2, source.size)
        }
        assertEquals(MAX_AVATAR_IMAGE_BYTES, decodeAvatarImageData(image(padded(MAX_AVATAR_IMAGE_BYTES)))!!.size)
        assertThrows(IllegalArgumentException::class.java) { decodeAvatarImageData(image(padded(MAX_AVATAR_IMAGE_BYTES + 1))) }
        assertThrows(IllegalArgumentException::class.java) { decodeAvatarImageData(image() + "A".repeat(100000)) }
    }

    @Test fun olderRoleAndRoomJsonLoadWithBlankImageFields() {
        val actor = json.decodeFromString<Character>("""{"id":"old-role","avatar":"林","color":4288267940}""")
        val room = json.decodeFromString<ChatRoom>("""{"id":"old-room","userName":"旧名字"}""")
        assertEquals("", actor.avatarImage)
        assertEquals("林", actor.avatar)
        assertEquals("", room.userAvatarImage)
        assertEquals("旧名字", room.userName)
    }

    @Test fun groupTransferPreservesBothImagesAcrossDuplicateNamesAndLeavesHistoryOut() {
        val original = groupState()
        val packageData = exportGroupPackage(original, "avatar-room")
        val encoded = json.encodeToString(packageData)
        assertTrue(encoded.contains("avatarImage"))
        assertTrue(encoded.contains("userAvatarImage"))
        assertFalse(encoded.contains("PRIVATE_MESSAGE"))
        assertFalse(encoded.contains("\"messages\""))
        val decoded = json.decodeFromString<GroupPackage>(encoded)
        val imported = importGroupPackage(original, decoded, now = 123)
        assertEquals(original.messages, imported.state.messages)
        val room = imported.state.rooms.single { it.id == imported.roomId }
        assertEquals(original.rooms.single().userAvatarImage, room.userAvatarImage)
        val actor = imported.state.characters.single { it.id == room.members.single().characterId }
        assertEquals("小萱（2）", actor.name)
        assertEquals(original.characters.single().avatarImage, actor.avatarImage)
        assertEquals("萱", actor.avatar)
    }

    @Test fun groupValidationAndDirectImportBothRejectInvalidUserOrCharacterPictures() {
        val original = groupState()
        val valid = exportGroupPackage(original, "avatar-room")
        val variants = listOf(valid.copy(room = valid.room.copy(userAvatarImage = "content://photo")),
            valid.copy(characters = valid.characters.map { it.copy(avatarImage = image() + "A".repeat(100000)) }))
        variants.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { validateGroupPackage(invalid) }
            assertThrows(IllegalArgumentException::class.java) { importGroupPackage(original, invalid) }
        }
        assertEquals(1, original.characters.size)
        assertEquals(1, original.rooms.size)
    }

    @Test fun avatarPicturesNeverEnterChatModelMessages() = runTest {
        val requests = mutableListOf<GenerationRequest>()
        val gateway = object : ChatGateway {
            override fun stream(request: GenerationRequest) = flow {
                requests += request
                emit(StreamEvent.Text("晚上一起散步吧。"))
            }
        }
        val store = StateStore(groupState())
        ChatEngine(store, gateway, this).send("avatar-room", "今晚做什么？")
        advanceUntilIdle()
        assertEquals(1, requests.size)
        val content = requests.single().messages.joinToString("\n") { it.content }
        assertTrue(content.contains("今晚做什么？"))
        assertTrue(content.contains("小萱"))
        assertFalse(content.contains(AVATAR_IMAGE_DATA_PREFIX))
        assertFalse(content.contains("avatarImage"))
        assertFalse(content.contains(image().removePrefix(AVATAR_IMAGE_DATA_PREFIX).take(60)))
    }

    private fun groupState(): AppState {
        val actor = Character(id = "avatar-role", name = "小萱", avatar = "萱", avatarImage = image())
        val source = jpeg()
        // A JPEG comment makes the user's image independently identifiable without changing pixels.
        val userBytes = source.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 3, 85) + source.copyOfRange(2, source.size)
        val room = ChatRoom(id = "avatar-room", name = "散步群", userName = "我", userAvatarImage = image(userBytes),
            members = listOf(Member(actor.id, 0, true)))
        return AppState(characters = listOf(actor), rooms = listOf(room),
            messages = listOf(ChatMessage(id = "old", roomId = room.id, text = "PRIVATE_MESSAGE")))
    }
}
