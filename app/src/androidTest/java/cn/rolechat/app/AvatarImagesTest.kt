package cn.rolechat.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.rolechat.app.data.avatarBitmap
import cn.rolechat.app.data.importAvatarImage
import cn.rolechat.app.data.normalizeAvatarImage
import cn.rolechat.core.AVATAR_IMAGE_DATA_PREFIX
import cn.rolechat.core.AVATAR_IMAGE_SIZE
import cn.rolechat.core.MAX_AVATAR_IMAGE_BYTES
import cn.rolechat.core.decodeAvatarImageData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only generated local fixtures; photo data never leaves the device. */
@RunWith(AndroidJUnit4::class)
class AvatarImagesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rectangularPhotoIsCenteredSquareAndIndependentOfOriginalUri() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val source = photo(512, 256) { canvas, paint ->
            canvas.drawColor(Color.GREEN)
            paint.color = Color.RED
            canvas.drawRect(0f, 0f, 128f, 256f, paint)
            paint.color = Color.BLUE
            canvas.drawRect(384f, 0f, 512f, 256f, paint)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "rolechat-avatar-test-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RoleChatAvatarTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val saved: String
        try {
            resolver.openOutputStream(uri)!!.use { it.write(source) }
            saved = importAvatarImage(resolver, uri)
        } finally {
            resolver.delete(uri, null, null)
        }
        // Rendering the saved avatar does not reopen a provider URI or need retained permissions.
        assertTrue(saved.startsWith(AVATAR_IMAGE_DATA_PREFIX))
        assertFalse(saved.contains("content://"))
        assertTrue(requireNotNull(decodeAvatarImageData(saved)).size <= MAX_AVATAR_IMAGE_BYTES)
        val output = requireNotNull(avatarBitmap(saved))
        assertEquals(AVATAR_IMAGE_SIZE, output.width)
        assertEquals(AVATAR_IMAGE_SIZE, output.height)
        assertColor(Color.GREEN, output.getPixel(16, 128))
        assertColor(Color.GREEN, output.getPixel(240, 128))
    }

    @Test fun allExifOrientationsAreAppliedAndLocationMetadataIsStripped() {
        val original = photo(256, 256, Bitmap.CompressFormat.JPEG) { canvas, paint ->
            val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            for (row in 0..1) for (column in 0..1) {
                paint.color = colors[row * 2 + column]
                canvas.drawRect(column * 128f, row * 128f, (column + 1) * 128f, (row + 1) * 128f, paint)
            }
        }
        // Source quadrants after EXIF transformation, read top-left, top-right, bottom-left, bottom-right.
        val expected = listOf(
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            intArrayOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            intArrayOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            intArrayOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            intArrayOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            intArrayOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
        )
        val file = File.createTempFile("avatar-orientation-", ".jpg", context.cacheDir)
        try {
            for (orientation in 1..8) {
                file.writeBytes(original)
                ExifInterface(file.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,12/1,0/1")
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                    setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,24/1,0/1")
                    setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                    saveAttributes()
                }
                val saved = normalizeAvatarImage(file.readBytes())
                val output = requireNotNull(avatarBitmap(saved))
                for (index in 0..3) {
                    assertColor(expected[orientation - 1][index], output.getPixel(
                        if (index % 2 == 0) 64 else 192, if (index < 2) 64 else 192))
                }
                val bytes = requireNotNull(decodeAvatarImageData(saved))
                val exif = ExifInterface(ByteArrayInputStream(bytes))
                assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
                // A re-encoded JPEG may omit the tag or expose EXIF's undefined
                // value. Pixels above must already be upright in both cases.
                assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                    in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
            }
        } finally {
            file.delete()
        }
    }

    @Test fun wideCameraPhotoIsDownsampledBeforeCroppingAndRemainsBounded() {
        val source = photo(8192, 512, Bitmap.CompressFormat.JPEG) { canvas, _ -> canvas.drawColor(Color.BLUE) }
        val saved = normalizeAvatarImage(source)
        val bitmap = requireNotNull(avatarBitmap(saved))
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        assertColor(Color.BLUE, bitmap.getPixel(128, 128))
        assertTrue(requireNotNull(decodeAvatarImageData(saved)).size <= 64 * 1024)
    }

    @Test fun invalidOrOversizedImagesAreRejectedAndInvalidStoredImagesUseFallback() {
        assertThrows(IllegalArgumentException::class.java) { normalizeAvatarImage("not an image".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { normalizeAvatarImage(ByteArray(20 * 1024 * 1024 + 1)) }
        assertNull(avatarBitmap(""))
        assertNull(avatarBitmap("content://expired/photo"))
        assertNull(avatarBitmap("data:image/jpeg;base64,bm90IGFuIGltYWdl"))
    }

    private fun photo(
        width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        draw: (Canvas, Paint) -> Unit
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap), Paint())
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), 25.0)
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), 25.0)
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), 25.0)
    }
}
