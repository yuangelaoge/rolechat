package cn.rolechat.core

import java.util.Base64

const val AVATAR_IMAGE_SIZE = 256
const val MAX_AVATAR_IMAGE_BYTES = 64 * 1024
const val AVATAR_IMAGE_DATA_PREFIX = "data:image/jpeg;base64,"
private const val MAX_AVATAR_BASE64_LENGTH = ((MAX_AVATAR_IMAGE_BYTES + 2) / 3) * 4

/** Embedded thumbnails survive backup/transfer and do not retain access to the original photo. */
fun validateAvatarImageData(value: String, label: String = "头像") {
    decodeAvatarImageData(value, label)
}

/** Empty means the existing text avatar. Other formats and external image addresses are rejected. */
fun decodeAvatarImageData(value: String, label: String = "头像"): ByteArray? {
    if (value.isEmpty()) return null
    require(value.startsWith(AVATAR_IMAGE_DATA_PREFIX)) { "${label}图片格式无效，请重新从相册选择" }
    require(value.length <= AVATAR_IMAGE_DATA_PREFIX.length + MAX_AVATAR_BASE64_LENGTH) { "${label}图片过大，请重新从相册选择" }
    val encoded = value.substring(AVATAR_IMAGE_DATA_PREFIX.length)
    require(encoded.isNotEmpty() && encoded.length % 4 == 0) { "${label}图片数据无效" }
    val bytes = try { Base64.getDecoder().decode(encoded) }
    catch (_: IllegalArgumentException) { throw IllegalArgumentException("${label}图片数据无效") }
    require(bytes.size <= MAX_AVATAR_IMAGE_BYTES) { "${label}图片过大，请重新从相册选择" }
    require(Base64.getEncoder().encodeToString(bytes) == encoded && isAvatarJpeg(bytes)) { "${label}图片数据无效，请重新从相册选择" }
    return bytes
}

/** Check frame dimensions before any UI decoder allocates a bitmap from imported data. */
private fun isAvatarJpeg(bytes: ByteArray): Boolean {
    fun u8(index: Int): Int = bytes[index].toInt() and 0xff
    fun u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
    if (bytes.size < 32 || u8(0) != 0xff || u8(1) != 0xd8 ||
        u8(bytes.lastIndex - 1) != 0xff || u8(bytes.lastIndex) != 0xd9) return false
    var offset = 2
    var frameFound = false
    while (offset < bytes.size - 2) {
        if (u8(offset++) != 0xff) return false
        while (offset < bytes.size && u8(offset) == 0xff) offset++
        if (offset >= bytes.size) return false
        val marker = u8(offset++)
        if (marker == 0 || marker == 0x01 || marker in 0xd0..0xd9 || offset + 2 > bytes.size - 2) return false
        val length = u16(offset)
        if (length < 2 || offset + length > bytes.size - 2) return false
        if (marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)) {
            // Only ordinary 8-bit baseline/extended/progressive JPEGs are supported.
            if (frameFound || marker !in 0xc0..0xc2 || length < 8 || u8(offset + 2) != 8 ||
                u16(offset + 3) != AVATAR_IMAGE_SIZE || u16(offset + 5) != AVATAR_IMAGE_SIZE) return false
            val components = u8(offset + 7)
            if (components !in setOf(1, 3) || length != 8 + 3 * components) return false
            frameFound = true
        }
        if (marker == 0xda) {
            if (length < 6) return false
            val components = u8(offset + 2)
            return frameFound && components in 1..3 && length == 6 + 2 * components && offset + length < bytes.size - 2
        }
        offset += length
    }
    return false
}
