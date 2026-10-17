package cn.rolechat.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import cn.rolechat.app.data.AppJson
import cn.rolechat.app.data.ChatDatabase
import cn.rolechat.app.data.SecretStore
import cn.rolechat.app.data.SnapshotEntity
import cn.rolechat.app.data.providerSecretId
import cn.rolechat.core.*
import cn.rolechat.core.Character
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Optional native end-to-end test. Skipped unless the caller seeds the private file
 * files/live-test-key. Never package that file or a real credential with the app/tests.
 */
@RunWith(AndroidJUnit4::class)
class LiveConnectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realNativeMessageStreamsToCompletionWhenPrivateCredentialIsProvided() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyFile = File(context.filesDir, "live-test-key")
        assumeTrue("Optional live credential was not provided", keyFile.isFile)
        val supplied = try { keyFile.readText().trim() } finally { keyFile.delete() }
        assumeTrue("Optional live credential was empty", supplied.isNotEmpty())
        val secrets = SecretStore(context)
        val credentialId = providerSecretId(Provider(id = "siliconflow"))
        val previousCredential = secrets.get(credentialId)
        val db = ChatDatabase.open(context)
        val previousSnapshot = runBlocking { db.snapshotDao().read() }
        val liveActor = Character(id = "live-native-role", name = "联网测试角色",
            prompt = "你是友好的聊天伙伴，只用一句简短中文回答。", dualModel = false,
            primary = ModelConfig(thinking = ThinkingMode.OFF, maxTokens = 128))
        val room = ChatRoom(id = "live-native-room", name = "原生联网验收", direct = true,
            members = listOf(Member(liveActor.id, 0L, true)))
        val initial = AppState(characters = listOf(liveActor), rooms = listOf(room))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            secrets.put(credentialId, supplied)
            assertFalse("Plaintext seed must be removed before launch", keyFile.exists())
            runBlocking { db.snapshotDao().write(SnapshotEntity(payload = AppJson.encodeToString(initial))) }
            lateinit var vm: AppViewModel
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(20_000) { vm.ready.value }
            compose.onNodeWithText(room.name).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("你好，请用一句简短中文打个招呼。")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(120_000) {
                vm.state.value.messages.any { it.characterId == liveActor.id &&
                    it.status in setOf(MessageStatus.COMPLETE, MessageStatus.ERROR, MessageStatus.INTERRUPTED) }
            }
            val reply = vm.state.value.messages.last { it.characterId == liveActor.id }
            assertEquals("Native live request did not finish: ${reply.error}", MessageStatus.COMPLETE, reply.status)
            assertTrue("Expected streamed response text", reply.text.isNotBlank())
            assertEquals(1, reply.segments.size)
            compose.onNodeWithText(reply.text).assertIsDisplayed()
            closeSoftKeyboard()
            compose.waitUntil(5_000) { room.id !in vm.runtime.value.busyRooms }
            compose.waitForIdle()
            // Capture the completed chat only; credential inputs are never displayed here.
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            checkNotNull(bitmap) { "Could not capture native chat proof" }
            File(context.filesDir, "live-proof.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        } finally {
            scenario?.close()
            secrets.put(credentialId, previousCredential)
            runBlocking {
                db.snapshotDao().write(previousSnapshot ?: SnapshotEntity(payload = AppJson.encodeToString(AppState())))
            }
            db.close()
            keyFile.delete()
        }
    }
}
