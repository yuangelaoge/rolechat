package cn.rolechat.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.rolechat.app.data.avatarBitmap
import cn.rolechat.app.data.normalizeAvatarImage
import cn.rolechat.app.ui.AvatarPhotoPicker
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dispatch local picker results through Android's standard registry; no production test hooks. */
@RunWith(AndroidJUnit4::class)
class AvatarPhotoPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectedPhotoIsCopiedToDraftAndRemoveClearsOnlyTheDraft() {
        val fixture = fixture(photo())
        val draft = AtomicReference("")
        val calls = AtomicInteger()
        val picker = PickerResult(Uri.fromFile(fixture))
        val loadingEvents = Collections.synchronizedList(mutableListOf<Boolean>())
        try {
            showPicker(picker, "", draft, calls, loadingEvents)
            compose.onNodeWithTag("test_avatar_choose").performClick()
            compose.waitUntil(5_000) { draft.get().isNotBlank() }
            assertTrue(picker.imagesOnly)
            assertEquals(1, calls.get())
            compose.runOnIdle { assertEquals(listOf(true, false), loadingEvents.toList()) }
            assertNotNull(avatarBitmap(draft.get()))
            fixture.delete()
            assertNotNull("The copied avatar must survive source deletion", avatarBitmap(draft.get()))
            compose.onNodeWithTag("test_avatar_remove").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("", draft.get()); assertEquals(2, calls.get()) }
            compose.onNodeWithTag("test_avatar_remove").assertDoesNotExist()
        } finally { fixture.delete() }
    }

    @Test fun cancellingTheGalleryLeavesExistingPhotoAndDraftUntouched() {
        val original = normalizeAvatarImage(photo())
        val draft = AtomicReference(original)
        val calls = AtomicInteger()
        showPicker(PickerResult(null), original, draft, calls)
        compose.onNodeWithTag("test_avatar_choose").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(original, draft.get())
            assertEquals(0, calls.get())
        }
        compose.onNodeWithTag("test_avatar_error").assertDoesNotExist()
    }

    @Test fun corruptedSelectedPhotoShowsErrorWithoutReplacingExistingPhoto() {
        val fixture = fixture("not a photo".toByteArray())
        val original = normalizeAvatarImage(photo())
        val draft = AtomicReference(original)
        val calls = AtomicInteger()
        val loadingEvents = Collections.synchronizedList(mutableListOf<Boolean>())
        try {
            showPicker(PickerResult(Uri.fromFile(fixture)), original, draft, calls, loadingEvents)
            compose.onNodeWithTag("test_avatar_choose").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("test_avatar_error"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("test_avatar_error").assertIsDisplayed()
            assertEquals(original, draft.get())
            assertEquals(0, calls.get())
            compose.runOnIdle { assertEquals(listOf(true, false), loadingEvents.toList()) }
        } finally { fixture.delete() }
    }

    private fun showPicker(registry: PickerResult, initial: String, draft: AtomicReference<String>, calls: AtomicInteger,
        loadingEvents: MutableList<Boolean> = mutableListOf()) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            var current by remember { mutableStateOf(initial) }
            MaterialTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AvatarPhotoPicker(current, {
                        current = it
                        draft.set(it)
                        calls.incrementAndGet()
                    }, testTagPrefix = "test_avatar", onLoadingChange = { loadingEvents.add(it) })
                }
            }
        }
    }

    private class PickerResult(private val result: Uri?) : ActivityResultRegistry() {
        var imagesOnly = false
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            imagesOnly = contract is ActivityResultContracts.PickVisualMedia &&
                (input as? PickVisualMediaRequest)?.mediaType == ActivityResultContracts.PickVisualMedia.ImageOnly
            Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, result) }
        }
    }

    private fun fixture(bytes: ByteArray): File = File.createTempFile("avatar-picker-", ".png", compose.activity.cacheDir)
        .apply { writeBytes(bytes) }

    private fun photo(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
