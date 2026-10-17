package cn.rolechat.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import cn.rolechat.core.AVATAR_IMAGE_DATA_PREFIX
import cn.rolechat.core.AVATAR_IMAGE_SIZE
import cn.rolechat.core.MAX_AVATAR_IMAGE_BYTES
import cn.rolechat.core.decodeAvatarImageData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
private const val MAX_DECODED_SIDE = 2048

/** Copy the selected photo into the draft so it survives permission expiry and group exports. */
internal suspend fun importAvatarImage(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
    val source = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_SOURCE_BYTES) { "图片超过 20 MB，请选择较小的照片" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IllegalArgumentException("无法打开这张照片，请重新选择")
    currentCoroutineContext().ensureActive()
    normalizeAvatarImage(source).also { currentCoroutineContext().ensureActive() }
}

/** Bounds-first decode, correct camera orientation, then a centered square with no source metadata. */
internal fun normalizeAvatarImage(source: ByteArray): String {
    require(source.isNotEmpty() && source.size <= MAX_SOURCE_BYTES) { "图片为空或超过 20 MB" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别这张照片，请选择 JPG、PNG 或其他支持的图片" }
    var sample = 1
    // Bound both dimensions even for extreme panoramas; never decode the full camera image first.
    while (bounds.outWidth / sample > MAX_DECODED_SIDE || bounds.outHeight / sample > MAX_DECODED_SIDE ||
        minOf(bounds.outWidth, bounds.outHeight) / sample >= AVATAR_IMAGE_SIZE * 2) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
        ?: throw IllegalArgumentException("无法读取这张照片，请换一张图片")
    var oriented = decoded
    var square: Bitmap? = null
    try {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = orientationMatrix(orientation)
        if (transform != null) {
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
        }
        val output = Bitmap.createBitmap(AVATAR_IMAGE_SIZE, AVATAR_IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        square = output
        val side = minOf(oriented.width, oriented.height)
        val left = (oriented.width - side) / 2
        val top = (oriented.height - side) / 2
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(oriented, Rect(left, top, left + side, top + side),
                Rect(0, 0, AVATAR_IMAGE_SIZE, AVATAR_IMAGE_SIZE), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        // Re-encoding intentionally drops location and other source metadata.
        for (quality in intArrayOf(90, 80, 65, 50, 35, 20)) {
            val bytes = ByteArrayOutputStream().use { outputBytes ->
                check(output.compress(Bitmap.CompressFormat.JPEG, quality, outputBytes)) { "无法保存头像" }
                outputBytes.toByteArray()
            }
            if (bytes.size <= MAX_AVATAR_IMAGE_BYTES) {
                return AVATAR_IMAGE_DATA_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
        throw IllegalArgumentException("图片内容过于复杂，请换一张照片")
    } finally {
        square?.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
    }
}

private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
    ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
    ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
    ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
    ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
    ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
    else -> null
}

private val avatarCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    // Evicted images may still be drawn by Compose. Let the GC release them instead of recycling.
}

/** Called off the main thread. Only normalized, bounded embedded JPEGs are accepted. */
internal fun avatarBitmap(image: String): Bitmap? = runCatching {
    if (image.isBlank()) return null
    val key = MessageDigest.getInstance("SHA-256").digest(image.toByteArray()).joinToString("") { "%02x".format(it) }
    avatarCache.get(key)?.let { return it }
    val bytes = decodeAvatarImageData(image) ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (bitmap.width != AVATAR_IMAGE_SIZE || bitmap.height != AVATAR_IMAGE_SIZE) {
        bitmap.recycle()
        return null
    }
    avatarCache.put(key, bitmap)
    bitmap
}.getOrNull()
