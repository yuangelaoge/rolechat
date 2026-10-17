package cn.rolechat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.rolechat.app.ui.RoleChatApp
import cn.rolechat.core.*
import cn.rolechat.core.Character
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI contracts use fake actions and memory-only state; no HTTP, audio, or real credentials. */
@RunWith(AndroidJUnit4::class)
class RoleChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: StateStore
    private lateinit var actions: FakeActions
    private lateinit var runtime: MutableStateFlow<RuntimeUi>

    @Before fun showApp() {
        // Match MainActivity: the IME resizes the viewport instead of panning the
        // entire test host (which would move the top bar and new bubbles offscreen).
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(0xFF111111.toInt()),
                navigationBarStyle = SystemBarStyle.dark(0xFF111111.toInt())
            )
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        store = StateStore()
        runtime = MutableStateFlow(RuntimeUi(configuredProviders = setOf("siliconflow")))
        actions = FakeActions(store, runtime)
        compose.setContent {
            val state by store.state.collectAsState()
            val currentRuntime by runtime.collectAsState()
            RoleChatApp(state, currentRuntime, actions)
        }
    }

    @Test fun directorBoxSavesCancelsAppliesAndClearsWithoutChangingUserDraft() {
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("这是我的台词")
        compose.onNodeWithContentDescription("剧情指令").performClick()
        compose.onNodeWithText("System · 剧情指令").assertIsDisplayed()
        compose.onNodeWithTag("director-apply").assertIsNotEnabled()
        compose.onNodeWithTag("director-input").performTextInput("直接转到第二天早晨")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("直接转到第二天早晨", store.snapshot().rooms.first().directorInstruction) }
        compose.onNodeWithTag("chat-input").assertTextContains("这是我的台词")
        compose.onNodeWithContentDescription("剧情指令").performClick()
        compose.onNodeWithTag("director-input").assertTextContains("直接转到第二天早晨")
        capturePreview("system-director-0.1.19.png")
        compose.onNodeWithTag("director-input").performTextReplacement("不要保存这条")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("直接转到第二天早晨", store.snapshot().rooms.first().directorInstruction) }
        compose.onNodeWithContentDescription("剧情指令").performClick()
        compose.onNodeWithTag("director-input").performTextReplacement("让林间先说话")
        compose.onNodeWithTag("director-input").performClick()
        assertEditActionsVisibleAboveKeyboard(listOf("director-cancel", "director-save", "director-apply", "director-clear"))
        capturePreview("system-director-keyboard-0.1.19.png")
        compose.onNodeWithTag("director-apply").performClick()
        compose.runOnIdle { assertEquals("让林间先说话", store.snapshot().rooms.first().directorInstruction) }
        compose.onNodeWithContentDescription("剧情指令").performClick()
        compose.onNodeWithText("清空指令").performClick()
        compose.runOnIdle { assertEquals("", store.snapshot().rooms.first().directorInstruction) }
        compose.onNodeWithTag("chat-input").assertTextContains("这是我的台词")
    }

    @Test fun directorRecordsHaveNoUserAvatarOrRegenerateMenu() {
        compose.runOnIdle { store.update { it.copy(messages = listOf(ChatMessage(id = "direction", roomId = "lobby",
            senderName = "System · 剧情指令", text = "切换到早晨", isDirector = true))) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("director-event-direction").assertIsDisplayed()
        compose.onNodeWithTag("message-avatar-direction").assertDoesNotExist()
        compose.onNodeWithText("查看当前指令").performClick()
        compose.onNodeWithText("System · 剧情指令").assertIsDisplayed()
    }

    @Test fun openGroupMentionRoleAndSendMessage() {
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithContentDescription("选择要提醒的角色").performClick()
        compose.onNodeWithText("@林间").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("chat-input").assertIsFocused()
        compose.onNodeWithText("已选 1 位角色").assertIsDisplayed().performClick()
        compose.onNodeWithText("@林间").assertIsSelected()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("@林间").assertDoesNotExist()
        compose.onNodeWithTag("chat-input").performTextInput("你好，今天聊点什么？")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Sent("lobby", "你好，今天聊点什么？", setOf("lin"))), actions.sent)
        }
        compose.onNodeWithText("你好，今天聊点什么？").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun roomModeCanSwitchToParallelAndBackToSequential() {
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openRoomSection("回复方式")
        compose.onNodeWithText("并发回复").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(ReplyMode.PARALLEL, actions.savedRooms.last().mode) }
        compose.onNodeWithText("并发回复").assertDoesNotExist()
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithText("并发回复").assertIsDisplayed()
        compose.onNodeWithContentDescription("回复方式").performClick()
        compose.onNodeWithText("顺序回复").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(ReplyMode.SEQUENTIAL, actions.savedRooms.last().mode) }
        compose.onNodeWithText("顺序回复").assertDoesNotExist()
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithText("顺序回复").assertIsDisplayed()
    }

    @Test fun avatarsEditTheExactHistoricalRoleAndCurrentRoomIdentityWithoutLosingDraft() {
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            store.update { state -> state.copy(characters = state.characters + state.characters.first().copy(id = "former", prompt = "同名但不同角色的独立人设"),
                messages = listOf(
                    ChatMessage(id = "former-avatar", roomId = "lobby", characterId = "former", senderName = "林间", text = "我以前在这个群聊过。", createdAt = time),
                    ChatMessage(id = "human-avatar", roomId = "lobby", senderName = "旧身份", text = "这条消息保留原来的署名。", createdAt = time + 1))) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("还没发出的草稿")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("message-avatar-former-avatar").performClick()
        field("角色提示词").assertEditableValue("同名但不同角色的独立人设")
        field("角色名称").performTextReplacement("曾经的群友")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithTag("chat-input").assertEditableValue("还没发出的草稿")
        compose.runOnIdle {
            assertEquals("former", actions.savedCharacters.single().id)
            assertEquals("林间", store.snapshot().characters.single { it.id == "lin" }.name)
            assertTrue(store.snapshot().rooms.single { it.id == "lobby" }.members.none { it.characterId == "former" })
        }
        compose.onNodeWithTag("message-avatar-human-avatar").performClick()
        field("我的名字").assertEditableValue("我")
        field("我的名字").performTextReplacement("新的群内身份")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithTag("chat-input").assertEditableValue("还没发出的草稿")
        compose.runOnIdle {
            assertEquals("新的群内身份", actions.savedRooms.single().userName)
            assertEquals("旧身份", store.snapshot().messages.single { it.id == "human-avatar" }.senderName)
            assertTrue(actions.sent.isEmpty())
        }
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithTag("room-member-avatar-lin").performClick()
        field("角色名称").assertEditableValue("林间")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("chat-input").assertEditableValue("还没发出的草稿")
        compose.onNodeWithTag("room-action-sheet").assertDoesNotExist()
    }

    @Test fun uploadedAvatarsRenderInChatHomeMembersAndEditorsWithoutChangingAlignment() {
        val rolePhoto = avatarFixture(0xFFE65B4B.toInt())
        val userPhoto = avatarFixture(0xFF3A9BE0.toInt())
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            store.update { state -> state.copy(
                characters = state.characters.map { if (it.id == "lin") it.copy(avatarImage = rolePhoto) else it },
                rooms = state.rooms.map { it.copy(userAvatarImage = userPhoto) } +
                    ChatRoom(id = "photo-direct", name = "头像单聊", direct = true, members = listOf(Member("lin"))),
                messages = listOf(
                    ChatMessage(id = "photo-role", roomId = "lobby", characterId = "lin", senderName = "林间", text = "换了一张新头像。", createdAt = time),
                    ChatMessage(id = "photo-user", roomId = "lobby", senderName = "我", text = "我的头像也换好了。", createdAt = time + 1))) }
        }
        assertPhotoWithin("chat-list-avatar-lobby", 0xFFE65B4B.toInt())
        assertPhotoWithin("chat-list-avatar-photo-direct", 0xFFE65B4B.toInt())
        compose.onNodeWithTag("chat-list-row-lobby").performClick()
        assertPhotoWithin("message-avatar-photo-role", 0xFFE65B4B.toInt())
        assertPhotoWithin("message-avatar-photo-user", 0xFF3A9BE0.toInt())
        assertAvatarAlignedWithBubble("photo-role")
        assertAvatarAlignedWithBubble("photo-user")
        capturePreview("photo-avatars-chat-preview.png")
        compose.onNodeWithTag("message-avatar-photo-role").performClick()
        assertPhotoWithin("character-avatar-preview", 0xFFE65B4B.toInt())
        compose.onNodeWithTag("character-avatar_choose").assertIsDisplayed()
        compose.onNodeWithTag("character-avatar_remove").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("message-avatar-photo-user").performClick()
        assertPhotoWithin("user-avatar-preview", 0xFF3A9BE0.toInt())
        compose.onNodeWithTag("user-avatar_choose").assertIsDisplayed()
        capturePreview("photo-avatars-identity-preview.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("会话设置").performClick()
        assertPhotoWithin("room-member-avatar-lin", 0xFFE65B4B.toInt())
    }

    @Test fun removingAvatarIsADraftUntilSavedAndUserPhotosStayWithinTheirRoom() {
        val rolePhoto = avatarFixture(0xFFE65B4B.toInt())
        val userPhoto = avatarFixture(0xFF3A9BE0.toInt())
        val otherUserPhoto = avatarFixture(0xFFAC67C4.toInt())
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            store.update { state -> state.copy(
                characters = state.characters.map { if (it.id == "lin") it.copy(avatarImage = rolePhoto) else it },
                rooms = state.rooms.map { it.copy(userAvatarImage = userPhoto) } +
                    ChatRoom(id = "other-photo-room", name = "另一个身份", members = listOf(Member("lin")), userAvatarImage = otherUserPhoto),
                messages = listOf(
                    ChatMessage(id = "draft-photo-role", roomId = "lobby", characterId = "lin", senderName = "林间", text = "改头像不改聊天。", createdAt = time),
                    ChatMessage(id = "draft-photo-user", roomId = "lobby", senderName = "我", text = "保留这条消息。", createdAt = time + 1),
                    ChatMessage(id = "other-photo-user", roomId = "other-photo-room", senderName = "另一种身份", text = "这个群的头像独立。", createdAt = time + 2))) }
        }
        compose.onNodeWithTag("chat-list-row-lobby").performClick()
        compose.onNodeWithTag("message-avatar-draft-photo-role").performClick()
        compose.onNodeWithTag("character-avatar_remove").performClick()
        compose.onNodeWithTag("character-avatar_remove").assertDoesNotExist()
        compose.runOnIdle { assertEquals(rolePhoto, store.snapshot().characters.single { it.id == "lin" }.avatarImage) }
        compose.onNodeWithContentDescription("返回").performClick()
        assertPhotoWithin("message-avatar-draft-photo-role", 0xFFE65B4B.toInt())
        compose.onNodeWithTag("message-avatar-draft-photo-role").performClick()
        compose.onNodeWithTag("character-avatar_remove").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("", actions.savedCharacters.single().avatarImage) }
        compose.onNode(hasContentDescription("头像照片") and hasAnyAncestor(hasTestTag("message-avatar-draft-photo-role")), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("message-avatar-draft-photo-user").performClick()
        compose.onNodeWithTag("user-avatar_remove").performClick()
        compose.runOnIdle { assertEquals(userPhoto, store.snapshot().rooms.single { it.id == "lobby" }.userAvatarImage) }
        compose.onNodeWithContentDescription("返回").performClick()
        assertPhotoWithin("message-avatar-draft-photo-user", 0xFF3A9BE0.toInt())
        compose.onNodeWithTag("message-avatar-draft-photo-user").performClick()
        compose.onNodeWithTag("user-avatar_remove").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("", actions.savedRooms.single().userAvatarImage)
            assertEquals(otherUserPhoto, store.snapshot().rooms.single { it.id == "other-photo-room" }.userAvatarImage)
            assertEquals(3, store.snapshot().messages.size)
        }
        compose.onNode(hasContentDescription("头像照片") and hasAnyAncestor(hasTestTag("message-avatar-draft-photo-user")), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("chat-list-row-other-photo-room").performClick()
        assertPhotoWithin("message-avatar-other-photo-user", 0xFFAC67C4.toInt())
    }

    @Test fun sceneSignatureOpensItsRoleAndDeletedRoleAvatarDoesNotNavigate() {
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            store.update { state -> state.copy(characters = state.characters + Character(id = "scene-avatar", name = "街角旁白", messageStyle = MessageStyle.SCENE),
                messages = listOf(
                    ChatMessage(id = "scene-author", roomId = "lobby", characterId = "scene-avatar", senderName = "街角旁白", text = "街边的灯亮了。", messageStyle = MessageStyle.SCENE, createdAt = time),
                    ChatMessage(id = "deleted-avatar", roomId = "lobby", characterId = "gone", senderName = "已删除的角色", text = "保留的历史消息。", createdAt = time + 1))) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("message-sender-scene-author", useUnmergedTree = true).performClick()
        field("角色名称").assertEditableValue("街角旁白")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("message-sender-scene-author", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-actions-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("思考内容（COT）").assertHasClickAction()
        Espresso.pressBack()
        compose.onNodeWithTag("message-text-scene-author", useUnmergedTree = true).performClick()
        compose.onNodeWithText("回复详情").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("message-avatar-deleted-avatar", useUnmergedTree = true).assertHasNoClickAction()
            .performTouchInput { click() }
        compose.onNodeWithTag("chat-toolbar").assertIsDisplayed()
        compose.onNodeWithText("编辑角色").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择要提醒的角色").performClick()
        compose.onNodeWithText("@林间").performClick().assertIsSelected()
        compose.onNodeWithText("编辑角色").assertDoesNotExist()
    }

    @Test fun returningFromAvatarEditorPreservesTheHistoricalReadingPosition() {
        val time = System.currentTimeMillis()
        compose.runOnIdle { store.update { state -> state.copy(messages = (0 until 32).map { index ->
            ChatMessage(id = "avatar-history-$index", roomId = "lobby", characterId = "lin", senderName = "林间",
                text = "第 $index 条旧消息：一起聊聊散步时看到的风景。", createdAt = time + index)
        }) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        repeat(2) {
            compose.onNodeWithTag("chat-messages").performTouchInput { swipeDown(durationMillis = 500) }
            compose.waitForIdle()
        }
        val viewport = bounds("chat-messages")
        val matcher = SemanticsMatcher("头像导航前的旧消息") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("message-avatar-history-") == true
        }
        val anchor = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.boundsInRoot.top >= viewport.top + px(4f) && it.boundsInRoot.bottom <= viewport.bottom - px(4f) }
        val tag = anchor.config[SemanticsProperties.TestTag]
        val id = tag.removePrefix("message-")
        val top = anchor.boundsInRoot.top
        compose.onNodeWithTag("message-avatar-$id", useUnmergedTree = true).performClick()
        field("角色名称").assertEditableValue("林间")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        assertEquals("返回角色设置后继续阅读原来的历史位置", top, bounds(tag).top, px(1f))
        compose.onNodeWithTag("message-avatar-history-31", useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test fun compressionUsesItsOwnModelAndAppliesEveryEditedRoleOnlyAfterConfirmation() {
        val before = seedCompressionConversation()
        compose.runOnIdle {
            store.update { it.copy(providers = it.providers + Provider(id = "summary-models", name = "前情专用服务", baseUrl = "https://summary.example/v1")) }
            runtime.value = runtime.value.copy(configuredProviders = setOf("siliconflow", "summary-models"),
                modelLists = mapOf("summary-models" to listOf("summary/compact", "summary/style"), "siliconflow" to listOf("chat/unrelated")))
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("留在原聊天里的草稿")
        Espresso.closeSoftKeyboard()
        openCompression()
        compose.onNodeWithTag("compression-keep-recent").assertEditableValue("20")
        compose.onNodeWithTag("compression-range").assertTextEquals("可整理 2 位角色 · 6 条旧消息")
        capturePreview("manual-compression-settings.png")
        compose.onNodeWithTag("compression-keep-recent").performTextReplacement("3")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("compression-provider").performScrollTo().performClick()
        compose.onNodeWithText("前情专用服务").performClick()
        compose.onNodeWithTag("refresh-compression-models").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("summary-models"), actions.modelListRefreshes) }
        compose.onNodeWithTag("choose-compression-model").performScrollTo().performClick()
        compose.onNodeWithText("summary/style").performClick()
        compose.onNodeWithTag("compression-model").assertEditableValue("summary/style")
        compose.onNodeWithTag("compression-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("compression-prompt").performScrollTo().performTextReplacement("保留不同人物的语气，只整理已有内容。")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("reset-compression-prompt").performScrollTo().performClick()
        compose.onNodeWithTag("compression-prompt").assertEditableValue(DEFAULT_COMPRESSION_PROMPT)
        compose.onNodeWithTag("generate-compression").performClick()
        compose.onNodeWithTag("compression-progress").assertIsDisplayed()
        compose.runOnIdle {
            val settings = actions.generatedCompressions.single().second
            assertEquals(3, settings.keepRecentMessages)
            assertEquals("summary-models", settings.model.providerId)
            assertEquals("summary/style", settings.model.model)
            assertEquals(before.characters, store.snapshot().characters)
            assertTrue(store.snapshot().contextSummaries.isEmpty())
        }
        completeCompressionPreview()
        compose.onNodeWithTag("compression-draft-lin").performTextReplacement("林间专属前情：保留散步的约定和语气。")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("compression-draft-role").performClick()
        compose.onNodeWithText("夏至").performClick()
        compose.onNodeWithTag("compression-draft-xia").performTextReplacement("夏至专属前情：保留讨论电影时的分歧。")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("compression-draft-role").performClick()
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithTag("compression-draft-lin").assertEditableValue("林间专属前情：保留散步的约定和语气。")
        capturePreview("manual-compression-drafts.png")
        val previewId = compose.runOnIdle {
            assertTrue(actions.appliedCompressions.isEmpty())
            assertTrue(store.snapshot().contextSummaries.isEmpty())
            runtime.value.compressions.getValue("lobby").id
        }
        compose.onNodeWithTag("apply-compression").performClick()
        compose.runOnIdle {
            val applied = actions.appliedCompressions.single()
            assertEquals("lobby", applied.first)
            assertEquals(previewId, applied.second)
            assertEquals(mapOf("lin" to "林间专属前情：保留散步的约定和语气。", "xia" to "夏至专属前情：保留讨论电影时的分歧。"), applied.third)
            assertEquals(before.messages, store.snapshot().messages)
            assertEquals(2, store.snapshot().contextSummaries.size)
        }
        compose.onNodeWithTag("existing-compression-status").assertTextContains("正在使用", substring = true)
        compose.onNodeWithTag("compression-back").performClick()
        compose.onNodeWithTag("chat-input").assertEditableValue("留在原聊天里的草稿")
    }

    @Test fun compressionCancellationAndErrorsNeverApplyDraftsOrDeleteTheOriginalConversation() {
        val before = seedCompressionConversation()
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openCompression()
        compose.onNodeWithTag("generate-compression").performClick()
        compose.onNodeWithTag("cancel-compression").performClick()
        compose.runOnIdle {
            assertEquals(listOf("lobby"), actions.cancelledCompressions)
            assertTrue(runtime.value.compressions.isEmpty())
            assertTrue(store.snapshot().contextSummaries.isEmpty())
        }
        compose.onNodeWithTag("generate-compression").performClick()
        completeCompressionPreview()
        compose.onNodeWithTag("compression-draft-lin").performTextReplacement(" ")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("apply-compression").assertIsNotEnabled()
        compose.onNodeWithTag("compression-back").performClick()
        compose.onNodeWithTag("chat-toolbar").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(runtime.value.compressions.isEmpty())
            assertTrue(actions.appliedCompressions.isEmpty())
            assertEquals(before.messages, store.snapshot().messages)
        }
        openCompression()
        compose.onNodeWithTag("generate-compression").performClick()
        compose.runOnIdle {
            val progress = runtime.value.compressions.getValue("lobby")
            runtime.value = runtime.value.copy(compressions = mapOf("lobby" to progress.copy(status = CompressionStatus.ERROR, error = "前情模型暂时不可用")))
        }
        compose.onNodeWithTag("compression-error").assertTextEquals("前情模型暂时不可用")
        compose.onNodeWithTag("apply-compression").assertDoesNotExist()
        compose.onNodeWithTag("generate-compression").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(before.messages, store.snapshot().messages)
            assertTrue(store.snapshot().contextSummaries.isEmpty())
        }
    }

    @Test fun compressionValidatesRecentCountModelCredentialsAndBusyChatBeforeGenerating() {
        seedCompressionConversation(count = 4)
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openCompression()
        compose.onNodeWithTag("generate-compression").assertIsNotEnabled()
        compose.onNodeWithTag("compression-keep-recent").performTextReplacement("201")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("generate-compression").assertIsNotEnabled()
        compose.onNodeWithTag("compression-keep-recent").performTextReplacement("1")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("generate-compression").assertIsEnabled()
        compose.runOnIdle { runtime.value = runtime.value.copy(busyRooms = setOf("lobby")) }
        compose.onNodeWithTag("generate-compression").assertIsNotEnabled()
        compose.runOnIdle { runtime.value = runtime.value.copy(busyRooms = emptySet(), configuredProviders = emptySet()) }
        compose.onNodeWithTag("generate-compression").assertIsNotEnabled()
        compose.runOnIdle { runtime.value = runtime.value.copy(configuredProviders = setOf("siliconflow")) }
        compose.onNodeWithTag("compression-model").performScrollTo().performTextClearance()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("generate-compression").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.generatedCompressions.isEmpty()) }
    }

    @Test fun directChatCanInspectSavedCompressionAndRestoreItsFullContextAfterConfirmation() {
        val before = seedCompressionConversation(direct = true)
        compose.runOnIdle {
            val settings = CompressionSettings(keepRecentMessages = 3)
            val plans = buildCompressionPlans(store.snapshot(), "lobby", settings)
            store.update { applyCompressionDrafts(it, settings, plans, plans.associate { plan -> plan.characterId to "已保存前情：一起看电影的约定。" }) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openCompression()
        compose.onNodeWithTag("existing-compression").performScrollTo().performClick()
        compose.onNodeWithTag("existing-compression-text").performScrollTo().assertTextEquals("已保存前情：一起看电影的约定。")
        compose.onNodeWithTag("clear-compression").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, store.snapshot().contextSummaries.size) }
        compose.onNodeWithTag("clear-compression").performClick()
        compose.onNodeWithTag("confirm-clear-compression").performClick()
        compose.runOnIdle {
            assertEquals(listOf("lobby"), actions.clearedCompressions)
            assertTrue(store.snapshot().contextSummaries.isEmpty())
            assertEquals(before.messages, store.snapshot().messages)
            assertTrue(store.snapshot().rooms.single { it.id == "lobby" }.direct)
        }
        compose.onNodeWithTag("existing-compression").assertDoesNotExist()
    }

    @Test fun smartReplySettingsSaveValidateAndPreserveLegacySchedulingWhenSwitchedBack() {
        val legacy = compose.runOnIdle {
            store.update { state -> state.copy(
                providers = state.providers + Provider(id = "routing-test", name = "接话模型服务", baseUrl = "https://routing.example/v1"),
                rooms = state.rooms.map { it.copy(mode = ReplyMode.PARALLEL, interChat = true,
                    maxInitialReplies = 4, maxFollowUps = 7, shuffleOrder = true) }
            ) }
            store.snapshot().rooms.single { it.id == "lobby" }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openRoomSection("回复方式")
        compose.onNodeWithTag("reply-mode-SMART").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("第一批最多回复").assertDoesNotExist()
        compose.onNodeWithText("角色互聊").assertDoesNotExist()
        compose.onNodeWithTag("routing-provider").performScrollTo().performClick()
        compose.onNodeWithText("接话模型服务").performClick()
        compose.onNodeWithTag("routing-model").performScrollTo().performTextReplacement(" ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("routing-validation-error").assertExists()
        compose.onNodeWithTag("routing-model").performTextReplacement("test/routing-small")
        Espresso.closeSoftKeyboard()
        setRoutingSlider("routing-recent-messages", 12)
        setRoutingSlider("routing-max-replies", 4)
        compose.onNodeWithTag("routing-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("routing-prompt").performScrollTo().performTextReplacement(" ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("routing-validation-error").assertExists()
        val customPrompt = "判断下一位接话者；需要真人回答时选择 wait_user，只输出约定 JSON。"
        compose.onNodeWithTag("routing-prompt").performTextReplacement(customPrompt)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("routing-model").performScrollTo()
        capturePreview("smart-routing-settings-preview.png")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        val routing = compose.runOnIdle {
            val saved = actions.savedRooms.single()
            assertEquals(ReplyMode.SMART, saved.mode)
            assertEquals(legacy.interChat, saved.interChat)
            assertEquals(legacy.maxInitialReplies, saved.maxInitialReplies)
            assertEquals(legacy.maxFollowUps, saved.maxFollowUps)
            assertEquals(legacy.shuffleOrder, saved.shuffleOrder)
            assertEquals("routing-test", saved.routing.model.providerId)
            assertEquals("test/routing-small", saved.routing.model.model)
            assertEquals(customPrompt, saved.routing.systemPrompt)
            assertEquals(12, saved.routing.recentMessages)
            assertEquals(4, saved.routing.maxAutoReplies)
            saved.routing
        }
        openRoomSection("回复方式")
        compose.onNodeWithTag("reply-mode-SMART").assertIsSelected()
        compose.onNodeWithTag("routing-model").performScrollTo().assertEditableValue("test/routing-small")
        compose.onNodeWithTag("routing-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("routing-prompt").performScrollTo().assertEditableValue(customPrompt)
        compose.onNodeWithText("顺序回复").performScrollTo().performClick()
        compose.onNodeWithTag("routing-model").assertDoesNotExist()
        compose.onNodeWithText("第一批最多回复").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("角色互聊").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val saved = actions.savedRooms.last()
            assertEquals(legacy.copy(mode = ReplyMode.SEQUENTIAL, routing = routing), saved)
        }
        openRoomSection("回复方式")
        compose.onNodeWithTag("reply-mode-SMART").performScrollTo().performClick()
        compose.onNodeWithTag("routing-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("reset-routing-prompt").performScrollTo().performClick()
        compose.onNodeWithTag("routing-prompt").performScrollTo().assertEditableValue(DEFAULT_ROUTING_PROMPT)
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(routing.copy(systemPrompt = DEFAULT_ROUTING_PROMPT), actions.savedRooms.last().routing)
            assertTrue(actions.sent.isEmpty())
        }
    }

    @Test fun smartReplyModelPickerSearchesSelectedProviderAndRefreshesOnlyThatProvider() {
        compose.runOnIdle {
            store.update { state -> state.copy(providers = state.providers +
                Provider(id = "routing-list", name = "接话列表服务", baseUrl = "https://routing-list.example/v1")) }
            runtime.value = runtime.value.copy(
                configuredProviders = setOf("siliconflow", "routing-list"),
                modelLists = mapOf("siliconflow" to listOf("unrelated/chat-model"),
                    "routing-list" to listOf("test/router-fast", "test/router-small"))
            )
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openRoomSection("回复方式")
        compose.onNodeWithTag("reply-mode-SMART").performScrollTo().performClick()
        compose.onNodeWithTag("routing-provider").performScrollTo().performClick()
        compose.onNodeWithText("接话列表服务").performClick()
        compose.onNodeWithTag("refresh-routing-models").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("routing-list"), actions.modelListRefreshes)
            runtime.value = runtime.value.copy(loadingModels = setOf("routing-list"))
        }
        compose.onNodeWithTag("refresh-routing-models").assertIsNotEnabled()
        compose.runOnIdle { runtime.value = runtime.value.copy(loadingModels = emptySet()) }
        compose.onNodeWithTag("choose-routing-model").performScrollTo().performClick()
        compose.onNodeWithText("test/router-fast").assertIsDisplayed()
        compose.onNodeWithText("test/router-small").assertIsDisplayed()
        compose.onNodeWithText("unrelated/chat-model").assertDoesNotExist()
        field("搜索模型").performTextInput("small")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("test/router-fast").assertDoesNotExist()
        compose.onNodeWithText("test/router-small").performClick()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("routing-model").assertEditableValue("test/router-small")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("routing-list", actions.savedRooms.single().routing.model.providerId)
            assertEquals("test/router-small", actions.savedRooms.single().routing.model.model)
            assertTrue(actions.sent.isEmpty())
        }
    }

    @Test fun humanMessageShowsWaitForUserRoutingRecordAndCopiesOnlyVisibleDecision() {
        val record = RoutingRecord(status = RoutingStatus.DECIDED, action = RoutingAction.WAIT_USER,
            reason = "下一步需要顾言确认集合时间。", model = "test/routing-small",
            rawOutput = "synthetic-raw-routing-output-not-for-copy")
        val message = ChatMessage(id = "routing-human-wait", roomId = "lobby", senderName = "顾言",
            text = "等我确认明天几点方便。", routingDecision = record)
        compose.runOnIdle {
            store.update { it.copy(rooms = it.rooms.map { room -> room.copy(mode = ReplyMode.SMART) }, messages = listOf(message)) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("message-text-${message.id}", useUnmergedTree = true).assertTextEquals(message.text)
        compose.onNodeWithText(record.reason).assertDoesNotExist()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").assertDoesNotExist()
        compose.onNodeWithContentDescription("接话安排").performScrollTo().performClick()
        compose.onNodeWithTag("routing-sheet").assertIsDisplayed()
        compose.onNodeWithTag("routing-status").assertTextEquals("已完成判断")
        compose.onNodeWithTag("routing-action").assertTextEquals("等待你回复")
        compose.onNodeWithTag("routing-reason").assertTextEquals(record.reason)
        compose.onNodeWithTag("routing-source").assertTextEquals("判断模型 · test/routing-small")
        compose.onNodeWithTag("routing-error").assertDoesNotExist()
        compose.onNodeWithTag("routing-topic").assertDoesNotExist()
        capturePreview("routing-record-preview.png")
        setSyntheticClipboard("before-routing-copy")
        compose.onNodeWithTag("copy-routing-record").assertContentDescriptionEquals("复制接话记录")
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            val copied = clipboardText()
            assertTrue(copied.startsWith("接话安排\n状态：已完成判断\n安排：等待你回复\n依据：${record.reason}\n判断模型 · test/routing-small\n时间："))
            assertFalse("复制接话依据不能混入未经结构化的模型输出", copied.contains(record.rawOutput))
            assertFalse("复制接话记录不能混入聊天正文", copied.contains(message.text))
            assertFalse("旧记录没有话题判断时不能补判", copied.contains("本次话题判断"))
            assertEquals(listOf(message), store.snapshot().messages)
            assertTrue(actions.sent.isEmpty())
        }
        compose.onNodeWithTag("routing-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭接话安排").performClick()
        compose.onNodeWithTag("routing-sheet").assertDoesNotExist()
        compose.onNodeWithTag("message-text-${message.id}", useUnmergedTree = true).assertTextEquals(message.text)
    }

    @Test fun aiRoutingRecordKeepsItsChosenActorAndRoomMenuOpensLatestRecordedMessage() {
        val time = System.currentTimeMillis()
        val actor = ChatMessage(id = "routing-actor", roomId = "lobby", characterId = "xia", senderName = "夏至",
            text = "林间，你觉得哪条路线好？", createdAt = time,
            routingDecision = RoutingRecord(status = RoutingStatus.DECIDED, action = RoutingAction.ACTOR,
                targetId = "lin", selectedCharacterId = "lin", selectedName = "林间", source = RoutingSource.MENTION,
                reason = "明确点名了林间。", createdAt = time))
        val error = ChatMessage(id = "routing-error-message", roomId = "lobby", senderName = "顾言",
            text = "接着聊聊行程吧。", createdAt = time + 1,
            routingDecision = RoutingRecord(status = RoutingStatus.ERROR, action = RoutingAction.ACTOR,
                targetId = "unknown", model = "test/routing-offline",
                error = "智能接话服务请求失败，本轮已停止自动接话", rawOutput = "synthetic-error-output", createdAt = time + 1))
        val unrecorded = ChatMessage(id = "routing-later-unrecorded", roomId = "lobby", text = "稍后再确认。", createdAt = time + 2)
        compose.runOnIdle {
            store.update { it.copy(rooms = it.rooms.map { room -> room.copy(mode = ReplyMode.SMART) },
                messages = listOf(actor, error, unrecorded)) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${actor.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("接话安排").performScrollTo().performClick()
        compose.onNodeWithTag("routing-status").assertTextEquals("已完成判断")
        compose.onNodeWithTag("routing-action").assertTextEquals("已安排 林间 接话")
        compose.onNodeWithTag("routing-reason").assertTextEquals("明确点名了林间。")
        compose.onNodeWithTag("routing-source").assertTextEquals("来源 · 手动 @ 点名")
        setSyntheticClipboard("before-actor-routing-copy")
        compose.onNodeWithTag("copy-routing-record").performClick()
        compose.runOnIdle {
            assertTrue(clipboardText().contains("安排：已安排 林间 接话"))
            assertTrue(clipboardText().contains("来源 · 手动 @ 点名"))
            assertFalse(clipboardText().contains("已安排 夏至 接话"))
        }
        compose.onNodeWithContentDescription("关闭接话安排").performClick()
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithContentDescription("接话记录").performScrollTo().performClick()
        compose.onNodeWithTag("routing-sheet").assertIsDisplayed()
        compose.onNodeWithTag("routing-status").assertTextEquals("接话判断失败")
        compose.onNodeWithTag("routing-action").assertTextEquals("尚未安排下一位")
        compose.onNodeWithText("已安排 unknown 接话").assertDoesNotExist()
        compose.onNodeWithTag("routing-reason").assertDoesNotExist()
        compose.onNodeWithTag("routing-source").assertTextEquals("判断模型 · test/routing-offline")
        compose.onNodeWithTag("routing-error").assertTextEquals(error.routingDecision!!.error)
        setSyntheticClipboard("before-error-routing-copy")
        compose.onNodeWithTag("copy-routing-record").assertIsEnabled().performClick()
        compose.runOnIdle {
            val copied = clipboardText()
            assertTrue(copied.contains("状态：接话判断失败"))
            assertTrue(copied.contains("安排：尚未安排下一位"))
            assertFalse(copied.contains("已安排 unknown"))
            assertTrue(copied.contains("错误：${error.routingDecision!!.error}"))
            assertTrue(copied.contains("判断模型 · test/routing-offline"))
            assertFalse(copied.contains(error.routingDecision!!.rawOutput))
            assertEquals(listOf(actor, error, unrecorded), store.snapshot().messages)
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.regenerated.isEmpty())
        }
    }

    @Test fun routingEntryRequiresARecordAndOpenSheetUpdatesWhenDecisionIsCancelled() {
        val message = ChatMessage(id = "routing-live", roomId = "lobby", text = "请帮我安排一下。")
        compose.runOnIdle {
            store.update { it.copy(rooms = it.rooms.map { room -> room.copy(mode = ReplyMode.SMART) }, messages = listOf(message)) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("接话安排").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithContentDescription("接话记录").performScrollTo().performClick()
        compose.onNodeWithText("当前会话尚无接话记录。").assertIsDisplayed()
        compose.onNodeWithTag("copy-routing-record").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭接话安排").performClick()
        compose.runOnIdle { store.updateMessage(message.id) { it.copy(routingDecision = RoutingRecord(
            model = "test/routing-pending", action = RoutingAction.ACTOR, targetId = "unknown")) } }
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("接话安排").performScrollTo().performClick()
        compose.onNodeWithTag("routing-status").assertTextEquals("正在判断接话")
        compose.onNodeWithTag("routing-action").assertTextEquals("等待判断结果")
        compose.runOnIdle {
            store.updateMessage(message.id) { it.copy(routingDecision = it.routingDecision!!.copy(
                status = RoutingStatus.CANCELLED, source = RoutingSource.RULE, reason = "你已停止本轮接话。")) }
        }
        compose.onNodeWithTag("routing-status").assertTextEquals("接话判断已取消")
        compose.onNodeWithTag("routing-action").assertTextEquals("尚未安排下一位")
        compose.onNodeWithTag("routing-source").assertTextEquals("来源 · 会话规则")
        compose.onNodeWithTag("routing-reason").assertTextEquals("你已停止本轮接话。")
        compose.runOnIdle { assertTrue(actions.sent.isEmpty()) }
    }

    @Test fun routingTopicStatesAreCopiedWithoutClaimingCorrectionForUnscheduledReplies() {
        val record = RoutingRecord(status = RoutingStatus.DECIDED, action = RoutingAction.ACTOR,
            targetId = "lin", selectedCharacterId = "lin", selectedName = "林间", model = "test/topic-router",
            rawOutput = "synthetic-raw-topic-output-must-stay-hidden")
        val message = ChatMessage(id = "routing-topic-message", roomId = "lobby", characterId = "xia", senderName = "夏至",
            text = "举个例子，这家店的菜单就很清楚。", routingDecision = record)
        compose.runOnIdle {
            store.update { it.copy(rooms = it.rooms.map { room -> room.copy(mode = ReplyMode.SMART) }, messages = listOf(message)) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("接话安排").performScrollTo().performClick()
        compose.onNodeWithTag("routing-topic").assertDoesNotExist()
        // Capture the visual state before any copy action opens Android's clipboard overlay.
        val labels = mapOf(TopicStatus.OFF_TOPIC to "偏离原要求", TopicStatus.ON_TOPIC to "围绕原要求", TopicStatus.EXAMPLE to "举例说明")
        val nextNotes = mapOf(TopicStatus.EXAMPLE to "下一位接话时会提醒回到原主题。", TopicStatus.OFF_TOPIC to "下一位接话时会优先本轮原要求。")
        labels.forEach { (topic, label) ->
            compose.runOnIdle { store.updateMessage(message.id) { it.copy(routingDecision = record.copy(topicStatus = topic)) } }
            compose.onNodeWithTag("routing-topic-status").performScrollTo().assertTextEquals(label)
            val note = nextNotes[topic]
            if (note == null) compose.onNodeWithTag("routing-topic-note").assertDoesNotExist()
            else compose.onNodeWithTag("routing-topic-note").performScrollTo().assertTextEquals(note)
            if (topic == TopicStatus.OFF_TOPIC) capturePreview("topic-routing.png")
            setSyntheticClipboard("before-topic-copy")
            compose.onNodeWithTag("copy-routing-record").performClick()
            compose.runOnIdle {
                val copied = clipboardText()
                assertTrue(copied.contains("本次话题判断：$label"))
                if (note != null) assertTrue(copied.contains("接话提示：$note"))
                else assertFalse(copied.contains("接话提示："))
                assertFalse(copied.contains(record.rawOutput))
                assertFalse(copied.contains(message.text))
            }
        }
        val unscheduled = listOf(
            record.copy(status = RoutingStatus.ERROR, error = "本轮判断失败", topicStatus = TopicStatus.OFF_TOPIC),
            record.copy(status = RoutingStatus.CANCELLED, topicStatus = TopicStatus.EXAMPLE),
            record.copy(action = RoutingAction.STOP, topicStatus = TopicStatus.OFF_TOPIC),
            record.copy(action = RoutingAction.WAIT_USER, topicStatus = TopicStatus.EXAMPLE),
            record.copy(selectedCharacterId = null, selectedName = "", topicStatus = TopicStatus.OFF_TOPIC)
        )
        unscheduled.forEach { current ->
            compose.runOnIdle { store.updateMessage(message.id) { it.copy(routingDecision = current) } }
            compose.onNodeWithTag("routing-topic-note").performScrollTo().assertTextEquals("尚未安排下一位；后续接话时应回到原主题。")
            compose.onNodeWithTag("copy-routing-record").performClick()
            compose.runOnIdle {
                val copied = clipboardText()
                assertTrue(copied.contains("接话提示：尚未安排下一位；后续接话时应回到原主题。"))
                assertFalse(copied.contains("已提醒"))
                assertFalse(copied.contains("下一位接话时会"))
            }
        }
        compose.runOnIdle {
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.regenerated.isEmpty())
            assertTrue(actions.spoken.isEmpty())
        }
    }

    @Test fun characterDualModelNamesAndCutoffRangeAreSavedIndependently() {
        compose.onNodeWithContentDescription("角色").performClick()
        capturePreview("roles-preview.png")
        compose.onAllNodesWithText("模型")[0].performClick()
        compose.onNodeWithText("模型").assertIsSelected()
        compose.onNodeWithText("双模型接续").performScrollTo().performClick()
        val modelFields = hasSetTextAction() and hasText("模型名称")
        compose.onAllNodes(modelFields)[0].performScrollTo().performTextReplacement("test/first-model")
        compose.onAllNodes(modelFields)[1].performScrollTo().performTextReplacement("test/second-model")
        field("最少字符").performScrollTo().performTextReplacement("4")
        field("最多字符").performScrollTo().performTextReplacement("9")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val saved = actions.savedCharacters.single()
            assertTrue(saved.dualModel)
            assertEquals("test/first-model", saved.primary.model)
            assertEquals("test/second-model", saved.secondary.model)
            assertEquals(4, saved.cutMin)
            assertEquals(9, saved.cutMax)
        }
    }

    @Test fun providerKeyUsesPasswordSemanticsAndIsNotRefilledWhenReopened() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("硅基流动").performClick()
        val synthetic = "ui-test-placeholder-only"
        val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        field("API 密钥").performScrollTo().performTextInput(synthetic)
        field("API 密钥").assert(password)
        compose.onNodeWithContentDescription("显示密钥").performClick()
        field("API 密钥").assert(password.not())
        compose.onNodeWithContentDescription("隐藏密钥").performClick()
        field("API 密钥").assert(password)
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(synthetic, actions.savedProviders.single().second)
            assertFalse(store.snapshot().providers.single().toString().contains(synthetic))
        }
        compose.onNodeWithText("硅基流动").performClick()
        field("API 密钥").performScrollTo().assert(password)
        field("API 密钥").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun myIdentitySavesReopensAndStaysIndependentBetweenGroups() {
        compose.runOnIdle {
            store.update { state -> state.copy(rooms = state.rooms + ChatRoom(id = "night", name = "夜行小队", members = listOf(Member("lin"), Member("xia")))) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openRoomSection("我的身份")
        field("我的名字").performScrollTo().performTextReplacement("顾言")
        field("我的角色背景").performScrollTo().performTextReplacement("我是一名旅行摄影师，与林间是多年的朋友，刚结束一次海边旅行。")
        compose.onNodeWithText("保存").performClick()
        openRoomSection("我的身份")
        field("我的名字").performScrollTo().assertEditableValue("顾言")
        field("我的角色背景").performScrollTo().assertEditableValue("我是一名旅行摄影师，与林间是多年的朋友，刚结束一次海边旅行。")
        capturePreview("identity-preview.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithText("夜行小队").performClick()
        openRoomSection("我的身份")
        field("我的名字").performScrollTo().assertEditableValue("我")
        field("我的角色背景").performScrollTo().assertEditableValue("")
        field("我的名字").performScrollTo().performTextReplacement("队长")
        field("我的角色背景").performScrollTo().performTextReplacement("负责这次夜间探索行动的领队。")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val first = store.snapshot().rooms.single { it.id == "lobby" }
            val second = store.snapshot().rooms.single { it.id == "night" }
            assertEquals("顾言", first.userName)
            assertEquals("我是一名旅行摄影师，与林间是多年的朋友，刚结束一次海边旅行。", first.userBackground)
            assertEquals("队长", second.userName)
            assertEquals("负责这次夜间探索行动的领队。", second.userBackground)
        }
        openRoomSection("我的身份")
        field("我的名字").performScrollTo().assertEditableValue("队长")
        field("我的角色背景").performScrollTo().assertEditableValue("负责这次夜间探索行动的领队。")
    }

    @Test fun directChatIdentityDefaultsBlankNameAndPreservesHistoricalSender() {
        compose.runOnIdle {
            store.update { state -> state.copy(
                rooms = state.rooms + ChatRoom(id = "solo", name = "林间的私聊", direct = true, members = listOf(Member("lin")), userName = "旧名"),
                messages = state.messages + ChatMessage(id = "identity-old", roomId = "solo", senderName = "旧名", text = "原来的发言")
            ) }
        }
        compose.onNodeWithText("林间的私聊").performClick()
        assertHistoricalSenderInDetails("identity-old", "旧名")
        openRoomSection("我的身份")
        field("我的名字").performScrollTo().performTextReplacement("   ")
        field("我的角色背景").performScrollTo().performTextReplacement("这段私聊里，我是一名喜欢散步的邻居。")
        compose.onNodeWithText("保存").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("新的一句话")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle {
            val room = store.snapshot().rooms.single { it.id == "solo" }
            val messages = store.snapshot().messages.filter { it.roomId == "solo" }
            assertEquals("我", room.userName)
            assertEquals("这段私聊里，我是一名喜欢散步的邻居。", room.userBackground)
            assertEquals(listOf("旧名", "我"), messages.map { it.senderName })
        }
        Espresso.closeSoftKeyboard()
        assertHistoricalSenderInDetails("identity-old", "旧名")
        compose.onNodeWithText("新的一句话").assertIsDisplayed()
        openRoomSection("我的身份")
        field("我的名字").performScrollTo().assertEditableValue("我")
        field("我的角色背景").performScrollTo().assertEditableValue("这段私聊里，我是一名喜欢散步的邻居。")
    }

    @Test fun searchFindsRoomByIdentityAndQuickMemberEntryOpensMembers() {
        compose.runOnIdle {
            store.update { state -> state.copy(
                rooms = state.rooms.map { it.copy(userName = "顾言", userBackground = "与林间是多年的朋友，喜欢旅行与摄影。") } +
                    ChatRoom(id = "night", name = "夜行小队", members = listOf(Member("lin"), Member("xia")), userName = "队长"),
                messages = listOf(
                    ChatMessage(roomId = "lobby", senderName = "顾言", text = "周末一起去海边走走吧。"),
                    ChatMessage(roomId = "lobby", characterId = "lin", senderName = "林间", text = "好啊！我带相机，想拍一组傍晚的海。"),
                    ChatMessage(roomId = "lobby", characterId = "xia", senderName = "夏至", text = "那我来找地方吃饭，拍完正好一起吃。")
                )
            ) }
        }
        capturePreview("chat-list-preview.png")
        compose.onNodeWithContentDescription("搜索会话").performClick()
        compose.onNodeWithTag("search-input").performTextInput("顾言")
        compose.onNodeWithText("片刻 · 茶水间").assertIsDisplayed()
        compose.onNodeWithText("夜行小队").assertDoesNotExist()
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.onNodeWithText("夜行小队").assertIsDisplayed()
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithText("好啊！我带相机，想拍一组傍晚的海。").assertIsDisplayed()
        capturePreview("chat-preview.png")
        openRoomSection("群成员")
        compose.onNodeWithText("成员").assertIsSelected()
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("读取入群前的历史").performScrollTo().assertIsDisplayed()
        capturePreview("members-preview.png")
        compose.onNodeWithText("我的身份").performClick()
        field("我的名字").assertEditableValue("顾言")
        compose.onNodeWithText("回复").performClick()
        compose.onNodeWithText("并发回复").assertIsDisplayed()
    }

    @Test fun homeListSearchClearsCancelsAndOpensNewGroupWithoutCallingServices() {
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            val guide = Character(id = "home-guide-role", name = "旅行向导", avatar = "旅", color = 0xFF80ADA0)
            val barista = Character(id = "home-barista-role", name = "咖啡师", avatar = "咖", color = 0xFFC8A585)
            val bookseller = Character(id = "home-books-role", name = "书店店员", avatar = "书", color = 0xFF9DA6C2)
            val narrator = Character(id = "home-narrator-role", name = "场景旁白", avatar = "景", color = 0xFFAD9EBB,
                messageStyle = MessageStyle.SCENE)
            val rooms = listOf(
                ChatRoom(id = "home-walk", name = "周末散步", members = listOf(Member(guide.id), Member(barista.id), Member(narrator.id))),
                ChatRoom(id = "home-coffee", name = "咖啡师", direct = true, members = listOf(Member(barista.id))),
                ChatRoom(id = "home-books", name = "阅读角", members = listOf(Member(bookseller.id), Member(guide.id))),
                ChatRoom(id = "home-guide", name = "旅行向导", direct = true, members = listOf(Member(guide.id))),
                ChatRoom(id = "home-film", name = "电影夜话", members = listOf(Member(barista.id), Member(bookseller.id), Member(guide.id))),
                ChatRoom(id = "home-rain", name = "雨天小屋", members = listOf(Member(bookseller.id), Member(narrator.id))),
                ChatRoom(id = "home-scene", name = "场景旁白", direct = true, members = listOf(Member(narrator.id))),
                ChatRoom(id = "home-notes", name = "灵感便签", members = listOf(Member(bookseller.id), Member(guide.id), Member(narrator.id))),
                ChatRoom(id = "home-midnight", name = "深夜电台", members = listOf(Member(barista.id), Member(narrator.id))),
                ChatRoom(id = "home-bookshop", name = "书店店员", direct = true, members = listOf(Member(bookseller.id)))
            )
            val previews = listOf(
                guide to "明天下午沿河边走走，顺便看看落日。",
                barista to "今天想喝点什么？我推荐一杯热拿铁。",
                bookseller to "那就从这本短篇集开始，读完再聊。",
                guide to "路线已经整理好了，慢慢走就能到。",
                barista to "这部电影适合周五晚上一起看。",
                narrator to "雨落在窗台，屋里的灯刚刚亮起。",
                narrator to "暮色渐深，远处传来一声清脆的风铃。",
                bookseller to "这句话先记下来，留给下次写故事。",
                barista to "今天就听一首慢歌，好好休息。",
                bookseller to "新书已经摆上窗边的小架子，随时来看看。"
            )
            store.update { state -> state.copy(
                characters = listOf(guide, barista, bookseller, narrator), rooms = rooms,
                messages = rooms.mapIndexed { index, room ->
                    val (role, text) = previews[index]
                    ChatMessage(id = "home-message-$index", roomId = room.id, characterId = role.id,
                        senderName = role.name, text = text, messageStyle = role.messageStyle,
                        createdAt = time - index * 15 * 60_000L)
                }
            ) }
        }
        compose.onNodeWithTag("search-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索会话").assertIsDisplayed()
        compose.onNodeWithContentDescription("新建").assertIsDisplayed()
        assertEquals("首页标题应居中", bounds("chat-list-header").center.x,
            compose.onNodeWithText("片刻").fetchSemanticsNode().boundsInRoot.center.x, px(1f))
        compose.onNodeWithTag("chat-list-row-home-walk").assert(hasText("旅行向导：明天下午沿河边走走，顺便看看落日。"))
        compose.onNodeWithTag("chat-list-row-home-coffee").assert(hasText("今天想喝点什么？我推荐一杯热拿铁。"))
        compose.onNodeWithText("咖啡师：今天想喝点什么？我推荐一杯热拿铁。").assertDoesNotExist()
        compose.onNodeWithTag("chat-list").performScrollToNode(hasTestTag("chat-list-row-home-bookshop"))
        compose.onNodeWithTag("chat-list-row-home-bookshop").assertIsDisplayed()
        compose.onNodeWithTag("chat-list-avatar-home-bookshop", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat-list").performScrollToIndex(0)
        compose.onNodeWithTag("chat-list-row-home-walk").assertIsDisplayed()
        assertDarkSurface("chat-list")
        capturePreview("home-list-preview.png")

        compose.onNodeWithContentDescription("搜索会话").performClick()
        compose.onNodeWithTag("search-input").assertIsFocused().performTextInput("阅读角")
        compose.onNodeWithTag("chat-list-row-home-books").assertIsDisplayed()
        compose.onNodeWithTag("chat-list-row-home-coffee").assertDoesNotExist()
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.onNodeWithTag("search-input").assertEditableValue("")
        compose.onNodeWithTag("chat-list").performScrollToNode(hasTestTag("chat-list-row-home-coffee"))
        compose.onNodeWithTag("chat-list-row-home-coffee").assertIsDisplayed()
        compose.onNodeWithTag("search-input").performTextInput("旅行向导")
        compose.onNodeWithTag("chat-list").performScrollToNode(hasTestTag("chat-list-row-home-walk"))
        compose.onNodeWithTag("chat-list-row-home-walk").assertIsDisplayed()
        compose.onNodeWithTag("chat-list-row-home-coffee").assertDoesNotExist()
        compose.onNodeWithContentDescription("取消搜索").performClick()
        compose.onNodeWithTag("search-input").assertDoesNotExist()
        compose.onNodeWithTag("chat-list").performScrollToNode(hasTestTag("chat-list-row-home-coffee"))
        compose.onNodeWithTag("chat-list-row-home-coffee").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索会话").performClick()
        compose.onNodeWithTag("search-input").assertEditableValue("")
        compose.onNodeWithContentDescription("取消搜索").performClick()
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithTag("chat-create-menu").assertIsDisplayed()
        compose.onNodeWithTag("create-group-action").performClick()
        compose.onNodeWithText("创建群聊").assertIsDisplayed()
        field("群聊名称").assertEditableValue("")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("chat-list-header").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(10, store.snapshot().rooms.size)
            assertTrue(actions.savedRooms.isEmpty())
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.spoken.isEmpty())
        }
    }

    @Test fun homeRoomPinningOverridesRecencyAndUnpinningRestoresOriginalOrder() {
        val initial = seedRoomManagementConversation()
        assertTrue("未置顶时最近有消息的群应在前面", bounds("chat-list-row-management-other").top < bounds("chat-list-row-lobby").top)
        openHomeRoomActions("lobby")
        compose.onNodeWithTag("room-action-sheet").assertIsDisplayed()
        compose.onNodeWithTag("room-pin-action").assertContentDescriptionEquals("置顶")
        compose.onNodeWithTag("room-clear-action").assertDoesNotExist()
        compose.onNodeWithTag("room-export-action").assertIsDisplayed()
        capturePreview("room-actions-preview.png")
        compose.onNodeWithTag("room-pin-action").performClick()
        compose.onNodeWithTag("chat-list-pinned-lobby", useUnmergedTree = true).assertIsDisplayed().assertContentDescriptionEquals("已置顶")
        assertTrue("旧会话置顶后应排在较新的未置顶会话之前", bounds("chat-list-row-lobby").top < bounds("chat-list-row-management-other").top)
        compose.runOnIdle {
            assertEquals(listOf("lobby" to true), actions.pinnedChanges)
            assertEquals(initial.messages, store.snapshot().messages)
        }

        openHomeRoomActions("lobby")
        compose.onNodeWithTag("room-pin-action").assertContentDescriptionEquals("取消置顶").performClick()
        compose.onNodeWithTag("chat-list-pinned-lobby", useUnmergedTree = true).assertDoesNotExist()
        assertTrue("取消置顶后应恢复按最近消息排序", bounds("chat-list-row-management-other").top < bounds("chat-list-row-lobby").top)
        compose.runOnIdle {
            assertEquals(listOf("lobby" to true, "lobby" to false), actions.pinnedChanges)
            assertEquals(initial, store.snapshot())
        }
    }

    @Test fun homeDeleteRequiresConfirmationAndRemovesOnlyTheChosenConversation() {
        val initial = seedRoomManagementConversation()
        openHomeRoomActions("lobby")
        compose.onNodeWithTag("room-delete-action").performScrollTo().performClick()
        compose.onNodeWithTag("delete-room-dialog").assertIsDisplayed()
        compose.onNodeWithTag("confirm-delete-room").assertIsEnabled()
        compose.onNode(hasText("取消") and hasAnyAncestor(hasTestTag("delete-room-dialog"))).performClick()
        compose.onNodeWithTag("delete-room-dialog").assertDoesNotExist()
        compose.onNodeWithTag("room-action-sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(actions.deletedRooms.isEmpty())
            assertEquals(initial, store.snapshot())
        }

        compose.onNodeWithTag("room-delete-action").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-delete-room").performClick()
        compose.onNodeWithTag("chat-list-row-lobby").assertDoesNotExist()
        compose.onNodeWithTag("chat-list-row-management-other").assertIsDisplayed()
        compose.onNodeWithTag("room-action-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("lobby"), actions.deletedRooms)
            assertEquals(initial.copy(rooms = initial.rooms.filterNot { it.id == "lobby" },
                messages = initial.messages.filterNot { it.roomId == "lobby" }), store.snapshot())
            assertTrue(store.snapshot().messages.none { it.id.startsWith("management-old-") })
        }
    }

    @Test fun chatTitleMenuClearsHistoryAfterConfirmationButKeepsTheGroupUsable() {
        val initial = seedRoomManagementConversation()
        compose.onNodeWithTag("chat-list-row-lobby").performClick()
        compose.onNodeWithTag("chat-room-title").performTouchInput { longClick() }
        compose.onNodeWithTag("room-action-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("我的身份").assertExists()
        compose.onNodeWithTag("room-clear-action").performScrollTo().assertIsDisplayed()
        capturePreview("room-management-preview.png")
        compose.onNodeWithTag("room-clear-action").performClick()
        compose.onNodeWithTag("clear-history-dialog").assertIsDisplayed()
        compose.onNode(hasText("取消") and hasAnyAncestor(hasTestTag("clear-history-dialog"))).performClick()
        compose.onNodeWithTag("room-action-sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(actions.clearedRooms.isEmpty())
            assertEquals(initial, store.snapshot())
        }
        compose.onNodeWithTag("room-clear-action").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-clear-history").performClick()
        compose.onNodeWithTag("chat-toolbar").assertIsDisplayed()
        compose.onNodeWithText("从一句问候开始").assertIsDisplayed()
        compose.onNodeWithTag("message-management-old-reply", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("room-action-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("lobby"), actions.clearedRooms)
            assertTrue(actions.deletedRooms.isEmpty())
            assertEquals(initial.copy(messages = initial.messages.filterNot { it.roomId == "lobby" }), store.snapshot())
        }
        compose.onNodeWithTag("chat-input").performTextInput("从这里重新开始聊天。")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Sent("lobby", "从这里重新开始聊天。", emptySet())), actions.sent)
            assertEquals(listOf("从这里重新开始聊天。"), store.snapshot().messages.filter { it.roomId == "lobby" }.map { it.text })
        }
        Espresso.closeSoftKeyboard()
    }

    @Test fun groupImportExportRoutesStaySeparateFromBackupsAndPrivateChatsCannotExport() {
        val initial = seedRoomManagementConversation(includeDirect = true)
        openHomeRoomActions("lobby")
        compose.onNodeWithTag("room-export-action").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("lobby"), actions.exportedGroups) }
        compose.onNodeWithTag("chat-list-row-lobby").performClick()
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithTag("room-export-action").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("lobby", "lobby"), actions.exportedGroups) }
        compose.onNodeWithContentDescription("返回").performClick()

        openHomeRoomActions("management-direct")
        compose.onNodeWithTag("room-export-action").assertDoesNotExist()
        compose.onNodeWithTag("room-pin-action").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("chat-list-row-management-direct").performClick()
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithTag("room-export-action").assertDoesNotExist()
        compose.onNodeWithTag("room-clear-action").performScrollTo().assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithTag("chat-create-menu").assertIsDisplayed()
        compose.onNodeWithTag("create-group-action").assertIsDisplayed()
        compose.onNodeWithTag("import-group-action").performClick()
        compose.onNodeWithTag("chat-list-header").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, actions.groupImportCount)
            assertTrue(actions.savedRooms.isEmpty())
            assertEquals(0, actions.backupImportCount)
            assertEquals(0, actions.backupExportCount)
        }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("settings-import-group").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, actions.groupImportCount) }
        compose.onNodeWithText("导出聊天备份").performScrollTo().performClick()
        compose.onNodeWithText("导入聊天备份").performScrollTo().performClick()
        compose.onNodeWithText("导入备份并替换数据？").assertIsDisplayed()
        compose.onNodeWithText("选择备份").performClick()
        compose.runOnIdle {
            assertEquals(2, actions.groupImportCount)
            assertEquals(1, actions.backupExportCount)
            assertEquals(1, actions.backupImportCount)
            assertEquals(listOf("lobby", "lobby"), actions.exportedGroups)
            assertEquals(initial, store.snapshot())
        }
    }

    @Test fun fewMessagesStartBelowSingleLineToolbarAndSettingsStayInSheet() {
        seedCompactConversation()
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        assertCompactChatFrame()
        val list = bounds("chat-messages")
        val first = bounds("message-layout-first")
        val firstText = bounds("message-text-layout-first")
        assertTrue("首条消息应紧跟聊天区顶部，不应被推至屏幕下方", first.top - list.top in 0f..px(28f))
        assertTrue("首条正文上方只留时间和署名的空间", firstText.top - list.top in 0f..px(90f))
        compose.onNodeWithTag("message-layout-second", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("我的身份").assertDoesNotExist()
        compose.onNodeWithContentDescription("群成员").assertDoesNotExist()
        compose.onNodeWithContentDescription("回复方式").assertDoesNotExist()
        compose.onNodeWithText("顺序回复").assertDoesNotExist()
        capturePreview("compact-chat-preview.png")

        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithContentDescription("我的身份").assertIsDisplayed()
        compose.onNodeWithContentDescription("群成员").assertIsDisplayed()
        compose.onNodeWithContentDescription("回复方式").assertIsDisplayed()
        compose.onNodeWithText("顺序回复").assertIsDisplayed()
        capturePreview("compact-chat-menu-preview.png")
        compose.onNodeWithText("更多设置").performClick()
        compose.onNodeWithText("基础").assertIsSelected()
    }

    @Test fun longStreamingReplyStaysAboveComposerAndBusyStateUsesSameRow() {
        val longText = (1..65).joinToString("\n") { "第 $it 段：今天沿着海边慢慢走，想把路上的见闻讲给你听。" }
        compose.runOnIdle {
            store.update { it.copy(messages = listOf(
                ChatMessage(id = "long-user", roomId = "lobby", text = "说说今天的散步吧。"),
                ChatMessage(id = "long-reply", roomId = "lobby", characterId = "lin", senderName = "林间",
                    text = longText, status = MessageStatus.GENERATING_A)
            )) }
            runtime.value = runtime.value.copy(busyRooms = setOf("lobby"))
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithContentDescription("停止本轮").assertIsDisplayed()
        assertCompactChatFrame()
        assertReplyTailIsVisible("long-reply")
        val busyComposerHeight = bounds("chat-composer").height

        compose.runOnIdle {
            store.updateMessage("long-reply") { it.copy(text = it.text + "\n这就是今天散步的最后一句。", status = MessageStatus.COMPLETE) }
            runtime.value = runtime.value.copy(busyRooms = emptySet())
        }
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        assertCompactChatFrame()
        assertReplyTailIsVisible("long-reply")
        assertEquals("生成状态不应额外挤占一排输入空间", busyComposerHeight, bounds("chat-composer").height, px(1f))
    }

    @Test fun realKeyboardKeepsComposerAboveImeAndToolbarVisible() {
        seedCompactConversation()
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        val toolbarBefore = bounds("chat-toolbar")
        compose.onNodeWithTag("chat-input").performClick().performTextInput("周末一起去海边走走吧。")
        try {
            // Use actual Android window insets, rather than assuming text focus means
            // that the software keyboard is on screen.
            waitForImeAboveComposer()
            compose.onNodeWithTag("chat-input").assertIsFocused().assertIsDisplayed()
            compose.onNodeWithTag("chat-toolbar").assertIsDisplayed()
            assertCompactChatFrame()
            val ime = imeState()
            assertTrue("必须实际显示系统键盘", ime.visible)
            val composer = windowBounds("chat-composer")
            assertTrue("输入区底边应位于键盘之上", composer.bottom <= ime.topInWindow + px(1f))
            assertTrue("输入区与键盘之间不应出现大块空白", ime.topInWindow - composer.bottom <= px(3f))
            assertEquals("键盘不能把标题栏推离屏幕", toolbarBefore.top, bounds("chat-toolbar").top, px(1f))
            capturePreview("compact-chat-keyboard-preview.png")
        } finally {
            Espresso.closeSoftKeyboard()
        }
    }

    @Test fun keyboardWithAudioMentionAndMultilineDraftStillLeavesMessagesVisible() {
        seedCompactConversation()
        compose.runOnIdle { runtime.value = runtime.value.copy(busyRooms = setOf("lobby"), audioStatus = "正在朗读 · 林间") }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        val toolbarBefore = bounds("chat-toolbar")
        compose.onNodeWithContentDescription("选择要提醒的角色").performClick()
        compose.onNodeWithText("@林间").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("周末去海边。\n先在入口碰面。\n记得带相机。")
        try {
            waitForImeAboveComposer()
            assertCompactChatFrame(maxComposerHeightDp = 136f)
            compose.onNodeWithText("已选 1 位角色").assertIsDisplayed()
            compose.onNodeWithText("@林间").assertDoesNotExist()
            compose.onNodeWithText("正在朗读 · 林间").assertIsDisplayed()
            compose.onNodeWithText("本轮回复进行中").assertDoesNotExist()
            compose.onNodeWithContentDescription("发送").assertIsEnabled()
            assertEquals("多行草稿和音频状态不能推走标题", toolbarBefore.top, bounds("chat-toolbar").top, px(1f))
            assertTrue("小屏键盘打开时仍要留下可读的聊天空间", bounds("chat-messages").height >= px(80f))
            assertReplyTailIsVisible("layout-second", expectAtBottom = false)
            capturePreview("compact-chat-dense-preview.png")

            compose.onNodeWithTag("chat-input").performTextClearance()
            compose.onNodeWithContentDescription("停止本轮").assertIsDisplayed()
            compose.onNodeWithContentDescription("发送").assertDoesNotExist()
            waitForImeAboveComposer()
        } finally {
            Espresso.closeSoftKeyboard()
        }
    }

    @Test fun streamUpdatesDoNotPullReaderAwayFromHistory() {
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            val history = (0 until 32).map { index ->
                ChatMessage(id = "history-$index", roomId = "lobby", characterId = if (index % 2 == 0) null else "lin",
                    senderName = if (index % 2 == 0) "我" else "林间", text = "第 $index 条历史：一起聊聊散步时看到的风景。", createdAt = time + index)
            }
            store.update { it.copy(messages = history + ChatMessage(id = "stream", roomId = "lobby", characterId = "lin",
                senderName = "林间", text = "我还想补充一点。", status = MessageStatus.GENERATING_A, createdAt = time + 32)) }
            runtime.value = runtime.value.copy(busyRooms = setOf("lobby"))
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("message-stream", useUnmergedTree = true).assertIsDisplayed()
        // A real downward drag moves to older messages and exercises nested-scroll
        // user intent; scrollToIndex would bypass that contract.
        repeat(2) {
            compose.onNodeWithTag("chat-messages").performTouchInput { swipeDown(durationMillis = 500) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("message-stream", useUnmergedTree = true).assertIsNotDisplayed()
        val viewport = bounds("chat-messages")
        val historyMatcher = SemanticsMatcher("历史消息项") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("message-history-") == true
        }
        val anchor = compose.onAllNodes(historyMatcher, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.boundsInRoot.top >= viewport.top + px(4f) && it.boundsInRoot.bottom <= viewport.bottom - px(4f) }
        assertTrue("上滑后应有完整可见的历史消息作为位置参照", anchor != null)
        val anchorTag = anchor!!.config[SemanticsProperties.TestTag]
        val anchorTop = anchor.boundsInRoot.top
        repeat(5) { chunk ->
            compose.runOnIdle {
                store.updateMessage("stream") { it.copy(text = it.text + "\n接着说第 $chunk 段新的内容，让最新回复继续增长。") }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(anchorTag, useUnmergedTree = true).assertIsDisplayed()
            assertEquals("流式正文更新不应拉走正在阅读历史的位置", anchorTop, bounds(anchorTag).top, px(1f))
            compose.onNodeWithTag("message-stream", useUnmergedTree = true).assertIsNotDisplayed()
        }
    }

    @Test fun voiceSettingsExposeVolcengineLinksAndKeepConnectionOptionsAccessible() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        field("API Key").performScrollTo().assertIsDisplayed()
        field("Resource ID").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("登录火山引擎").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("获取 API Key").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("高级连接设置").performScrollTo().performClick()
        field("WebSocket 地址").performScrollTo().assertEditableValue("wss://openspeech.bytedance.com/api/v3/tts/bidirection")
    }

    @Test fun siliconFlowSpeechSettingsAndPerRoleVoiceOverridesAreSavedSeparately() {
        val originalDoubaoVoice = store.snapshot().characters.first().voice
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        compose.onNodeWithText("硅基流动").performClick()
        compose.onNodeWithText("启用语音").performClick()
        field("语音模型").performScrollTo().assertEditableValue("FunAudioLLM/CosyVoice2-0.5B")
        field("默认音色 ID").performScrollTo().performTextReplacement("claire")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val tts = store.snapshot().tts
            assertEquals(TtsEngine.SILICONFLOW, tts.engine)
            assertTrue(tts.enabled)
            assertEquals("siliconflow", tts.siliconProviderId)
            assertEquals("claire", tts.siliconVoice)
            assertEquals("seed-tts-1.0", tts.resourceId)
        }
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        field("默认音色 ID").performScrollTo().assertEditableValue("claire")
        compose.onNodeWithText("语音引擎").performScrollTo()
        capturePreview("silicon-speech-preview.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("角色").performClick()
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("声音").performClick()
        field("语音模型覆盖").performScrollTo().performTextReplacement("custom/voice-model")
        field("音色覆盖").performScrollTo().performTextReplacement("speech:private-demo-voice")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val first = store.snapshot().characters.single { it.id == "lin" }
            val other = store.snapshot().characters.single { it.id == "xia" }
            assertEquals("custom/voice-model", first.siliconModel)
            assertEquals("speech:private-demo-voice", first.siliconVoice)
            assertEquals(originalDoubaoVoice, first.voice)
            assertEquals("", other.siliconModel)
            assertEquals("", other.siliconVoice)
        }
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("声音").performClick()
        field("音色覆盖").performScrollTo().assertEditableValue("speech:private-demo-voice")
        compose.onNodeWithText("恢复全局默认").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("", store.snapshot().characters.single { it.id == "lin" }.siliconVoice)
            assertEquals("claire", store.snapshot().tts.siliconVoice)
        }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        compose.onNodeWithText("火山引擎").performClick()
        field("Resource ID").performScrollTo().assertEditableValue("seed-tts-1.0")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(TtsEngine.DOUBAO, store.snapshot().tts.engine)
            assertEquals("claire", store.snapshot().tts.siliconVoice)
        }
    }

    @Test fun voicePreviewUsesUnsavedDraftAndDoesNotSaveOrAddChatMessages() {
        compose.runOnIdle { store.update { it.copy(tts = it.tts.copy(resourceId = "seed-tts-2.0")) } }
        val original = store.snapshot()
        compose.onNodeWithContentDescription("角色").performClick()
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("声音").performClick()
        compose.runOnIdle { assertEquals(0, actions.audioStopCount) }
        field("豆包音色 ID").performTextReplacement("zh_female_vv_uranus_bigtts")
        compose.onNodeWithTag("preview-character-voice").performScrollTo().performClick()
        compose.onNodeWithTag("character-voice-preview-text").assertTextContains("你好，我是林间，很高兴见到你。", substring = true)
        compose.runOnIdle {
            assertEquals("zh_female_vv_uranus_bigtts", actions.voicePreviews.single().voice)
            assertEquals(original, store.snapshot())
            assertTrue(actions.savedCharacters.isEmpty())
            assertTrue(actions.sent.isEmpty())
            runtime.value = runtime.value.copy(audioStatus = "林间 · 朗读完成")
        }
        compose.onNodeWithTag("character-voice-preview-status").performScrollTo().assertTextEquals("林间 · 朗读完成")
        capturePreview("character-voice-preview.png")
        compose.onNodeWithTag("stop-character-voice-preview").performClick()
        compose.runOnIdle { assertEquals(1, actions.audioStopCount) }
        compose.onNodeWithTag("preview-character-voice").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            assertEquals(2, actions.audioStopCount)
            assertEquals(original, store.snapshot())
            store.update { it.copy(tts = it.tts.copy(engine = TtsEngine.SILICONFLOW)) }
        }
        val siliconState = store.snapshot()
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("声音").performClick()
        field("语音模型覆盖").performScrollTo().performTextReplacement("test/voice-preview")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("使用全局默认（alex）").performScrollTo().performClick()
        compose.onNodeWithText("anna").performClick()
        compose.runOnIdle { assertEquals(2, actions.voicePreviews.size) }
        compose.onNodeWithTag("preview-character-voice").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("anna", actions.voicePreviews.last().siliconVoice)
            assertEquals("test/voice-preview", actions.voicePreviews.last().siliconModel)
            assertEquals(siliconState, store.snapshot())
            assertFalse(store.snapshot().tts.enabled)
        }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(3, actions.audioStopCount)
            assertEquals("anna", actions.savedCharacters.single().siliconVoice)
            assertTrue(store.snapshot().messages.isEmpty())
        }
    }

    @Test fun doubaoVoicePickerBlocksMismatchedVoiceAndSupportsSearchRefreshAndExplicitPreview() {
        compose.runOnIdle { store.update { it.copy(tts = it.tts.copy(resourceId = "seed-tts-2.0")) } }
        compose.onNodeWithContentDescription("角色").performClick()
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("声音").performClick()
        compose.onNodeWithTag("preview-character-voice").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("doubao-voice-validation-error").performScrollTo().assertTextContains("当前资源为 2.0", substring = true)
        compose.onNodeWithTag("choose-doubao-voice").performScrollTo().performClick()
        compose.onNodeWithTag("doubao-voice-search").performTextReplacement("灿灿")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("select-doubao-voice-zh_female_cancan_mars_bigtts").assertDoesNotExist()
        compose.onNodeWithTag("select-doubao-voice-zh_female_cancan_uranus_bigtts").assertIsEnabled()
        compose.onNodeWithTag("doubao-compatible-only").performClick()
        compose.onNodeWithTag("select-doubao-voice-zh_female_cancan_mars_bigtts").assertIsNotEnabled()
        compose.onNodeWithTag("doubao-compatible-only").performClick()
        compose.onNodeWithTag("doubao-voice-search").performTextReplacement("Vivi")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("refresh-doubao-voices").performClick()
        compose.runOnIdle {
            assertEquals(1, actions.voiceListRefreshes)
            runtime.value = runtime.value.copy(doubaoVoiceListError = "测试刷新失败，已保留现有列表")
        }
        compose.onNodeWithTag("doubao-voice-list-error").assertIsDisplayed()
        compose.onNodeWithTag("preview-doubao-voice-zh_female_vv_uranus_bigtts").performClick()
        compose.runOnIdle {
            assertEquals("zh_female_vv_uranus_bigtts", actions.voicePreviews.single().voice)
            assertEquals("zh_female_cancan_mars_bigtts", store.snapshot().characters.first().voice)
        }
        capturePreview("doubao-voice-picker-preview.png")
        compose.onNodeWithTag("select-doubao-voice-zh_female_vv_uranus_bigtts").performClick()
        field("豆包音色 ID").assertEditableValue("zh_female_vv_uranus_bigtts")
        compose.onNodeWithTag("preview-character-voice").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("zh_female_vv_uranus_bigtts", actions.savedCharacters.single().voice)
            assertEquals(1, actions.voicePreviews.size)
            assertTrue(store.snapshot().messages.isEmpty())
        }
    }

    @Test fun voiceListCredentialsArePairedMaskedAndNeverRefilled() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        compose.onNodeWithText("音色列表访问密钥").performScrollTo().performClick()
        compose.onNodeWithTag("voice-catalog-access-key").performScrollTo().performTextReplacement("test-access-only")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("voice-catalog-secret-key").performScrollTo().performTextReplacement("test-secret-only")
        compose.onNodeWithTag("voice-catalog-secret-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("test-access-only" to "test-secret-only", actions.voiceListCredentials.single()) }
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        compose.onNodeWithText("音色列表访问密钥").performScrollTo().performClick()
        compose.onNodeWithTag("voice-catalog-access-key").performScrollTo().assertEditableValue("")
        compose.onNodeWithTag("voice-catalog-secret-key").performScrollTo().assertEditableValue("")
    }

    @Test fun cotShowsCopyableSpeechPreparationResultWithoutChangingTheChatText() {
        val preparation = SpeechPreparationRecord(model = "test/dialogue-small", text = "晚上见。", instruction = "轻声、温柔地说。",
            rawOutput = "{\"text\":\"晚上见。\",\"instruction\":\"轻声、温柔地说。\"}", status = SpeechPreparationStatus.COMPLETE,
            previousInstruction = "语速稍慢，保持轻松、柔和的语气。", previousMessageId = "previous-lin")
        val message = ChatMessage(id = "speech-trace", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "她轻轻挥手。\"晚上见。\"", speechPreparation = preparation)
        compose.runOnIdle { store.update { it.copy(messages = listOf(message)) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("reasoning-empty").assertDoesNotExist()
        compose.onNodeWithTag("speech-preparation-text").assertTextEquals("晚上见。")
        compose.onNodeWithTag("speech-preparation-instruction").assertTextEquals("轻声、温柔地说。")
        compose.onNodeWithTag("speech-preparation-previous-instruction").performScrollTo().assertTextEquals(preparation.previousInstruction)
        capturePreview("speech-preparation-cot-preview.png")
        compose.onNodeWithTag("toggle-speech-preparation-raw").performScrollTo().performClick()
        compose.onNodeWithTag("speech-preparation-raw").performScrollTo().assertTextEquals(preparation.rawOutput)
        compose.onNodeWithTag("copy-speech-preparation").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip?.getItemAt(0)?.text.toString()
            assertTrue(copied.contains("晚上见。") && copied.contains("轻声、温柔地说。") && copied.contains(preparation.rawOutput))
            assertTrue(copied.contains("上一条朗读指令（本次参考）") && copied.contains(preparation.previousInstruction))
            assertEquals(message, store.snapshot().messages.single())
            store.update { it.copy(messages = listOf(message.copy(speechPreparation = preparation.copy(status = SpeechPreparationStatus.ERROR,
                error = "对白提取结果格式不正确，本条未朗读")))) }
        }
        compose.onNodeWithTag("speech-preparation-error").performScrollTo().assertTextEquals("对白提取结果格式不正确，本条未朗读")
    }

    @Test fun longBThinkingCannotHideSpeechPreparationAndTabsKeepTheirOwnContent() {
        val reasoning = "B 的长思考，不应遮住语音入口。\n".repeat(180)
        val record = SpeechPreparationRecord(model = "test/small-model", text = "明天见。", instruction = "轻声说。",
            rawOutput = "{\"text\":\"明天见。\",\"instruction\":\"轻声说。\"}", status = SpeechPreparationStatus.COMPLETE)
        val message = ChatMessage(id = "long-b-speech", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "他挥了挥手。明天见。", segments = listOf(
                GenerationSegment(label = "A", model = "test/a", text = "他挥了", status = SegmentStatus.CUT),
                GenerationSegment(id = "long-b", label = "B", model = "test/b", text = "挥手。明天见。",
                    reasoning = reasoning, status = SegmentStatus.COMPLETE)), speechPreparation = record)
        compose.runOnIdle { store.update { it.copy(messages = listOf(message)) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("cot-chat-tab").assertIsSelected()
        compose.onNodeWithTag("cot-speech-tab").assertIsDisplayed().performClick()
        compose.onNodeWithTag("cot-speech-tab").assertIsSelected()
        compose.onNodeWithTag("speech-preparation-text").assertIsDisplayed().assertTextEquals("明天见。")
        compose.onNodeWithTag("reasoning-text-long-b").assertDoesNotExist()
        capturePreview("speech-preparation-tabs-preview.png")
        compose.onNodeWithTag("speech-preparation-previous-instruction").performScrollTo().assertTextEquals("未参考上一条朗读指令")
        compose.onNodeWithTag("copy-speech-preparation").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(clipboardText().contains(record.text))
            assertTrue(clipboardText().contains("未参考上一条朗读指令"))
            assertFalse(clipboardText().contains(reasoning))
        }
        compose.onNodeWithTag("cot-chat-tab").assertIsDisplayed().performClick()
        compose.onNodeWithTag("copy-reasoning").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("模型 B · test/b\n$reasoning", clipboardText())
            assertEquals(message, store.snapshot().messages.single())
        }
        compose.onNodeWithTag("cot-speech-tab").performClick()
        compose.onNodeWithTag("speech-preparation-text").performScrollTo().assertTextEquals("明天见。")
    }

    @Test fun missingSpeechRecordCanBeRetriedFromCotAndFailureAppearsWithoutReopening() {
        val message = ChatMessage(id = "retry-extraction", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "她轻声说：晚上见。", segments = listOf(GenerationSegment(label = "B", model = "test/b",
                reasoning = "已有 B 思考", status = SegmentStatus.COMPLETE)))
        compose.runOnIdle { store.update { it.copy(messages = listOf(message),
            tts = it.tts.copy(enabled = true, dialogueExtraction = it.tts.dialogueExtraction.copy(enabled = true))) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("cot-speech-tab").performClick()
        compose.onNodeWithTag("speech-preparation-empty").assertIsDisplayed()
        compose.onNodeWithTag("prepare-speech-hint").assertTextContains("再次朗读", substring = true)
        compose.onNodeWithTag("prepare-speech-action").assertIsEnabled().performClick()
        val attempt = SpeechPreparationRecord(model = "test/small")
        compose.runOnIdle {
            assertEquals(listOf(message.id), actions.spoken)
            assertTrue(actions.regenerated.isEmpty() && actions.sent.isEmpty())
            store.updateMessage(message.id) { it.copy(speechPreparation = attempt) }
        }
        compose.onNodeWithTag("speech-preparation-empty").assertDoesNotExist()
        compose.onNodeWithTag("speech-preparation-status").assertTextEquals("处理中")
        compose.onNodeWithTag("prepare-speech-action").assertIsNotEnabled()
        compose.runOnIdle { store.updateMessage(message.id) { it.copy(speechPreparation = attempt.copy(
            status = SpeechPreparationStatus.ERROR, error = "请先为对白提取选择模型服务并保存 API 密钥；本次未调用语音前置模型")) } }
        compose.onNodeWithTag("speech-preparation-error").performScrollTo().assertTextContains("本次未调用", substring = true)
        compose.onNodeWithTag("prepare-speech-action").assertIsEnabled()
        compose.runOnIdle { store.update { it.copy(tts = it.tts.copy(dialogueExtraction = it.tts.dialogueExtraction.copy(enabled = false))) } }
        compose.onNodeWithTag("prepare-speech-action").assertIsNotEnabled()
        compose.onNodeWithTag("prepare-speech-hint").assertTextContains("提取对白后朗读", substring = true)
    }

    @Test fun extractionProgressKeepsAStopActionAndCompletedStatusDoesNot() {
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        listOf("林间 · 正在提取对白", "正在朗读 · 林间", "林间 · 正在朗读").forEach { status ->
            compose.runOnIdle { runtime.value = runtime.value.copy(audioStatus = status) }
            compose.onNodeWithContentDescription("停止语音").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("停止语音").performClick()
        compose.runOnIdle { assertEquals(1, actions.audioStopCount) }
        compose.runOnIdle { runtime.value = runtime.value.copy(audioStatus = "林间 · 朗读完成") }
        compose.onNodeWithContentDescription("停止语音").assertDoesNotExist()
    }

    @Test fun failedPreflightShowsNoModelCallAndCotCanSaveTheExactRolesVoice() {
        val oldVoice = "zh_female_cancan_mars_bigtts"
        val newVoice = "zh_female_vv_uranus_bigtts"
        val failed = SpeechPreparationRecord(model = "test/small", modelInvoked = false,
            voice = SpeechVoiceSnapshot(TtsEngine.DOUBAO, "seed-tts-2.0", oldVoice),
            status = SpeechPreparationStatus.ERROR, error = "当前资源为 2.0，灿灿属于 1.0")
        val message = ChatMessage(id = "wrong-voice", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "（她挥挥手。）明天见。", segments = listOf(GenerationSegment(label = "B", reasoning = "B 的思考")), speechPreparation = failed)
        compose.runOnIdle { store.update { state -> state.copy(messages = listOf(message),
            characters = state.characters.map { it.copy(name = "林间") },
            tts = state.tts.copy(enabled = true, resourceId = "seed-tts-2.0", dialogueExtraction = state.tts.dialogueExtraction.copy(enabled = true))) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("cot-speech-tab").performClick()
        compose.onNodeWithTag("speech-preparation-status").assertTextEquals("未调用模型")
        compose.onNodeWithTag("speech-model-not-invoked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speech-preparation-text").performScrollTo().assertTextEquals("尚未提取")
        compose.onNodeWithText("没有可朗读的对白").assertDoesNotExist()
        compose.onNodeWithTag("change-speech-voice").performScrollTo().performClick()
        compose.onNodeWithTag("doubao-voice-search").performTextReplacement("Vivi")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("preview-doubao-voice-$newVoice").performClick()
        compose.runOnIdle {
            assertEquals(oldVoice, store.snapshot().characters.first { it.id == "lin" }.voice)
            assertEquals(newVoice, actions.voicePreviews.single().voice)
        }
        compose.onNodeWithTag("select-doubao-voice-$newVoice").assertTextEquals("使用并保存").performClick()
        compose.onNodeWithTag("cot-speech-tab").assertIsSelected()
        compose.onNode(hasText(newVoice, substring = true) and hasAnyAncestor(hasTestTag("current-speech-voice"))).assertExists()
        compose.onNodeWithTag("speech-voice-changed").assertIsDisplayed()
        compose.onNodeWithTag("attempt-speech-voice").performScrollTo().assertTextContains(oldVoice, substring = true)
        compose.runOnIdle {
            assertEquals(newVoice, store.snapshot().characters.first { it.id == "lin" }.voice)
            assertEquals(oldVoice, store.snapshot().characters.first { it.id == "xia" }.voice)
            assertEquals(failed, store.snapshot().messages.single().speechPreparation)
            assertTrue(actions.savedCharacters.isEmpty())
        }
        compose.onNodeWithTag("prepare-speech-action").performClick()
        compose.runOnIdle { assertEquals(listOf(message.id), actions.spoken) }
    }

    @Test fun savingSelectedVoiceDoesNotSaveOtherDraftFieldsAndSurvivesReturning() {
        val original = store.snapshot().characters.first { it.id == "lin" }
        val newVoice = "zh_female_vv_uranus_bigtts"
        compose.runOnIdle { store.update { it.copy(tts = it.tts.copy(resourceId = "seed-tts-2.0")) } }
        compose.onNodeWithContentDescription("角色").performClick()
        compose.onNodeWithText("林间").performClick()
        field("角色提示词").performScrollTo().performTextReplacement("这份人设草稿不应跟随音色保存")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("声音").performClick()
        compose.onNodeWithTag("choose-doubao-voice").performScrollTo().performClick()
        compose.onNodeWithTag("doubao-voice-search").performTextReplacement("Vivi")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("select-doubao-voice-$newVoice").performClick()
        compose.onNodeWithTag("saved-doubao-voice").performScrollTo().assertTextContains(newVoice, substring = true)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            assertEquals(original.copy(voice = newVoice), store.snapshot().characters.first { it.id == "lin" })
            assertTrue(actions.savedCharacters.isEmpty())
        }
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("声音").performClick()
        field("豆包音色 ID").performScrollTo().assertEditableValue(newVoice)
    }

    @Test fun onlyAnActualEmptyExtractionIsLabeledNoDialogue() {
        val message = ChatMessage(id = "extraction-result", roomId = "lobby", characterId = "lin", senderName = "林间", text = "你好呀。",
            speechPreparation = SpeechPreparationRecord(model = "test/small", status = SpeechPreparationStatus.ERROR,
                modelInvoked = true, error = "返回格式不正确"))
        compose.runOnIdle { store.update { it.copy(messages = listOf(message)) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${message.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("speech-preparation-text").performScrollTo().assertTextEquals("未取得有效提取结果")
        compose.onNodeWithText("没有可朗读的对白").assertDoesNotExist()
        compose.runOnIdle { store.updateMessage(message.id) { it.copy(speechPreparation = it.speechPreparation!!.copy(status = SpeechPreparationStatus.EMPTY, error = "")) } }
        compose.onNodeWithTag("speech-preparation-text").assertTextEquals("没有可朗读的对白")
        compose.runOnIdle { store.updateMessage(message.id) { it.copy(speechPreparation = it.speechPreparation!!.copy(status = SpeechPreparationStatus.COMPLETE,
            text = "你好呀。", instruction = "轻声说。", rawOutput = "{\"text\":\"你好呀。\",\"instruction\":\"轻声说。\"}")) } }
        compose.onNodeWithTag("speech-preparation-text").assertTextEquals("你好呀。")
        compose.onNodeWithTag("toggle-speech-preparation-raw").performScrollTo().performClick()
        compose.onNodeWithTag("speech-preparation-raw").performScrollTo().assertTextContains("你好呀。", substring = true)
    }

    @Test fun sceneStyleSavesRestoresAndCanReturnToOrdinaryDialogue() {
        val original = store.snapshot().characters.single { it.id == "lin" }
        compose.onNodeWithContentDescription("角色").performClick()
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("人设").assertIsSelected()
        compose.onNodeWithText("消息样式").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("角色对话").assertIsSelected()
        compose.onNodeWithText("场景提示").performClick()
        compose.onNodeWithText("场景提示").assertIsSelected()
        assertDarkSurface()
        capturePreview("dark-role-style-preview.png")
        compose.onNodeWithText("保存").performClick()
        val backup = compose.runOnIdle {
            val saved = actions.savedCharacters.single()
            assertEquals(MessageStyle.SCENE, saved.messageStyle)
            assertEquals("切换显示样式不应覆盖人设", original.prompt, saved.prompt)
            assertEquals(original.primary, saved.primary)
            Json.encodeToString(AppState.serializer(), store.snapshot())
        }
        // Simulate loading a serialized local backup into the UI, rather than
        // merely keeping the still-open editor's remembered selection.
        compose.runOnIdle { store.update { it.copy(characters = emptyList()) } }
        compose.waitForIdle()
        compose.runOnIdle { store.update { Json.decodeFromString(AppState.serializer(), backup) } }
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("场景提示").performScrollTo().assertIsSelected()
        compose.onNodeWithText("角色对话").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val saved = store.snapshot().characters.single { it.id == "lin" }
            assertEquals(MessageStyle.CHAT, saved.messageStyle)
            assertEquals(original.prompt, saved.prompt)
            assertEquals(MessageStyle.CHAT, store.snapshot().characters.single { it.id == "xia" }.messageStyle)
        }
    }

    @Test fun sceneShortcutCreatesAnOrdinaryConfigurableGroupMember() {
        compose.onNodeWithContentDescription("角色").performClick()
        compose.onNodeWithContentDescription("创建场景提示").performClick()
        compose.onNodeWithText("创建场景提示").assertIsDisplayed()
        field("角色名称").assertEditableValue("场景提示").performTextReplacement("天气旁白")
        compose.onNodeWithText("场景提示").performScrollTo().assertIsSelected()
        compose.onNodeWithText("保存").performClick()
        val sceneId = compose.runOnIdle {
            val saved = actions.savedCharacters.single()
            assertEquals(MessageStyle.SCENE, saved.messageStyle)
            assertTrue("快捷创建的场景角色应有可编辑的场景人设", saved.prompt.isNotBlank())
            assertEquals("siliconflow", saved.primary.providerId)
            saved.id
        }
        compose.onNodeWithContentDescription("聊天").performClick()
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        openRoomSection("群成员")
        compose.onNodeWithText("天气旁白").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("加入").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertTrue(store.snapshot().rooms.single { it.id == "lobby" }.members.any { it.characterId == sceneId })
        }
        compose.onNodeWithContentDescription("选择要提醒的角色").performClick()
        compose.onNodeWithText("@天气旁白").assertIsDisplayed().performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("描述一下此刻的天气。")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { assertEquals(setOf(sceneId), actions.sent.single().mentions) }
        Espresso.closeSoftKeyboard()
    }

    @Test fun darkGroupMixesCenteredSceneAndOrdinaryBubblesUsingHistoricalStyle() {
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            store.update { state -> state.copy(
                // Current role style intentionally differs from both historical
                // messages: rendering must use each message's saved snapshot.
                characters = state.characters.map { if (it.id == "lin") it.copy(messageStyle = MessageStyle.SCENE) else it } +
                    Character(id = "narrator", name = "旁白", messageStyle = MessageStyle.CHAT),
                rooms = state.rooms.map { if (it.id == "lobby") it.copy(members = it.members + Member("narrator")) else it },
                messages = listOf(
                    ChatMessage(id = "dark-self", roomId = "lobby", senderName = "顾言", text = "都到了吗？我刚到街角，等雨小一点就过去，先帮我留个窗边的位置。", createdAt = time),
                    ChatMessage(id = "dark-lin", roomId = "lobby", characterId = "lin", senderName = "林间", text = "到了，就在窗边。我把热茶放在桌上，外面雨不大，你慢慢走过来就好。", createdAt = time + 1),
                    ChatMessage(id = "dark-scene", roomId = "lobby", characterId = "narrator", senderName = "旁白", text = "雨声渐缓，窗边亮起一盏暖灯。", messageStyle = MessageStyle.SCENE, createdAt = time + 2),
                    ChatMessage(id = "dark-xia", roomId = "lobby", characterId = "xia", senderName = "夏至", text = "我点了热茶。", createdAt = time + 3)
                )
            ) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        assertCompactChatFrame()
        compose.onNodeWithTag("scene-message-dark-scene", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat-bubble-dark-scene", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("message-avatar-dark-scene", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("message-sender-dark-scene", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("场景 · 旁白")
        val viewport = bounds("chat-messages")
        assertEquals("场景提示容器应在聊天区居中", viewport.center.x, bounds("scene-message-dark-scene").center.x, px(2f))
        assertEquals("场景正文不能保留普通消息的头像缩进", viewport.center.x, bounds("message-text-dark-scene").center.x, px(2f))
        listOf("dark-self", "dark-lin", "dark-xia").forEach(::assertAvatarAlignedWithBubble)
        assertBubblesReserveBothAvatarLanes("dark-self", "dark-lin", listOf("dark-self", "dark-lin", "dark-xia"))
        listOf("dark-self", "dark-lin").forEach { id ->
            assertTrue("长句应真实换行以验证最大宽度", bounds("message-text-$id").height >= px(40f))
        }
        compose.onNodeWithTag("message-sender-dark-self", useUnmergedTree = true).assertDoesNotExist()
        listOf("dark-lin", "dark-xia").forEach { id ->
            val sender = bounds("message-sender-$id")
            val bubble = bounds("chat-bubble-$id")
            assertTrue("群聊署名应紧贴气泡上方", bubble.top - sender.bottom in -px(1f)..px(4f))
            assertTrue("群聊署名不应额外占用 28dp 工具行", sender.height <= px(18f))
        }
        compose.onNodeWithContentDescription("朗读消息").assertDoesNotExist()
        assertDarkSurface("chat-messages")
        capturePreview("dark-chat-preview.png")
    }

    @Test fun darkDirectConversationAlignsAvatarsAndOmitsRepeatedSenders() {
        val time = System.currentTimeMillis()
        compose.runOnIdle {
            store.update { state -> state.copy(
                rooms = state.rooms + ChatRoom(id = "dark-direct", name = "林间", direct = true, members = listOf(Member("lin"))),
                messages = listOf(
                    ChatMessage(id = "direct-one", roomId = "dark-direct", senderName = "顾言", text = "下班了吗？", createdAt = time),
                    ChatMessage(id = "direct-two", roomId = "dark-direct", characterId = "lin", senderName = "林间", text = "刚到家，你呢？", createdAt = time + 1),
                    ChatMessage(id = "direct-three", roomId = "dark-direct", senderName = "顾言", text = "我在楼下的小路入口，今天风不大，想沿着河边慢慢走一圈再回来。", createdAt = time + 2),
                    ChatMessage(id = "direct-four", roomId = "dark-direct", characterId = "lin", senderName = "林间", text = "好，等我两分钟。我带上相机和一件外套，顺便看看路边新开的那家书店。", createdAt = time + 3)
                )
            ) }
        }
        compose.onNodeWithText("林间").performClick()
        assertCompactChatFrame()
        val viewport = bounds("chat-messages")
        listOf("direct-one", "direct-two", "direct-three", "direct-four").forEach { id ->
            assertAvatarAlignedWithBubble(id)
            compose.onNodeWithTag("message-sender-$id", useUnmergedTree = true).assertDoesNotExist()
        }
        assertBubblesReserveBothAvatarLanes("direct-three", "direct-four", listOf("direct-one", "direct-two", "direct-three", "direct-four"))
        listOf("direct-three", "direct-four").forEach { id ->
            assertTrue("长句应真实换行以验证最大宽度", bounds("message-text-$id").height >= px(40f))
        }
        assertTrue("自己的短气泡应靠右", bounds("chat-bubble-direct-one").center.x > viewport.center.x)
        assertTrue("对方的短气泡应靠左", bounds("chat-bubble-direct-two").center.x < viewport.center.x)
        compose.onNodeWithContentDescription("朗读消息").assertDoesNotExist()
        assertDarkSurface("chat-messages")
        capturePreview("dark-direct-preview.png")
        assertHistoricalSenderInDetails("direct-one", "顾言")
        compose.onNodeWithTag("chat-bubble-direct-two", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("朗读消息").performClick()
        compose.runOnIdle { assertEquals(listOf("direct-two"), actions.spoken) }
    }

    @Test fun sceneErrorsKeepRetryDetailsAndLongPressActionsAvailable() {
        compose.runOnIdle {
            store.update { it.copy(messages = listOf(ChatMessage(
                id = "scene-error", roomId = "lobby", characterId = "lin", senderName = "场景叙述者",
                text = "远处的脚步声忽然停住。", messageStyle = MessageStyle.SCENE,
                status = MessageStatus.ERROR, error = "连接暂时中断，请重试。",
                segments = listOf(
                    GenerationSegment(label = "A", model = "test/scene-a", text = "远处的脚步声", status = SegmentStatus.CUT),
                    GenerationSegment(label = "B", model = "test/scene-b", text = "忽然停住。", status = SegmentStatus.ERROR)
                )
            ))) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("scene-message-scene-error", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("回复详情").assertIsDisplayed()
        compose.onNodeWithText("场景叙述者 ·", substring = true).assertIsDisplayed()
        compose.onNode(hasText("连接暂时中断，请重试。") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("test/scene-a").assertIsDisplayed()
        compose.onNodeWithText("test/scene-b").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("scene-message-scene-error", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("复制文字").assertHasClickAction()
        compose.onNodeWithContentDescription("回复详情").performClick()
        compose.onNodeWithText("回复详情").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("重试回复").performClick()
        compose.runOnIdle { assertEquals(listOf("scene-error"), actions.retried) }
        compose.onNodeWithTag("message-avatar-scene-error", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun longPressRegeneratesCompletedAndGeneratingRepliesByTheirMessageIds() {
        compose.runOnIdle {
            store.update { it.copy(messages = listOf(
                ChatMessage(id = "regenerate-user", roomId = "lobby", text = "下午去哪里散步？"),
                ChatMessage(id = "regenerate-complete", roomId = "lobby", characterId = "lin", senderName = "林间",
                    text = "先到河边走走吧，傍晚的风会很舒服。",
                    segments = listOf(GenerationSegment(id = "regenerate-a", label = "A", model = "test/walk-model",
                        text = "先到河边走走吧，傍晚的风会很舒服。", status = SegmentStatus.COMPLETE))),
                ChatMessage(id = "regenerate-active", roomId = "lobby", characterId = "xia", senderName = "夏至",
                    text = "我也想想。", status = MessageStatus.THINKING_A)
            )) }
            runtime.value = runtime.value.copy(busyRooms = setOf("lobby"))
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-regenerate-complete", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-actions-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("重新生成").assertIsEnabled()
        compose.onNodeWithContentDescription("编辑消息").assertIsEnabled()
        capturePreview("message-actions-preview.png")
        compose.onNodeWithContentDescription("重新生成").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("regenerate-complete"), actions.regenerated) }
        compose.onNodeWithTag("chat-bubble-regenerate-active", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("重新生成").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("regenerate-complete", "regenerate-active"), actions.regenerated)
            assertTrue("重新生成应使用独立操作，不冒充错误重试", actions.retried.isEmpty())
        }
    }

    @Test fun ownMessageEditingPrefillsCancelsRejectsBlankAndUpdatesOnlyThatMessage() {
        val time = System.currentTimeMillis()
        val original = ChatMessage(id = "editable-user", roomId = "lobby", senderName = "顾言",
            text = "下午去河边散步吧。", mentions = listOf("lin"), createdAt = time)
        val reply = ChatMessage(id = "editable-later-reply", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "好，我带上相机。", contextMessageIds = listOf(original.id), createdAt = time + 1)
        val initial = compose.runOnIdle {
            store.update { it.copy(messages = listOf(original, reply)) }
            store.snapshot()
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("编辑消息").assertIsEnabled().performClick()
        compose.onNodeWithTag("edit-message-dialog").assertIsDisplayed()
        compose.onNodeWithTag("edit-message-input").assertEditableValue(original.text)
            .performTextReplacement("这段修改准备取消。")
        compose.onNodeWithTag("cancel-message-edit").performClick()
        compose.onNodeWithTag("edit-message-dialog").assertDoesNotExist()
        Espresso.closeSoftKeyboard()
        compose.runOnIdle {
            assertEquals(initial, store.snapshot())
            assertTrue(actions.editedMessages.isEmpty())
            assertTrue(actions.editedAndSentMessages.isEmpty())
        }
        compose.onNodeWithTag("message-text-${original.id}", useUnmergedTree = true).assertTextEquals(original.text)

        compose.onNodeWithTag("chat-bubble-${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("重新生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("思考内容（COT）").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑消息").performClick()
        compose.onNodeWithTag("edit-message-input").assertEditableValue(original.text).performTextReplacement(" \n \t ")
        compose.onNodeWithTag("save-message-edit").assertIsNotEnabled()
        compose.onNodeWithTag("save-and-send-message-edit").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(initial, store.snapshot())
            assertTrue(actions.editedMessages.isEmpty())
            assertTrue(actions.editedAndSentMessages.isEmpty())
        }
        val edited = "下午改去公园散步。\n我们在南门见。"
        compose.onNodeWithTag("edit-message-input").performTextReplacement(edited)
        compose.waitUntil(timeoutMillis = 8_000) { imeState().let { it.visible && it.height > px(100f) } }
        compose.onNodeWithTag("save-message-edit").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("save-and-send-message-edit").assertIsDisplayed().assertIsEnabled()
        capturePreview("edit-message-preview.png")
        compose.onNodeWithTag("save-message-edit").performClick()
        compose.onNodeWithTag("edit-message-dialog").assertDoesNotExist()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("message-text-${original.id}", useUnmergedTree = true).assertTextEquals(edited)
        compose.runOnIdle {
            assertEquals(listOf(original.id to edited), actions.editedMessages)
            assertEquals("保存应原地更新正文并保留身份、时间、提及和后续回复",
                initial.copy(messages = listOf(original.copy(text = edited), reply)), store.snapshot())
            assertTrue(actions.regenerated.isEmpty())
            assertTrue(actions.retried.isEmpty())
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.editedAndSentMessages.isEmpty())
        }
        compose.onNodeWithTag("chat-bubble-${reply.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("编辑消息").assertIsEnabled()
        compose.onNodeWithContentDescription("重新生成").assertIsEnabled()
    }

    @Test fun aiMessageCanBeEditedInPlaceAndOldSegmentsAreCleared() {
        val question = ChatMessage(id = "ai-edit-question", roomId = "lobby", text = "下午几点见？")
        val original = ChatMessage(id = "ai-edit-target", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "三点见。", contextMessageIds = listOf(question.id), segments = listOf(
                GenerationSegment(label = "A", text = "三点", reasoning = "旧的思考", status = SegmentStatus.CUT),
                GenerationSegment(label = "B", text = "见。", status = SegmentStatus.COMPLETE)))
        val later = ChatMessage(id = "ai-edit-later", roomId = "lobby", characterId = "xia", senderName = "夏至", text = "好，记下了。")
        compose.runOnIdle { store.update { it.copy(messages = listOf(question, original, later)) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("编辑消息").performScrollTo().performClick()
        compose.onNodeWithTag("edit-message-input").assertEditableValue(original.text).performTextReplacement("改成四点见。")
        compose.waitUntil(timeoutMillis = 8_000) { imeState().visible }
        compose.onNodeWithTag("save-message-edit").assertIsDisplayed()
        capturePreview("ai-edit-message-preview.png")
        compose.onNodeWithTag("save-message-edit").performClick()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("message-text-${original.id}", useUnmergedTree = true).assertTextEquals("改成四点见。")
        compose.runOnIdle {
            assertEquals(listOf(question, original.copy(text = "改成四点见。", segments = emptyList()), later), store.snapshot().messages)
            assertEquals(listOf(original.id to "改成四点见。"), actions.editedMessages)
            assertTrue(actions.regenerated.isEmpty())
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.editedAndSentMessages.isEmpty())
        }
        compose.onNodeWithTag("chat-bubble-${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("reasoning-empty").assertIsDisplayed()
        compose.onNodeWithText("旧的思考").assertDoesNotExist()
    }

    @Test fun ownEditedMessageCanSaveAndSendInPlaceAndResendUnchangedTextWithKeyboardVisible() {
        val time = System.currentTimeMillis()
        val original = ChatMessage(id = "edit-send-human", roomId = "lobby", senderName = "顾言",
            text = "下午去河边散步吧。", mentions = listOf("lin"), createdAt = time,
            speechPreparation = SpeechPreparationRecord(text = "过期内容", status = SpeechPreparationStatus.COMPLETE))
        val later = ChatMessage(id = "edit-send-human-later", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "好，我带上相机。", contextMessageIds = listOf(original.id), createdAt = time + 1)
        compose.runOnIdle { store.update { it.copy(messages = listOf(original, later)) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("编辑消息").performClick()
        compose.onNodeWithTag("edit-message-input").assertEditableValue(original.text).performTextReplacement(" \n\t ")
        compose.onNodeWithTag("save-message-edit").assertIsNotEnabled()
        compose.onNodeWithTag("save-and-send-message-edit").assertIsNotEnabled()
        val edited = "下午改去公园。\n我们四点在南门见。"
        compose.onNodeWithTag("edit-message-input").performTextReplacement(edited)
        try {
            assertEditActionsVisibleAboveKeyboard()
            capturePreview("edit-and-send-preview.png")
            compose.onNodeWithContentDescription("保存并发送编辑消息").performClick()
            compose.onNodeWithTag("edit-message-dialog").assertDoesNotExist()
        } finally { Espresso.closeSoftKeyboard() }
        compose.onNodeWithTag("message-text-${original.id}", useUnmergedTree = true).assertTextEquals(edited)
        val updated = original.copy(text = edited, speechPreparation = null)
        compose.runOnIdle {
            assertEquals(listOf(original.id to edited), actions.editedAndSentMessages)
            assertEquals("保留原消息身份并删除后续消息", listOf(updated), store.snapshot().messages)
            assertEquals(1, store.snapshot().messages.count { it.characterId == null })
            assertTrue(actions.editedMessages.isEmpty())
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.regenerated.isEmpty())
            assertTrue(actions.retried.isEmpty())
        }

        // Sending an existing edit again is intentional, even when no character changes.
        compose.onNodeWithTag("chat-bubble-${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("编辑消息").performClick()
        compose.onNodeWithTag("edit-message-input").assertEditableValue(edited).performClick()
        try {
            assertEditActionsVisibleAboveKeyboard()
            compose.onNodeWithTag("save-and-send-message-edit").performClick()
        } finally { Espresso.closeSoftKeyboard() }
        compose.runOnIdle {
            assertEquals(listOf(original.id to edited, original.id to edited), actions.editedAndSentMessages)
            assertEquals(listOf(updated), store.snapshot().messages)
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.regenerated.isEmpty())
        }
    }

    @Test fun aiEditedMessageCanSaveAndSendWithoutBecomingHumanAndRemovesLaterReplies() {
        val time = System.currentTimeMillis()
        val question = ChatMessage(id = "edit-send-ai-question", roomId = "lobby", text = "下午几点见？", createdAt = time)
        val original = ChatMessage(id = "edit-send-ai-target", roomId = "lobby", characterId = "lin", senderName = "林间",
            text = "三点见。", mentions = listOf("xia"), contextMessageIds = listOf(question.id), createdAt = time + 1,
            roundId = "original-round", error = "过期提示", segments = listOf(
                GenerationSegment(label = "A", text = "三点", reasoning = "过期思考", status = SegmentStatus.CUT),
                GenerationSegment(label = "B", text = "见。", status = SegmentStatus.COMPLETE)),
            speechPreparation = SpeechPreparationRecord(text = "三点见。", status = SpeechPreparationStatus.COMPLETE))
        val later = ChatMessage(id = "edit-send-ai-later", roomId = "lobby", characterId = "xia", senderName = "夏至",
            text = "好，记下了。", contextMessageIds = listOf(question.id, original.id), createdAt = time + 2)
        compose.runOnIdle { store.update { it.copy(messages = listOf(question, original, later)) } }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("编辑消息").performScrollTo().performClick()
        compose.onNodeWithTag("edit-message-input").assertEditableValue(original.text).performTextReplacement(" ")
        compose.onNodeWithTag("save-message-edit").assertIsNotEnabled()
        compose.onNodeWithTag("save-and-send-message-edit").assertIsNotEnabled()
        val edited = "改成四点见。到时我先在南门等你们。"
        compose.onNodeWithTag("edit-message-input").performTextReplacement(edited)
        try {
            assertEditActionsVisibleAboveKeyboard()
            compose.onNodeWithTag("save-and-send-message-edit").performClick()
        } finally { Espresso.closeSoftKeyboard() }
        compose.onNodeWithTag("edit-message-dialog").assertDoesNotExist()
        compose.onNodeWithTag("message-text-${original.id}", useUnmergedTree = true).assertTextEquals(edited)
        compose.onNodeWithTag("message-text-${later.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(original.id to edited), actions.editedAndSentMessages)
            assertEquals(listOf(question, original.copy(text = edited, segments = emptyList(), error = "",
                status = MessageStatus.COMPLETE, speechPreparation = null)), store.snapshot().messages)
            assertEquals(1, store.snapshot().messages.count { it.characterId == null })
            assertTrue(actions.editedMessages.isEmpty())
            assertTrue(actions.sent.isEmpty())
            assertTrue(actions.regenerated.isEmpty())
            assertTrue(actions.retried.isEmpty())
        }
    }

    @Test fun bReasoningOutputCompatibilitySavesIndependentlyAndCanBeDisabled() {
        compose.runOnIdle { store.update { state -> state.copy(characters = state.characters.map {
            it.copy(primary = it.primary.copy(thinking = ThinkingMode.OFF), secondary = it.secondary.copy(thinking = ThinkingMode.OFF))
        }) } }
        compose.onNodeWithContentDescription("角色").performClick()
        compose.onAllNodesWithText("模型")[0].performClick()
        compose.onNodeWithText("双模型接续").performScrollTo().performClick()
        compose.onNodeWithTag("secondary-output-source").performScrollTo().performClick()
        compose.onNodeWithText("思考字段兼容").performClick()
        compose.onNodeWithTag("b-thinking-forced").performScrollTo().assertIsDisplayed()
        capturePreview("b-output-compatibility-preview.png")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val actor = store.snapshot().characters.single { it.id == "lin" }
            assertEquals(ReplyOutputSource.REASONING, actor.secondaryOutputSource)
            assertEquals(ThinkingMode.ON, actor.secondary.thinking)
            assertEquals(ThinkingMode.OFF, actor.primary.thinking)
            assertEquals(ReplyOutputSource.CONTENT, store.snapshot().characters.single { it.id == "xia" }.secondaryOutputSource)
        }
        compose.onNodeWithText("林间").performClick()
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithTag("secondary-output-source").performScrollTo().performClick()
        compose.onNodeWithText("正文").performClick()
        compose.onNodeWithTag("b-thinking-forced").assertDoesNotExist()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(ReplyOutputSource.CONTENT, store.snapshot().characters.single { it.id == "lin" }.secondaryOutputSource) }
    }

    @Test fun dialogueExtractionSettingsAreEditableOptionalAndSharedAcrossSpeechEngines() {
        compose.runOnIdle { store.update { it.copy(providers = it.providers + Provider(id = "extractor", name = "对白模型服务", baseUrl = "https://speech-filter.example/v1")) } }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        compose.onNodeWithTag("dialogue-extraction-model").assertDoesNotExist()
        compose.onNodeWithTag("dialogue-extraction-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("dialogue-extraction-context").performScrollTo().assertTextContains("同一会话、同一角色上一条有效朗读指令", substring = true)
        compose.onNodeWithTag("dialogue-extraction-context").assertTextContains("不发送角色设定、原聊天历史或上一条回复原文", substring = true)
        compose.onNodeWithTag("dialogue-extraction-provider").performScrollTo().performClick()
        compose.onNodeWithText("对白模型服务").performClick()
        compose.onNodeWithTag("dialogue-extraction-model").performScrollTo().performTextReplacement("small/dialogue-only")
        val customPrompt = "只保留这条回复中说出口的话，动作和心理活动不要读。输出 JSON，text 是对白，instruction 是朗读语气；没有依据则留空。"
        compose.onNodeWithTag("dialogue-extraction-prompt").performScrollTo().performTextReplacement(" ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("dialogue-extraction-prompt").performTextReplacement(customPrompt)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("dialogue-extraction-model").performScrollTo()
        capturePreview("dialogue-extraction-preview.png")
        compose.onNodeWithTag("doubao-tts2-preset").performScrollTo().performClick()
        compose.onNodeWithTag("doubao-tts2-voice-guide").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("doubao-tts2-instructions-doc").performScrollTo().assertIsDisplayed()
        capturePreview("doubao-instructions-preview.png")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val extraction = store.snapshot().tts.dialogueExtraction
            assertTrue(extraction.enabled)
            assertEquals("extractor", extraction.model.providerId)
            assertEquals("small/dialogue-only", extraction.model.model)
            assertEquals(customPrompt, extraction.systemPrompt)
            assertEquals(TtsEngine.DOUBAO, store.snapshot().tts.engine)
            assertEquals("seed-tts-2.0", store.snapshot().tts.resourceId)
            assertTrue(store.snapshot().characters.all { it.voice == Character().voice })
            assertTrue(actions.sent.isEmpty())
        }
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        compose.onNodeWithTag("dialogue-extraction-prompt").performScrollTo().assertEditableValue(customPrompt)
        compose.onNodeWithText("语音引擎").performScrollTo()
        compose.onNodeWithText("硅基流动").performClick()
        compose.onNodeWithTag("dialogue-extraction-toggle").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(TtsEngine.SILICONFLOW, store.snapshot().tts.engine)
            assertFalse(store.snapshot().tts.dialogueExtraction.enabled)
            assertEquals(customPrompt, store.snapshot().tts.dialogueExtraction.systemPrompt)
        }
        compose.onNodeWithText("语音服务").performScrollTo().performClick()
        compose.onNodeWithTag("dialogue-extraction-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("dialogue-extraction-reset").performScrollTo().performClick()
        compose.onNodeWithTag("dialogue-extraction-prompt").performScrollTo().assertEditableValue(DEFAULT_DIALOGUE_EXTRACTION_PROMPT)
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertTrue(store.snapshot().tts.dialogueExtraction.enabled)
            assertEquals(DEFAULT_DIALOGUE_EXTRACTION_PROMPT, store.snapshot().tts.dialogueExtraction.systemPrompt)
            assertEquals("small/dialogue-only", store.snapshot().tts.dialogueExtraction.model.model)
        }
    }

    @Test fun sceneRegenerationWorksButUserRemovedAndDeletedRolesHaveNoRegenerateAction() {
        compose.runOnIdle {
            store.update { state -> state.copy(
                rooms = state.rooms.map { if (it.id == "lobby") it.copy(members = listOf(Member("lin"))) else it },
                messages = listOf(
                    ChatMessage(id = "boundary-user", roomId = "lobby", text = "继续这个故事吧。",
                        segments = listOf(GenerationSegment(reasoning = "测试数据：用户消息不应暴露思考入口。"))),
                    ChatMessage(id = "boundary-scene", roomId = "lobby", characterId = "lin", senderName = "旁白",
                        text = "门外传来轻轻的敲门声。", messageStyle = MessageStyle.SCENE),
                    ChatMessage(id = "boundary-removed", roomId = "lobby", characterId = "xia", senderName = "夏至", text = "这是退出群聊前的回复。"),
                    ChatMessage(id = "boundary-deleted", roomId = "lobby", characterId = "deleted-role", senderName = "旧角色", text = "这条历史消息仍可查看。")
                )
            ) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("scene-message-boundary-scene", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("重新生成").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("boundary-scene"), actions.regenerated) }

        compose.onNodeWithTag("chat-bubble-boundary-user", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("编辑消息").assertIsEnabled()
        compose.onNodeWithContentDescription("重新生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("思考内容（COT）").assertDoesNotExist()
        Espresso.pressBack()
        listOf("boundary-removed", "boundary-deleted").forEach { id ->
            compose.onNodeWithTag("chat-bubble-$id", useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNodeWithContentDescription("重新生成").assertDoesNotExist()
            compose.onNodeWithContentDescription("思考内容（COT）").assertHasClickAction()
            Espresso.pressBack()
        }
        compose.runOnIdle { assertEquals(listOf("boundary-scene"), actions.regenerated) }
    }

    @Test fun recordedThinkingIsSeparateFromRepliesAndCopiesBothModelSegmentsExactly() {
        val firstReasoning = "测试思考 A：先确认天气与集合地点。"
        val secondReasoning = "测试思考 B：接着安排步行路线，不重复前一段。"
        val body = "我们先到河边，再慢慢散步。"
        compose.runOnIdle {
            store.update { it.copy(messages = listOf(ChatMessage(
                id = "cot-message", roomId = "lobby", characterId = "lin", senderName = "林间", text = body,
                segments = listOf(
                    GenerationSegment(id = "cot-a", label = "A", model = "test/cot-a", text = "我们先到河边，",
                        status = SegmentStatus.CUT, reasoning = firstReasoning),
                    GenerationSegment(id = "cot-b", label = "B", model = "test/cot-b", text = "再慢慢散步。",
                        status = SegmentStatus.COMPLETE, reasoning = secondReasoning)
                )
            ))) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("message-text-cot-message", useUnmergedTree = true).assertTextEquals(body)
        compose.onNodeWithText(firstReasoning, substring = true).assertDoesNotExist()
        compose.onNodeWithText(secondReasoning, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("chat-bubble-cot-message", useUnmergedTree = true).performClick()
        compose.onNodeWithText("回复详情").assertIsDisplayed()
        compose.onNodeWithText(firstReasoning, substring = true).assertDoesNotExist()
        compose.onNodeWithText(secondReasoning, substring = true).assertDoesNotExist()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("chat-bubble-cot-message", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("reasoning-sheet").assertIsDisplayed()
        compose.onNodeWithTag("reasoning-text-cot-a", useUnmergedTree = true).performScrollTo().assertTextEquals(firstReasoning)
        compose.onNodeWithTag("reasoning-text-cot-b", useUnmergedTree = true).performScrollTo().assertTextEquals(secondReasoning)
        compose.onNode(hasText("test/cot-a", substring = true) and hasAnyAncestor(hasTestTag("reasoning-sheet"))).assertExists()
        compose.onNode(hasText("test/cot-b", substring = true) and hasAnyAncestor(hasTestTag("reasoning-sheet"))).assertExists()
        compose.onNodeWithTag("reasoning-text-cot-a", useUnmergedTree = true).performScrollTo()
        capturePreview("cot-preview.png")
        setSyntheticClipboard("before-cot-copy")
        compose.onNodeWithTag("copy-reasoning").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("模型 A · test/cot-a\n$firstReasoning\n\n模型 B · test/cot-b\n$secondReasoning", clipboardText())
            assertFalse("复制思考内容不能混入回复正文", clipboardText().contains(body))
        }
        compose.onNodeWithTag("reasoning-sheet").assertIsDisplayed()
    }

    @Test fun emptyThinkingIsExplicitAndCopyEnablesOnlyAfterRecordedContentArrives() {
        compose.runOnIdle {
            store.update { it.copy(messages = listOf(ChatMessage(
                id = "cot-empty-message", roomId = "lobby", characterId = "lin", senderName = "林间", text = "这是一条没有思考记录的回复。",
                segments = listOf(GenerationSegment(id = "cot-empty-a", label = "A", model = "test/no-cot",
                    text = "这是一条没有思考记录的回复。", status = SegmentStatus.COMPLETE))
            ))) }
        }
        compose.onNodeWithText("片刻 · 茶水间").performClick()
        compose.onNodeWithTag("chat-bubble-cot-empty-message", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("思考内容（COT）").performScrollTo().performClick()
        compose.onNodeWithTag("reasoning-empty").assertIsDisplayed().assertTextEquals("模型未返回思考内容，或这条历史消息未记录。")
        compose.onNodeWithTag("reasoning-text-cot-empty-a", useUnmergedTree = true).assertDoesNotExist()
        setSyntheticClipboard("unchanged-empty-thinking")
        compose.onNodeWithTag("copy-reasoning").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("unchanged-empty-thinking", clipboardText())
            store.updateMessage("cot-empty-message") { it.copy(status = MessageStatus.THINKING_A) }
            runtime.value = runtime.value.copy(busyRooms = setOf("lobby"))
        }
        compose.onNodeWithTag("reasoning-empty").assertTextEquals("模型尚未返回思考内容。")
        compose.onNodeWithTag("copy-reasoning").assertIsNotEnabled()
        val received = "测试增量思考：已收到第一段。"
        compose.runOnIdle {
            store.updateMessage("cot-empty-message") { message -> message.copy(segments = message.segments.map { it.copy(reasoning = received) }) }
        }
        compose.onNodeWithTag("reasoning-empty").assertDoesNotExist()
        compose.onNodeWithTag("reasoning-text-cot-empty-a", useUnmergedTree = true).assertTextEquals(received)
        compose.onNodeWithTag("copy-reasoning").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("模型 A · test/no-cot\n$received", clipboardText()) }

        val longReasoning = (1..36).joinToString("\n\n") { index ->
            "测试长思考第 $index 段：这段虚构内容只用于验证滚动和完整复制，不代表真实模型输出。"
        } + "\n\n测试末尾：第三十六段之后的最后一句也必须完整保留。"
        compose.runOnIdle {
            store.updateMessage("cot-empty-message") { message -> message.copy(segments = message.segments.map { it.copy(reasoning = longReasoning) }) }
        }
        compose.waitForIdle()
        fun scrollRange() = compose.onNodeWithTag("reasoning-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        val initialScroll = scrollRange().value()
        assertTrue("长思考应超过一屏并可滚动", scrollRange().maxValue() > bounds("reasoning-scroll").height)
        val copyTop = bounds("copy-reasoning").top
        fun assertCopyRemainsVisible() {
            compose.onNodeWithTag("copy-reasoning").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            val fullButton = compose.onNodeWithTag("copy-reasoning").getUnclippedBoundsInRoot()
            assertTrue("复制按钮不能被长正文挤出面板", px(fullButton.bottom.value) <= bounds("reasoning-sheet").bottom + px(1f))
            assertTrue("复制按钮应位于正文滚动区外", px(fullButton.top.value) >= bounds("reasoning-scroll").bottom - px(1f))
            assertEquals("滚动思考正文时复制按钮应固定可见", copyTop, bounds("copy-reasoning").top, px(1f))
        }
        assertCopyRemainsVisible()
        repeat(2) { step ->
            val remaining = scrollRange().maxValue() - scrollRange().value()
            val distance = if (step == 0) remaining / 2f else remaining + px(1f)
            compose.onNodeWithTag("reasoning-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                assertTrue("思考正文应响应滚动操作", scrollBy(0f, distance))
            }
            compose.waitForIdle()
            assertCopyRemainsVisible()
        }
        assertTrue("必须实际滚动正文才能到达长思考末尾", scrollRange().value() > initialScroll)
        assertTrue("长思考应能滚动到真实末尾", scrollRange().maxValue() - scrollRange().value() <= 1f)
        val fullText = compose.onNodeWithTag("reasoning-text-cot-empty-a", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val viewport = bounds("reasoning-scroll")
        assertTrue("最后一句应完整出现在正文滚动区内", px(fullText.bottom.value) <= viewport.bottom + px(1f))
        assertTrue("末尾不能滚出正文可视区", px(fullText.bottom.value) >= viewport.top + px(22f))
        setSyntheticClipboard("before-long-cot-copy")
        compose.onNodeWithTag("copy-reasoning").performClick()
        compose.runOnIdle { assertEquals("模型 A · test/no-cot\n$longReasoning", clipboardText()) }
    }

    private fun openCompression() {
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithTag("room-compression-action").performScrollTo().performClick()
        compose.onNodeWithTag("compression-screen").assertIsDisplayed()
    }

    private fun seedCompressionConversation(count: Int = 26, direct: Boolean = false): AppState = compose.runOnIdle {
        val time = System.currentTimeMillis()
        store.update { state -> state.copy(
            rooms = state.rooms.map { if (it.id == "lobby") it.copy(direct = direct,
                members = if (direct) listOf(Member("lin", 0, true)) else it.members) else it },
            messages = (0 until count).map { index -> ChatMessage(id = "compression-history-$index", roomId = "lobby",
                characterId = if (index % 2 == 0) null else "lin", senderName = if (index % 2 == 0) "我" else "林间",
                text = "第 $index 条聊天：讨论周末看电影的安排。", createdAt = time + index)
            }) }
        store.snapshot()
    }

    private fun completeCompressionPreview(roomId: String = "lobby") = compose.runOnIdle {
        val pending = runtime.value.compressions.getValue(roomId)
        val plans = buildCompressionPlans(store.snapshot(), roomId, pending.settings)
        runtime.value = runtime.value.copy(compressions = runtime.value.compressions + (roomId to pending.copy(
            status = CompressionStatus.READY, completed = plans.size, total = plans.size,
            drafts = plans.map { CompressionDraft(it.characterId, it.characterName, it.sourceMessages.size, it.recentMessages.size,
                "${it.characterName}的前情：大家约好周末去看电影，时间还没有定下。") }
        )))
    }

    private fun openHomeRoomActions(roomId: String) {
        compose.onNodeWithTag("chat-list").performScrollToNode(hasTestTag("chat-list-row-$roomId"))
        compose.onNodeWithTag("chat-list-row-$roomId").performTouchInput { longClick() }
        compose.onNodeWithTag("room-action-sheet").assertIsDisplayed()
    }

    private fun seedRoomManagementConversation(includeDirect: Boolean = false): AppState = compose.runOnIdle {
        val time = System.currentTimeMillis()
        store.update { state -> state.copy(
            rooms = state.rooms + ChatRoom(id = "management-other", name = "最近的会话",
                members = listOf(Member("lin"), Member("xia")), createdAt = time) +
                if (includeDirect) listOf(ChatRoom(id = "management-direct", name = "林间的私聊",
                    direct = true, members = listOf(Member("lin")), createdAt = time - 30_000)) else emptyList(),
            messages = listOf(
                ChatMessage(id = "management-old-user", roomId = "lobby", text = "这是准备清理的旧提问。", createdAt = time - 60_000),
                ChatMessage(id = "management-old-reply", roomId = "lobby", characterId = "lin", senderName = "林间",
                    text = "这条旧回答包含独立保存的思考记录。", createdAt = time - 59_000,
                    contextMessageIds = listOf("management-old-user"),
                    segments = listOf(GenerationSegment(label = "A", text = "这条旧回答包含独立保存的思考记录。",
                        reasoning = "测试思考：清空或删除时也必须一并移除。", status = SegmentStatus.COMPLETE))),
                ChatMessage(id = "management-other-message", roomId = "management-other", characterId = "xia", senderName = "夏至",
                    text = "另一段会话继续保留。", createdAt = time)
            )
        ) }
        store.snapshot()
    }

    private fun openRoomSection(description: String) {
        compose.onNodeWithContentDescription("会话设置").performClick()
        compose.onNodeWithContentDescription(description).performClick()
    }

    private fun setRoutingSlider(tag: String, value: Int) {
        val progress = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(progress and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue("接话滑块应接受 $value", setProgress(value.toFloat()))
            }
    }

    private fun seedCompactConversation() = compose.runOnIdle {
        val time = System.currentTimeMillis()
        store.update { it.copy(messages = listOf(
            ChatMessage(id = "layout-first", roomId = "lobby", senderName = "我", text = "周末一起去海边走走吧。", createdAt = time),
            ChatMessage(id = "layout-second", roomId = "lobby", characterId = "lin", senderName = "林间",
                text = "好啊！我带相机，想拍一组傍晚的海。", createdAt = time + 1)
        )) }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun windowBounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    private fun px(dp: Float): Float = dp * compose.activity.resources.displayMetrics.density

    private fun setSyntheticClipboard(text: String) = compose.runOnIdle {
        (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("RoleChat UI test", text))
    }

    /** Called only after this test has overwritten the clipboard with synthetic data. */
    private fun clipboardText(): String =
        (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()

    private fun assertHistoricalSenderInDetails(messageId: String, sender: String) {
        compose.onNodeWithTag("message-sender-$messageId", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat-bubble-$messageId", useUnmergedTree = true).performClick()
        compose.onNodeWithText("回复详情").assertIsDisplayed()
        compose.onNodeWithText("$sender ·", substring = true).assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
    }

    private fun avatarFixture(color: Int): String {
        val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }

    private fun assertPhotoWithin(parentTag: String, expectedColor: Int) {
        val matcher = hasContentDescription("头像照片") and hasAnyAncestor(hasTestTag(parentTag))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        val photo = compose.onNode(matcher, useUnmergedTree = true).assertIsDisplayed()
        val pixels = photo.captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        val expected = androidx.compose.ui.graphics.Color(expectedColor)
        assertEquals("照片红色通道：$parentTag", expected.red, center.red, .04f)
        assertEquals("照片绿色通道：$parentTag", expected.green, center.green, .04f)
        assertEquals("照片蓝色通道：$parentTag", expected.blue, center.blue, .04f)
    }

    private fun assertAvatarAlignedWithBubble(messageId: String) {
        compose.onNodeWithTag("chat-bubble-$messageId", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("message-avatar-$messageId", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("头像与气泡顶边应对齐：$messageId", bounds("chat-bubble-$messageId").top,
            bounds("message-avatar-$messageId").top, px(1f))
    }

    private fun assertBubblesReserveBothAvatarLanes(selfId: String, otherId: String, messageIds: List<String>) {
        val leftAvatar = bounds("message-avatar-$otherId")
        val rightAvatar = bounds("message-avatar-$selfId")
        val leftGap = bounds("chat-bubble-$otherId").left - leftAvatar.right
        val rightGap = rightAvatar.left - bounds("chat-bubble-$selfId").right
        assertTrue("头像与气泡之间应留出间距", leftGap > 0f && rightGap > 0f)
        val contentLeft = leftAvatar.right + leftGap
        val contentRight = rightAvatar.left - rightGap
        messageIds.forEach { id ->
            val bubble = bounds("chat-bubble-$id")
            assertTrue("气泡不能侵入左侧头像区域：$id", bubble.left >= contentLeft - px(1f))
            assertTrue("气泡不能侵入右侧头像区域：$id", bubble.right <= contentRight + px(1f))
            assertTrue("长气泡必须同时预留左右头像区域：$id", bubble.width <= contentRight - contentLeft + px(1f))
        }
    }

    /** Check rendered colors, so a light surface cannot pass through semantic tags alone. */
    private fun assertDarkSurface(tag: String? = null) {
        compose.waitForIdle()
        val pixels = (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag))
            .captureToImage().toPixelMap()
        var dark = 0
        var samples = 0
        val stepX = (pixels.width / 40).coerceAtLeast(1)
        val stepY = (pixels.height / 40).coerceAtLeast(1)
        for (y in stepY / 2 until pixels.height step stepY) {
            for (x in stepX / 2 until pixels.width step stepX) {
                val color = pixels[x, y]
                if (0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue < 0.35f) dark++
                samples++
            }
        }
        assertTrue("默认界面的主要背景应实际渲染为深色，暗像素占比=${dark.toFloat() / samples}", dark.toFloat() / samples >= 0.70f)
    }

    private fun assertCompactChatFrame(maxComposerHeightDp: Float = 76f) {
        compose.waitForIdle()
        val toolbar = bounds("chat-toolbar")
        val messages = bounds("chat-messages")
        val composer = bounds("chat-composer")
        assertEquals("聊天标题只占一行 52dp", px(52f), toolbar.height, px(.75f))
        assertTrue("聊天区应紧跟标题栏", messages.top - toolbar.bottom in -px(1f)..px(2f))
        assertTrue("聊天区不能延伸到输入区下面", messages.bottom <= composer.top + px(1f))
        assertTrue("输入区不应多占无关工具行", composer.height <= px(maxComposerHeightDp))
    }

    private fun assertReplyTailIsVisible(id: String, expectAtBottom: Boolean = true) {
        compose.waitForIdle()
        val list = bounds("chat-messages")
        val composer = bounds("chat-composer")
        // Clipped bounds alone would pass even with hidden final lines. The full
        // text layout's bottom must actually fit above the composer.
        val text = compose.onNodeWithTag("message-text-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val message = compose.onNodeWithTag("message-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("长回复最后一行必须完整露出", px(text.bottom.value) <= composer.top + px(1f))
        assertTrue("回复最后一行不能被滚出聊天区顶部", px(text.bottom.value) >= list.top + px(24f))
        if (expectAtBottom) assertTrue("自动跟随应到达长回复真实末尾", px(text.bottom.value) >= list.bottom - px(88f))
        assertTrue("整条回复的底边不能藏在输入区下方", px(message.bottom.value) <= composer.top + px(1f))
    }

    private data class ImeState(val visible: Boolean, val topInWindow: Float, val height: Float)
    /** Exercised on the 360dp emulator; all three dialog actions must stay usable with the real IME. */
    private fun assertEditActionsVisibleAboveKeyboard(tags: List<String> = listOf("cancel-message-edit", "save-message-edit", "save-and-send-message-edit")) {
        compose.waitUntil(timeoutMillis = 8_000) { imeState().let { it.visible && it.height > px(100f) } }
        val ime = imeState()
        val screenOffset = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val screen = IntArray(2).also(decor::getLocationOnScreen)
            val window = IntArray(2).also(decor::getLocationInWindow)
            (screen[1] - window[1]).toFloat()
        }
        val keyboardTop = ime.topInWindow + screenOffset
        tags.forEach { tag ->
            val action = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            val full = action.getUnclippedBoundsInRoot()
            val node = action.fetchSemanticsNode()
            val visible = node.boundsInRoot
            assertEquals("编辑操作不能被弹窗裁掉：$tag", px((full.bottom - full.top).value), visible.height, px(1f))
            assertEquals("编辑操作不能在小屏横向截断：$tag", px((full.right - full.left).value), visible.width, px(1f))
            // A dialog has its own window. Root bounds alone can report a button as
            // displayed even when the separate keyboard window covers it on screen.
            val bottomOnScreen = node.positionOnScreen.y + node.size.height
            if (bottomOnScreen > keyboardTop + px(1f)) capturePreview("edit-actions-obstructed.png")
            assertTrue("键盘不能遮住 $tag：按钮底部=$bottomOnScreen，键盘顶部=$keyboardTop",
                bottomOnScreen <= keyboardTop + px(1f))
        }
        if ("save-and-send-message-edit" in tags) compose.onNodeWithContentDescription("保存并发送编辑消息").assertIsDisplayed().assertIsEnabled()
    }

    private fun waitForImeAboveComposer() {
        compose.waitUntil(timeoutMillis = 8_000) {
            val ime = imeState()
            ime.visible && ime.height > px(100f) &&
                abs(windowBounds("chat-composer").bottom - ime.topInWindow) <= px(3f)
        }
    }

    private fun imeState(): ImeState = compose.runOnIdle {
        val decor = compose.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
        val height = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val location = IntArray(2)
        decor.getLocationInWindow(location)
        ImeState(insets?.isVisible(WindowInsetsCompat.Type.ime()) == true, (location[1] + decor.height - height).toFloat(), height.toFloat())
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun SemanticsNodeInteraction.assertEditableValue(value: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(value)))

    /** Visual QA artifact, captured after the native window has had time to present. */
    private fun capturePreview(filename: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose semantics can already expose a sheet while SurfaceFlinger still
        // displays the preceding frame. Cross real display frames, then wait for a
        // short quiet window before taking the system screenshot (including IME).
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            val decor = compose.activity.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { frames.countDown() } }
        }
        check(frames.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "窗口未及时完成截图准备" }
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "未能取得界面截图：$filename" }
        try {
            java.io.File(instrumentation.targetContext.filesDir, filename).outputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally { bitmap.recycle() }
    }

    private data class Sent(val roomId: String, val text: String, val mentions: Set<String>)
    private class FakeActions(private val store: StateStore, private val runtime: MutableStateFlow<RuntimeUi>) : AppActions {
        var voiceListRefreshes = 0
        val voiceListCredentials = mutableListOf<Pair<String?, String?>>()
        val sent = mutableListOf<Sent>()
        val savedRooms = mutableListOf<ChatRoom>()
        val savedCharacters = mutableListOf<Character>()
        val savedProviders = mutableListOf<Pair<Provider, String?>>()
        val modelListRefreshes = mutableListOf<String>()
        val retried = mutableListOf<String>()
        val regenerated = mutableListOf<String>()
        val editedMessages = mutableListOf<Pair<String, String>>()
        val editedAndSentMessages = mutableListOf<Pair<String, String>>()
        val spoken = mutableListOf<String>()
        val voicePreviews = mutableListOf<Character>()
        var audioStopCount = 0
        val deletedRooms = mutableListOf<String>()
        val clearedRooms = mutableListOf<String>()
        val pinnedChanges = mutableListOf<Pair<String, Boolean>>()
        val exportedGroups = mutableListOf<String>()
        var groupImportCount = 0
        var backupExportCount = 0
        var backupImportCount = 0
        val generatedCompressions = mutableListOf<Pair<String, CompressionSettings>>()
        val appliedCompressions = mutableListOf<Triple<String, String, Map<String, String>>>()
        val cancelledCompressions = mutableListOf<String>()
        val clearedCompressions = mutableListOf<String>()
        private val compressionPlans = mutableMapOf<String, List<CompressionPlan>>()
        override fun saveCharacter(character: Character) {
            savedCharacters += character
            store.update { it.copy(characters = it.characters.filterNot { c -> c.id == character.id } + character) }
        }
        override fun setCharacterVoice(characterId: String, voice: String): Boolean {
            if (store.snapshot().characters.none { it.id == characterId }) return false
            store.update { s -> s.copy(characters = s.characters.map { if (it.id == characterId) it.copy(voice = voice.trim()) else it }) }
            return true
        }
        override fun deleteCharacter(id: String) = Unit
        override fun saveWorld(world: World) = Unit
        override fun setDirectorInstruction(roomId: String, text: String, continueChat: Boolean) {
            store.update { state -> state.copy(rooms = state.rooms.map { if (it.id == roomId) it.copy(directorInstruction = text.trim()) else it }) }
        }
        override fun saveRoom(room: ChatRoom) {
            savedRooms += room
            store.update { state -> state.copy(rooms = if (state.rooms.any { it.id == room.id }) state.rooms.map { existing -> if (existing.id == room.id) room else existing } else state.rooms + room) }
        }
        override fun deleteRoom(id: String) {
            deletedRooms += id
            store.update { it.copy(rooms = it.rooms.filterNot { room -> room.id == id },
                messages = it.messages.filterNot { message -> message.roomId == id }) }
        }
        override fun clearHistory(roomId: String) {
            clearedRooms += roomId
            store.update { it.copy(messages = it.messages.filterNot { message -> message.roomId == roomId }) }
        }
        override fun generateCompression(roomId: String, settings: CompressionSettings) {
            generatedCompressions += roomId to settings
            val plans = buildCompressionPlans(store.snapshot(), roomId, settings)
            compressionPlans[roomId] = plans
            store.update { it.copy(rooms = it.rooms.map { room -> if (room.id == roomId) room.copy(compression = settings) else room }) }
            runtime.value = runtime.value.copy(compressions = runtime.value.compressions + (roomId to
                CompressionUiState(roomId = roomId, settings = settings, status = CompressionStatus.GENERATING, total = plans.size)))
        }
        override fun applyCompression(roomId: String, previewId: String, texts: Map<String, String>) {
            val preview = runtime.value.compressions[roomId]?.takeIf { it.id == previewId && it.status == CompressionStatus.READY } ?: return
            appliedCompressions += Triple(roomId, previewId, texts.toMap())
            store.update { applyCompressionDrafts(it, preview.settings, compressionPlans.getValue(roomId), texts) }
            compressionPlans.remove(roomId)
            runtime.value = runtime.value.copy(compressions = runtime.value.compressions - roomId)
        }
        override fun cancelCompression(roomId: String) {
            cancelledCompressions += roomId
            compressionPlans.remove(roomId)
            runtime.value = runtime.value.copy(compressions = runtime.value.compressions - roomId)
        }
        override fun clearCompression(roomId: String) {
            clearedCompressions += roomId
            compressionPlans.remove(roomId)
            runtime.value = runtime.value.copy(compressions = runtime.value.compressions - roomId)
            store.update { it.copy(contextSummaries = it.contextSummaries.filterNot { summary -> summary.roomId == roomId }) }
        }
        override fun setRoomPinned(roomId: String, pinned: Boolean) {
            pinnedChanges += roomId to pinned
            store.update { it.copy(rooms = it.rooms.map { room -> if (room.id == roomId) room.copy(pinned = pinned) else room }) }
        }
        override fun exportGroup(roomId: String) { exportedGroups += roomId }
        override fun importGroup() { groupImportCount++ }
        override fun saveProvider(provider: Provider, newApiKey: String?) { savedProviders += provider to newApiKey }
        override fun refreshModels(providerId: String) { modelListRefreshes += providerId }
        override fun send(roomId: String, text: String, mentions: Set<String>) {
            sent += Sent(roomId, text, mentions)
            store.update { state ->
                val sender = state.rooms.find { it.id == roomId }?.userName?.trim()?.ifBlank { "我" } ?: "我"
                state.copy(messages = state.messages + ChatMessage(roomId = roomId, senderName = sender, text = text, mentions = mentions.toList()))
            }
        }
        override fun stop(roomId: String) = Unit
        override fun switchToB(messageId: String) = Unit
        override fun retry(messageId: String) { retried += messageId }
        override fun regenerate(messageId: String) { regenerated += messageId }
        override fun editMessage(messageId: String, text: String) {
            editedMessages += messageId to text
            saveEditedMessage(messageId, text)
        }
        override fun editAndSendMessage(messageId: String, text: String) {
            editedAndSentMessages += messageId to text
            store.update { rewindConversation(it, messageId) }
            saveEditedMessage(messageId, text)
        }
        private fun saveEditedMessage(messageId: String, text: String) {
            store.update { it.copy(messages = it.messages.map { message ->
                if (message.id != messageId) message
                else if (message.characterId == null) message.copy(text = text, speechPreparation = null)
                else message.copy(text = text, segments = emptyList(), error = "", status = MessageStatus.COMPLETE, speechPreparation = null)
            }) }
        }
        override fun speak(messageId: String) { spoken += messageId }
        override fun previewVoice(character: Character) { voicePreviews += character }
        override fun stopAudio() { audioStopCount++ }
        override fun refreshDoubaoVoices() { voiceListRefreshes++ }
        override fun saveTts(settings: TtsSettings, apiKey: String?, accessToken: String?, voiceAccessKey: String?, voiceSecretKey: String?) {
            voiceListCredentials += voiceAccessKey to voiceSecretKey
            store.update { it.copy(tts = settings) }
        }
        override fun exportBackup() { backupExportCount++ }
        override fun importBackup() { backupImportCount++ }
    }
}
