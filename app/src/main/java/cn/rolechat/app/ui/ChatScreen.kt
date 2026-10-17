package cn.rolechat.app.ui

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.rolechat.app.AppActions
import cn.rolechat.app.RuntimeUi
import cn.rolechat.app.speech.DoubaoVoice
import cn.rolechat.app.speech.DoubaoVoices
import cn.rolechat.app.speech.speechVoiceSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import cn.rolechat.core.*
import cn.rolechat.core.Character

private val ChatBackground = Color(0xFF111111)
private val ChatToolbar = Color(0xFF191919)
private val ChatText = Color(0xFFE6E6E6)
private val ChatSecondary = Color(0xFF989898)
private val ChatDivider = Color(0xFF292929)
private val ChatGreen = Color(0xFF3EB575)
private val MyBubble = Color(0xFF3EB575)
private val OtherBubble = Color(0xFF2A2A2A)
private val ChatAvatarSize = 40.dp
private val AvatarBubbleGap = 9.dp
private val BubbleTop = HorizontalAlignmentLine { a, b -> minOf(a, b) }

/** The compact sender label stays above the aligned avatar and bubble. */
private fun Modifier.bubbleTop() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height, mapOf(BubbleTop to 0)) { placeable.placeRelative(0, 0) }
}

private fun Modifier.bubbleTail(self: Boolean, color: Color) = drawBehind {
    val edge = if (self) size.width else 0f
    val tip = if (self) edge + 5.dp.toPx() else -5.dp.toPx()
    val y = 15.dp.toPx()
    drawPath(Path().apply {
        moveTo(edge, y - 5.dp.toPx()); lineTo(tip, y); lineTo(edge, y + 5.dp.toPx()); close()
    }, color)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun ChatScreen(room: ChatRoom, state: AppState, runtime: RuntimeUi, actions: AppActions,
    onBack: () -> Unit, onEdit: (RoomSection) -> Unit, onSettings: () -> Unit,
    onEditCharacter: (String) -> Unit = {}, onCompression: () -> Unit = {}) {
    var draft by rememberSaveable(room.id) { mutableStateOf("") }
    var mentions by rememberSaveable(room.id, stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<String>()) }
    var showMentions by rememberSaveable(room.id) { mutableStateOf(false) }
    var returnToInput by remember(room.id) { mutableStateOf(false) }
    var showRoomMenu by rememberSaveable(room.id) { mutableStateOf(false) }
    var showDirector by rememberSaveable(room.id) { mutableStateOf(false) }
    var detailId by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var actionMessageId by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var reasoningMessageId by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var preferSpeechTab by rememberSaveable(room.id) { mutableStateOf(false) }
    var voicePickerMessageId by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var voicePickerPreviewed by remember { mutableStateOf(false) }
    var editingMessageId by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var routingMessageId by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var showRouting by rememberSaveable(room.id) { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (voicePickerPreviewed) actions.stopAudio() } }
    val members = room.members.mapNotNull { member -> state.characters.find { it.id == member.characterId } }
    val messages = state.messages.filter { it.roomId == room.id }
    val busy = room.id in runtime.busyRooms
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember(room.id) { FocusRequester() }
    LaunchedEffect(returnToInput, showMentions) {
        if (returnToInput && !showMentions) {
            withFrameNanos { }
            inputFocus.requestFocus()
            keyboard?.show()
            returnToInput = false
        }
    }
    var followLatest by rememberSaveable(room.id) { mutableStateOf(true) }
    val viewportHeight by remember { derivedStateOf { listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset } }
    // No empty introduction item above an existing conversation.
    val tailIndex = messages.size + if (messages.isEmpty() || members.isEmpty()) 1 else 0
    val scrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y > 0f) followLatest = false
                if (source == NestedScrollSource.UserInput && consumed.y < 0f && !listState.canScrollForward) followLatest = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, listState.layoutInfo.totalItemsCount) }.collect { (scrolling, forward, itemCount) ->
            if (itemCount > 0 && !scrolling && !forward) followLatest = true
        }
    }
    LaunchedEffect(messages, followLatest, viewportHeight) {
        if (messages.isNotEmpty() && followLatest) listState.scrollToItem(tailIndex)
    }
    val audioActive = runtime.audioStatus.split(" · ").any { it.trim() in setOf(
        "正在朗读", "语音播放中", "语音连接中", "硅基流动语音连接中", "正在连接语音服务", "正在提取对白", "正在准备音色试听"
    ) }
    var showAudioStatus by remember(runtime.audioStatus) { mutableStateOf(runtime.audioStatus.isNotBlank()) }
    LaunchedEffect(runtime.audioStatus) {
        if (!audioActive) { delay(4_000); showAudioStatus = false }
    }
    fun openRoomMenu() { focus.clearFocus(); keyboard?.hide(); showRoomMenu = true }
    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty()) { followLatest = true; actions.send(room.id, text, mentions); draft = ""; mentions = emptySet(); showMentions = false }
    }
    Scaffold(modifier = Modifier.fillMaxSize().imePadding(), containerColor = ChatBackground,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal), topBar = {
        Column(Modifier.background(ChatToolbar).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).testTag("chat-toolbar").padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = ChatText) }
                Row(Modifier.weight(1f).fillMaxHeight().testTag("chat-room-title")
                    .combinedClickable(onClick = ::openRoomMenu, onLongClick = ::openRoomMenu).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(room.name, Modifier.weight(1f, fill = false), color = ChatText, fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!room.direct) Text(" (${members.size})", color = ChatSecondary, fontSize = 14.sp, maxLines = 1)
                }
                IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); showDirector = true }) {
                    Icon(Icons.Outlined.Movie, "剧情指令", tint = if (room.directorInstruction.isBlank()) ChatSecondary else ChatGreen)
                }
                IconButton(onClick = ::openRoomMenu) { Icon(Icons.Outlined.MoreHoriz, "会话设置", tint = ChatText) }
            }
            HorizontalDivider(color = ChatDivider, thickness = .5.dp)
        }
    }, bottomBar = {
        Surface(color = ChatToolbar) {
            Column(Modifier.navigationBarsPadding()) {
                Column(Modifier.testTag("chat-composer")) {
                    HorizontalDivider(color = ChatDivider, thickness = .5.dp)
                    val hasMentions = mentions.isNotEmpty() && !room.direct
                    if (showAudioStatus || hasMentions) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showAudioStatus) {
                            Icon(Icons.Outlined.GraphicEq, null, Modifier.size(14.dp), tint = ChatGreen)
                            Text(runtime.audioStatus, Modifier.weight(1f).padding(start = 6.dp), color = ChatSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (hasMentions) {
                            Text("已选 ${mentions.size} 位角色", (if (showAudioStatus) Modifier else Modifier.weight(1f))
                                .clickable { focus.clearFocus(); keyboard?.hide(); showMentions = true }.padding(horizontal = 6.dp, vertical = 6.dp),
                                color = ChatGreen, fontSize = 11.sp, maxLines = 1)
                        }
                        if (showAudioStatus && audioActive) IconButton(onClick = { actions.stopAudio(); showAudioStatus = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.StopCircle, "停止语音", Modifier.size(18.dp), tint = ChatGreen)
                        } else if (showAudioStatus) IconButton(onClick = { showAudioStatus = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, "收起语音状态", Modifier.size(14.dp), tint = ChatSecondary)
                        } else IconButton(onClick = { mentions = emptySet() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, "清除提醒角色", Modifier.size(14.dp), tint = ChatSecondary)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = if (room.direct) 12.dp else 4.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (!room.direct) IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); showMentions = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Outlined.AlternateEmail, "选择要提醒的角色", tint = if (mentions.isEmpty()) ChatText else ChatGreen, modifier = Modifier.size(23.dp))
                        }
                        BasicTextField(draft, { draft = it }, modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("chat-input").focusRequester(inputFocus)
                            .background(OtherBubble, RoundedCornerShape(6.dp)).padding(horizontal = 11.dp, vertical = 10.dp),
                            enabled = members.isNotEmpty(), minLines = 1, maxLines = 3,
                            textStyle = TextStyle(color = ChatText, fontSize = 16.sp, lineHeight = 22.sp), cursorBrush = SolidColor(ChatGreen),
                            decorationBox = { inner -> Box {
                                if (draft.isEmpty()) Text(if (members.isEmpty()) "没有可回复的角色" else "发消息…", color = Color(0xFFAAAAAA), fontSize = 15.sp, lineHeight = 22.sp)
                                inner()
                            } })
                        val stopInstead = busy && draft.isBlank()
                        Button(onClick = { if (stopInstead) actions.stop(room.id) else send() }, enabled = stopInstead || (draft.isNotBlank() && members.isNotEmpty()),
                            modifier = Modifier.height(42.dp).width(58.dp).semantics { contentDescription = if (stopInstead) "停止本轮" else "发送" },
                            shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ChatGreen, contentColor = Color(0xFF082515), disabledContainerColor = OtherBubble, disabledContentColor = Color(0xFF686868))) {
                            Text(if (stopInstead) "停止" else "发送", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("chat-messages").nestedScroll(scrollConnection), state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (messages.isEmpty()) item(key = "intro") {
                    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { members.take(4).forEach { character ->
                                ChatAvatar(character, character.name, Modifier.clickable { onEditCharacter(character.id) }
                                    .semantics { contentDescription = "编辑角色${character.name}" })
                            } }
                        Text(if (members.isEmpty()) "先邀请一位角色" else "从一句问候开始", color = ChatSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                        if (runtime.configuredProviders.isEmpty()) TextButton(onSettings) { Text("配置模型服务", color = ChatGreen, fontSize = 13.sp) }
                        else if (members.isEmpty()) TextButton(onClick = { onEdit(RoomSection.MEMBERS) }) { Text("选择群成员", color = ChatGreen, fontSize = 13.sp) }
                    }
                } else if (members.isEmpty()) item(key = "removed-members") {
                    Text("角色已移除，历史消息仍可查看", color = ChatSecondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().wrapContentWidth().padding(vertical = 5.dp))
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val character = state.characters.find { it.id == message.characterId }
                    Column(Modifier.testTag("message-${message.id}")) {
                        if (index == 0 || message.createdAt - messages[index - 1].createdAt >= 5 * 60_000L) {
                            Text(formatTime(message.createdAt), color = Color(0xFF777777), fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth().wrapContentWidth().padding(top = 2.dp, bottom = 8.dp))
                        }
                        if (message.isDirector) {
                            var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                                .testTag("director-event-${message.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("System · 剧情指令记录", color = ChatGreen, fontSize = 11.sp)
                                SelectionContainer {
                                    Text(message.text, color = ChatSecondary, fontSize = 13.sp, lineHeight = 20.sp,
                                        textAlign = TextAlign.Center, maxLines = if (expanded) Int.MAX_VALUE else 3,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                                Row {
                                    if (message.text.length > 60 || message.text.count { it == '\n' } >= 3)
                                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起记录" else "展开记录", fontSize = 11.sp, color = ChatSecondary) }
                                    TextButton(onClick = { showDirector = true }) { Text("查看当前指令", fontSize = 11.sp, color = ChatGreen) }
                                }
                            }
                        } else if (message.messageStyle == MessageStyle.SCENE && message.characterId != null) {
                            SceneMessage(message, character, busy, onDetails = { detailId = message.id },
                                onMenu = { focus.clearFocus(); keyboard?.hide(); actionMessageId = message.id },
                                onSwitch = { actions.switchToB(message.id) }, onRetry = { actions.retry(message.id) },
                                onEditCharacter = { character?.let { onEditCharacter(it.id) } })
                        } else {
                            MessageBubble(message, character, busy, !room.direct, message.mentions.mapNotNull { id -> state.characters.find { it.id == id }?.name },
                                onDetails = { detailId = message.id }, onMenu = { focus.clearFocus(); keyboard?.hide(); actionMessageId = message.id },
                                onSwitch = { actions.switchToB(message.id) }, onRetry = { actions.retry(message.id) },
                                userAvatarImage = room.userAvatarImage,
                                onEditIdentity = {
                                    if (message.characterId == null) onEdit(RoomSection.IDENTITY)
                                    else character?.let { onEditCharacter(it.id) }
                                })
                        }
                    }
                }
                item(key = "conversation-tail") { Spacer(Modifier.height(1.dp)) }
            }
            if (!followLatest && listState.canScrollForward) FilledTonalButton(onClick = { followLatest = true; scope.launch { listState.scrollToItem(tailIndex) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).height(36.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Panel, contentColor = ChatGreen)) {
                Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("回到最新", fontSize = 12.sp)
            }
        }
    }
    if (showDirector) DirectorInstructionDialog(room, members.isNotEmpty(), busy,
        onDismiss = { showDirector = false }, onApply = { text, continueChat ->
            actions.setDirectorInstruction(room.id, text, continueChat)
            followLatest = true
            showDirector = false
        })
    if (showRoomMenu) RoomActionSheet(room, onDismiss = { showRoomMenu = false },
        onTogglePinned = { actions.setRoomPinned(room.id, !room.pinned) },
        onDelete = { actions.deleteRoom(room.id); onBack() }, onExport = { actions.exportGroup(room.id) },
        onCompression = onCompression, onClearHistory = {
            detailId = null; actionMessageId = null; reasoningMessageId = null; editingMessageId = null; routingMessageId = null; showRouting = false; followLatest = true
            actions.clearHistory(room.id)
        }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            members.forEach { character ->
                Column(Modifier.width(56.dp).testTag("room-member-avatar-${character.id}")
                    .clickable { showRoomMenu = false; onEditCharacter(character.id) }
                    .semantics { contentDescription = "编辑角色${character.name}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    ChatAvatar(character, character.name)
                    Text(character.name, color = ChatSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        ChatMenuRow("我的身份", room.userName.trim().ifBlank { "我" }, Icons.Outlined.PersonOutline) { showRoomMenu = false; onEdit(RoomSection.IDENTITY) }
        ChatMenuRow("剧情指令", if (room.directorInstruction.isBlank()) "System · 尚未设置" else "System · 当前生效中", Icons.Outlined.Movie) { showRoomMenu = false; showDirector = true }
        ChatMenuRow("群成员", if (room.direct) "聊天角色" else "${members.size} 位角色", Icons.Outlined.PeopleOutline) { showRoomMenu = false; onEdit(RoomSection.MEMBERS) }
        ChatMenuRow("回复方式", replyModeLabel(room.mode), Icons.Outlined.Forum) { showRoomMenu = false; onEdit(RoomSection.REPLY) }
        val latestRoutingMessage = messages.lastOrNull { it.routingDecision != null }
        if (!room.direct && (room.mode == ReplyMode.SMART || latestRoutingMessage != null)) {
            ChatMenuRow("接话记录", latestRoutingMessage?.routingDecision?.let(::routingStatusLabel) ?: "尚无记录", Icons.Outlined.AccountTree) {
                showRoomMenu = false; routingMessageId = latestRoutingMessage?.id; showRouting = true
            }
        }
        ChatMenuRow("更多设置", "世界观、自动朗读等", Icons.Outlined.Tune) { showRoomMenu = false; onEdit(RoomSection.BASIC) }
    }
    if (showMentions) ModalBottomSheet(onDismissRequest = { showMentions = false }, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("提醒角色回复", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = ChatText, modifier = Modifier.weight(1f))
                TextButton(onClick = { showMentions = false; returnToInput = true }) { Text("完成", color = ChatGreen) }
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(members, key = { _, member -> member.id }) { _, character ->
                    Row(Modifier.fillMaxWidth().clickable { mentions = if (character.id in mentions) mentions - character.id else mentions + character.id }.semantics { selected = character.id in mentions }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(character, character.name)
                        Text("@${character.name}", color = ChatText, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Checkbox(checked = character.id in mentions, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = ChatGreen))
                    }
                }
            }
        }
    }
    messages.find { it.id == actionMessageId }?.let { message ->
        val character = state.characters.find { it.id == message.characterId }
        MessageActionSheet(message, canSpeak = character != null && message.text.isNotBlank() && !message.isGenerating(),
            canRegenerate = character != null && room.members.any { it.characterId == character.id },
            roomBusy = busy,
            onDismiss = { actionMessageId = null }, onSpeak = { actionMessageId = null; actions.speak(message.id) },
            onEdit = { actionMessageId = null; editingMessageId = message.id },
            onRegenerate = { actionMessageId = null; actions.regenerate(message.id) },
            onReasoning = { actionMessageId = null; preferSpeechTab = false; reasoningMessageId = message.id },
            onRouting = { actionMessageId = null; routingMessageId = message.id; showRouting = true },
            onDetails = { actionMessageId = null; detailId = message.id })
    }
    if (showRouting) RoutingDecisionSheet(if (routingMessageId == null) messages.lastOrNull { it.routingDecision != null }
        else messages.find { it.id == routingMessageId }) { showRouting = false }
    messages.find { it.id == detailId }?.let { MessageDetails(it) { detailId = null } }
    messages.find { it.id == reasoningMessageId && it.characterId != null }?.let { message ->
        val unavailableReason = when {
            !state.tts.enabled -> "请先在语音服务中启用语音。"
            !state.tts.dialogueExtraction.enabled -> "请先在语音服务中开启「提取对白后朗读」。"
            state.characters.none { it.id == message.characterId } -> "角色已删除，无法重新朗读。"
            message.speechPreparation?.status == SpeechPreparationStatus.EXTRACTING -> "正在进行语音前置处理，请等待完成。"
            message.status !in setOf(MessageStatus.COMPLETE, MessageStatus.ERROR, MessageStatus.INTERRUPTED) -> "回复完成后可提取并朗读。"
            message.text.isBlank() -> "消息没有正文，无法提取并朗读。"
            else -> null
        }
        val currentCharacter = state.characters.find { it.id == message.characterId }
        MessageReasoningSheet(message, unavailableReason, preferSpeechTab,
            currentVoice = currentCharacter?.let { speechVoiceSnapshot(state.tts, it) }, voices = runtime.doubaoVoices,
            onChooseVoice = if (currentCharacter != null && state.tts.engine == TtsEngine.DOUBAO) ({
                reasoningMessageId = null; voicePickerMessageId = message.id
            }) else null,
            onPrepareSpeech = { actions.speak(message.id) }, onDismiss = { reasoningMessageId = null })
    }
    messages.find { it.id == voicePickerMessageId }?.let { message ->
        state.characters.find { it.id == message.characterId }?.let { character ->
            val finishSelection = {
                if (voicePickerPreviewed) actions.stopAudio()
                voicePickerPreviewed = false
                voicePickerMessageId = null
                preferSpeechTab = true
                reasoningMessageId = message.id
            }
            DoubaoVoicePicker(state.tts.resourceId, character, runtime, voicePickerPreviewed,
                onDismiss = finishSelection, onRefresh = actions::refreshDoubaoVoices,
                onPreview = { voice -> voicePickerPreviewed = true; actions.previewVoice(character.copy(voice = voice.id)) },
                onStopPreview = { actions.stopAudio(); voicePickerPreviewed = false },
                selectionLabel = "使用并保存", selectionDescription = "只修改这条消息所属角色「${character.name}」的音色。试听不会保存。",
                onSelect = { voice -> if (actions.setCharacterVoice(character.id, voice.id)) finishSelection() })
        }
    }
    messages.find { it.id == editingMessageId }?.let { message ->
        EditMessageDialog(message, stopRound = busy || message.isGenerating(), hasOtherResponders = members.any { it.id != message.characterId },
            smartReply = room.mode == ReplyMode.SMART && !room.direct,
            laterCount = messages.size - messages.indexOfFirst { it.id == message.id } - 1,
            onDismiss = { editingMessageId = null }, onSave = { text ->
            editingMessageId = null
            actions.editMessage(message.id, text)
        }, onSaveAndSend = { text ->
            editingMessageId = null
            followLatest = true
            actions.editAndSendMessage(message.id, text)
        })
    }
}

@Composable private fun ChatMenuRow(label: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = label }.heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = ChatSecondary)
        Text(label, Modifier.padding(start = 12.dp), fontSize = 15.sp, color = ChatText)
        Spacer(Modifier.width(12.dp))
        Text(value, Modifier.weight(1f), fontSize = 12.sp, color = ChatSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 8.dp).size(18.dp), tint = ChatSecondary)
    }
}

@Composable private fun ChatAvatar(character: Character?, fallback: String, modifier: Modifier = Modifier, image: String = character?.avatarImage.orEmpty()) {
    val text = character?.avatar?.ifBlank { fallback } ?: fallback
    val initial = text.ifBlank { "我" }.let { it.substring(0, it.offsetByCodePoints(0, 1)) }
    Box(modifier.size(ChatAvatarSize).clip(RoundedCornerShape(6.dp)).background(character?.let { Color(it.color) } ?: Color(0xFFB8CFE4)), contentAlignment = Alignment.Center) {
        AvatarPhoto(image, Modifier.fillMaxSize(), contentDescription = "头像照片") {
            Text(if (character?.avatar?.isNotBlank() == true) character.avatar else initial, color = Color(0xFF172019), fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun ChatMessage.isGenerating() = status in setOf(MessageStatus.QUEUED, MessageStatus.GENERATING_A,
    MessageStatus.THINKING_A, MessageStatus.HANDOFF, MessageStatus.GENERATING_B, MessageStatus.THINKING_B)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun MessageBubble(message: ChatMessage, character: Character?, roomBusy: Boolean, showGroupNames: Boolean,
    mentionedNames: List<String>, onDetails: () -> Unit, onMenu: () -> Unit, onSwitch: () -> Unit, onRetry: () -> Unit,
    userAvatarImage: String, onEditIdentity: () -> Unit) {
    val self = message.characterId == null
    val senderName = message.senderName.ifBlank { "我" }
    val senderInitial = senderName.substring(0, senderName.offsetByCodePoints(0, 1))
    val bubbleColor = if (self) MyBubble else OtherBubble
    val textColor = if (self) Color(0xFF082515) else ChatText
    // Keep the opposite avatar column empty even when a message fills its available width.
    val oppositeAvatarSpace = ChatAvatarSize + AvatarBubbleGap
    Row(Modifier.fillMaxWidth().padding(start = if (self) oppositeAvatarSpace else 0.dp,
        end = if (self) 0.dp else oppositeAvatarSpace),
        horizontalArrangement = if (self) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!self) {
            ChatAvatar(character, senderInitial, Modifier.alignBy { 0 }.testTag("message-avatar-${message.id}")
                .then(if (character != null) Modifier.clickable(onClick = onEditIdentity)
                    .semantics { contentDescription = "编辑角色${character.name}" } else Modifier))
            Spacer(Modifier.width(AvatarBubbleGap))
        }
        Column(Modifier.widthIn(max = 300.dp).weight(1f, fill = false).alignBy(BubbleTop), horizontalAlignment = if (self) Alignment.End else Alignment.Start) {
            if (!self && showGroupNames) Text(senderName, color = ChatSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp).height(16.dp).testTag("message-sender-${message.id}"))
            Column(Modifier.testTag("chat-bubble-${message.id}").bubbleTop().bubbleTail(self, bubbleColor)
                .background(bubbleColor, RoundedCornerShape(5.dp))
                .combinedClickable(onClick = onDetails, onLongClick = onMenu)
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (mentionedNames.isNotEmpty()) Text(mentionedNames.joinToString(" ") { "@$it" }, color = if (self) Color(0xFF174A2F) else ChatGreen,
                    fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
                Text(message.text.ifEmpty { if (message.isGenerating()) "···" else "没有生成正文" },
                    fontSize = if (message.text.isEmpty()) 14.sp else 16.sp, lineHeight = 22.sp,
                    color = if (message.text.isEmpty() && !self) ChatSecondary else textColor,
                    modifier = Modifier.testTag("message-text-${message.id}"))
            }
            MessageStateActions(message, character, roomBusy, onSwitch, onRetry, onDetails)
        }
        if (self) { Spacer(Modifier.width(AvatarBubbleGap)); ChatAvatar(null, senderInitial, Modifier.alignBy { 0 }.testTag("message-avatar-${message.id}")
            .clickable(onClick = onEditIdentity).semantics { contentDescription = "编辑我的身份" }, image = userAvatarImage) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun SceneMessage(message: ChatMessage, character: Character?, roomBusy: Boolean,
    onDetails: () -> Unit, onMenu: () -> Unit, onSwitch: () -> Unit, onRetry: () -> Unit,
    onEditCharacter: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("scene-message-${message.id}")
            .combinedClickable(onClick = onDetails, onLongClick = onMenu).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("场景 · ${message.senderName}", fontSize = 10.sp, lineHeight = 16.sp, color = Color(0xFF7F8882),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp).testTag("message-sender-${message.id}")
                    .then(if (character != null) Modifier.combinedClickable(onClick = onEditCharacter, onLongClick = onMenu)
                        .semantics { contentDescription = "编辑角色${character.name}" } else Modifier))
            Text(message.text.ifEmpty { if (message.isGenerating()) "场景正在展开…" else "没有生成正文" },
                fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFFB7BDB9), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("message-text-${message.id}")
                    .combinedClickable(onClick = onDetails, onLongClick = onMenu))
            MessageStateActions(message, character, roomBusy, onSwitch, onRetry, onDetails, centered = true)
        }
    }
}

@Composable private fun MessageStateActions(message: ChatMessage, character: Character?, roomBusy: Boolean,
    onSwitch: () -> Unit, onRetry: () -> Unit, onDetails: () -> Unit, centered: Boolean = false) {
    val active = message.isGenerating()
    val canSwitch = character?.dualModel == true && message.status in setOf(MessageStatus.GENERATING_A, MessageStatus.THINKING_A)
    if (message.error.isNotBlank()) Text(message.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, lineHeight = 17.sp,
        maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.padding(top = 5.dp))
    if (message.characterId != null && (active || message.status != MessageStatus.COMPLETE || message.segments.size > 1)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            modifier = Modifier.padding(top = 3.dp)) {
            if (active) {
                CircularProgressIndicator(Modifier.size(8.dp), color = ChatGreen, strokeWidth = 1.2.dp)
                val cut = message.segments.any { it.label == "A" && it.status == SegmentStatus.CUT }
                Text((if (cut) "A 已中断 · " else "") + statusLabel(message.status), color = ChatSecondary, fontSize = 10.sp,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 5.dp, end = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (message.status != MessageStatus.COMPLETE) {
                Text(statusLabel(message.status), color = ChatSecondary, fontSize = 10.sp, modifier = Modifier.padding(end = 3.dp))
            }
            if (canSwitch) TextButton(onSwitch, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.height(30.dp)) { Text("切换 B", color = ChatGreen, fontSize = 11.sp) }
            if (!active && !roomBusy && message.status in setOf(MessageStatus.ERROR, MessageStatus.INTERRUPTED)) IconButton(onRetry, Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Refresh, "重试回复", Modifier.size(16.dp), tint = ChatGreen)
            }
            if (message.segments.size > 1) TextButton(onDetails, contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.height(28.dp)) {
                Text(if (message.segments.any { it.label == "A" && it.status == SegmentStatus.CUT }) "A 中断 → B" else "A → B", fontSize = 10.sp, color = ChatSecondary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MessageActionSheet(message: ChatMessage, canSpeak: Boolean, canRegenerate: Boolean, roomBusy: Boolean,
    onDismiss: () -> Unit, onSpeak: () -> Unit, onEdit: () -> Unit, onRegenerate: () -> Unit, onReasoning: () -> Unit,
    onRouting: () -> Unit, onDetails: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().testTag("message-actions-sheet").verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Text(message.senderName, color = ChatSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (canSpeak) ChatMenuRow("朗读消息", "", Icons.Outlined.VolumeUp, onSpeak)
            if (message.text.isNotBlank()) ChatMenuRow("复制文字", "", Icons.Outlined.ContentCopy) {
                clipboard.setText(AnnotatedString(message.text)); onDismiss()
            }
            ChatMenuRow("编辑消息", "", Icons.Outlined.Edit, onEdit)
            if (message.characterId != null) {
                if (canRegenerate) Row(Modifier.fillMaxWidth().clickable(onClick = onRegenerate)
                    .semantics { contentDescription = "重新生成" }.heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(22.dp), tint = ChatSecondary)
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                        Text("重新生成", color = ChatText, fontSize = 15.sp)
                        Text(if (roomBusy || message.isGenerating()) "停止本轮并重新生成这条回复" else "从头重新生成这条回复",
                            color = ChatSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = ChatSecondary)
                }
                ChatMenuRow("思考内容（COT）", "", Icons.Outlined.Psychology, onReasoning)
            }
            if (message.routingDecision != null) ChatMenuRow("接话安排", "", Icons.Outlined.AccountTree, onRouting)
            ChatMenuRow("回复详情", "", Icons.Outlined.Info, onDetails)
        }
    }
}

@Composable private fun EditMessageDialog(message: ChatMessage, stopRound: Boolean, hasOtherResponders: Boolean, onDismiss: () -> Unit,
    onSave: (String) -> Unit, onSaveAndSend: (String) -> Unit, smartReply: Boolean = false, laterCount: Int = 0) {
    var text by rememberSaveable(message.id) { mutableStateOf(message.text) }
    val inputFocus = remember(message.id) { FocusRequester() }
    var focusRequested by remember(message.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
    val bottomInset = with(density) {
        maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density)).toDp()
    }
    fun finish(action: () -> Unit) { focus.clearFocus(force = true); keyboard?.hide(); action() }
    Dialog(onDismissRequest = { finish(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(dialogWindow) {
            // Compose handles IME insets below. Do not also let the platform pan this window.
            // TOP anchors the Compose 1.7 dialog's measured window to the same origin as its insets.
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            dialogWindow.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            onDispose { }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(bottom = bottomInset + if (keyboardVisible) 32.dp else 0.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 560.dp).testTag("edit-message-dialog"),
                color = Panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("编辑消息", color = ChatText, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    // Only the body shrinks and scrolls when the keyboard opens; all actions stay visible.
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().focusRequester(inputFocus)
                            .onGloballyPositioned {
                                if (!focusRequested) {
                                    focusRequested = true
                                    inputFocus.requestFocus()
                                    keyboard?.show()
                                }
                            }.testTag("edit-message-input"),
                            label = { Text("消息内容") }, minLines = 2, maxLines = 8, shape = RoundedCornerShape(8.dp))
                        Text(buildString {
                            if (laterCount > 0) append("保存并发送会删除后面的 $laterCount 条消息及相关上下文，从这里重新继续；只保存会保留后续消息。\n")
                            if (message.characterId == null) append("保存只更新上下文；保存并发送会以改后的消息继续当前会话。")
                            else {
                                append("保存只更新上下文，并清除原 A/B 与 COT 记录；保存并发送后，")
                                append(if (smartReply) "重新判断下一位接话者，也可能等待你回复。"
                                    else if (hasOtherResponders) "由群内其他角色接话。" else "由原角色继续回复。")
                            }
                            if (stopRound) append("\n两种保存都会停止本轮正在生成的回复。")
                        }, color = ChatSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 10.dp).testTag("edit-message-note"))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { finish(onDismiss) }, contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("cancel-message-edit")
                                .semantics { contentDescription = "取消编辑消息" }) { Text("取消", maxLines = 1) }
                        OutlinedButton(onClick = { finish { onSave(text) } }, enabled = text.isNotBlank(), shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("save-message-edit")
                                .semantics { contentDescription = "保存编辑消息" }) { Text("保存", maxLines = 1) }
                        Button(onClick = { finish { onSaveAndSend(text) } }, enabled = text.isNotBlank(), shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.weight(1.7f).heightIn(min = 48.dp).testTag("save-and-send-message-edit")
                                .semantics { contentDescription = "保存并发送编辑消息" }) { Text("保存并发送", maxLines = 1) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MessageReasoningSheet(message: ChatMessage, prepareSpeechUnavailableReason: String?, startWithSpeech: Boolean,
    currentVoice: SpeechVoiceSnapshot?, voices: List<DoubaoVoice>, onChooseVoice: (() -> Unit)?,
    onPrepareSpeech: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val chatScroll = rememberScrollState()
    val speechScroll = rememberScrollState()
    val copyText = message.segments.filter { it.outputSource != ReplyOutputSource.REASONING && it.reasoning.isNotBlank() }
        .joinToString("\n\n") { "模型 ${it.label} · ${it.model}\n${it.reasoning}" }
    var section by rememberSaveable(message.id) { mutableIntStateOf(if (startWithSpeech || (copyText.isBlank() && message.speechPreparation != null)) 1 else 0) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).testTag("reasoning-sheet").padding(horizontal = 20.dp)) {
            Text("思考内容（COT）", color = ChatText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("${message.senderName} · ${statusLabel(message.status)}", color = ChatSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
            TabRow(selectedTabIndex = section, containerColor = Panel, contentColor = ChatGreen, divider = { HorizontalDivider(color = ChatDivider) }) {
                Tab(selected = section == 0, onClick = { section = 0 }, modifier = Modifier.testTag("cot-chat-tab"), text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("聊天模型", fontSize = 14.sp)
                        Text(if (copyText.isNotBlank()) "已有思考内容" else "暂无思考内容", color = ChatSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                })
                Tab(selected = section == 1, onClick = { section = 1 }, modifier = Modifier.testTag("cot-speech-tab"), text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("语音前置处理", fontSize = 14.sp)
                        Text(message.speechPreparation?.let { speechPreparationStatus(it) } ?: "暂无记录", color = ChatSecondary, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                })
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("reasoning-scroll").verticalScroll(if (section == 0) chatScroll else speechScroll).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (section == 1) {
                    val record = message.speechPreparation
                    if (currentVoice != null) {
                        Column(Modifier.fillMaxWidth().testTag("current-speech-voice")) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("当前已保存的朗读音色", color = ChatSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (onChooseVoice != null) TextButton(onClick = onChooseVoice, modifier = Modifier.testTag("change-speech-voice")) { Text("更换音色", fontSize = 12.sp) }
                            }
                            Text(voiceDescription(currentVoice, voices), color = ChatText, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                    if (record != null) {
                        if (record.voice != null && currentVoice != null && record.voice != currentVoice)
                            Text("当前音色或资源已更改；下方是上次处理记录。点击「提取并朗读」会使用当前配置。", color = ChatGreen, fontSize = 12.sp,
                                lineHeight = 19.sp, modifier = Modifier.testTag("speech-voice-changed"))
                        SpeechPreparationCard(record, voices)
                    }
                    else Text("这条消息尚无语音前置处理记录", color = ChatSecondary, fontSize = 14.sp, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("speech-preparation-empty"))
                } else if (copyText.isBlank()) {
                    message.segments.filter { it.outputSource == ReplyOutputSource.REASONING }.forEach { segment ->
                        Text("模型 ${segment.label} · ${segment.model}\n此段按正文接收，不另存思考内容。", color = ChatSecondary, fontSize = 13.sp, lineHeight = 20.sp,
                            modifier = Modifier.testTag("reasoning-output-note-${segment.id}"))
                    }
                    Text(if (message.isGenerating()) "模型尚未返回思考内容。" else "模型未返回思考内容，或这条历史消息未记录。",
                        color = ChatSecondary, fontSize = 14.sp, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("reasoning-empty"))
                } else {
                    message.segments.forEach { segment ->
                        Column(Modifier.fillMaxWidth().testTag("reasoning-segment-${segment.id}")
                            .background(ChatToolbar, RoundedCornerShape(8.dp)).padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("模型 ${segment.label}", color = ChatGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(when (segment.status) {
                                    SegmentStatus.RUNNING -> "生成中"; SegmentStatus.COMPLETE -> "已完成"; SegmentStatus.CUT -> "已中断"
                                    SegmentStatus.ERROR -> "失败"; SegmentStatus.INTERRUPTED -> "已停止"
                                }, color = ChatSecondary, fontSize = 11.sp)
                            }
                            Text(segment.model.ifBlank { "未记录模型" }, color = ChatSecondary, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                            if (segment.outputSource == ReplyOutputSource.REASONING) Text("此段按正文接收，不另存思考内容。",
                                color = ChatSecondary, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("reasoning-output-note-${segment.id}"))
                            else if (segment.reasoning.isNotBlank()) SelectionContainer {
                                Text(segment.reasoning, color = ChatText, fontSize = 14.sp, lineHeight = 22.sp,
                                    modifier = Modifier.testTag("reasoning-text-${segment.id}"))
                            } else Text(if (segment.status == SegmentStatus.RUNNING) "尚未返回思考内容" else "此段未记录思考内容",
                                color = ChatSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = ChatDivider, modifier = Modifier.padding(top = 12.dp))
            if (section == 1) Text(prepareSpeechUnavailableReason ?: "会重新提取对白并再次朗读这条消息。",
                color = ChatSecondary, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 9.dp).testTag("prepare-speech-hint"))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (section == 1) TextButton(onClick = onPrepareSpeech, enabled = prepareSpeechUnavailableReason == null,
                    modifier = Modifier.testTag("prepare-speech-action").semantics { contentDescription = "提取并朗读" }) { Text("提取并朗读") }
                else TextButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }, enabled = copyText.isNotBlank(),
                    modifier = Modifier.testTag("copy-reasoning").semantics { contentDescription = "复制思考内容" }) { Text("复制思考内容") }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        }
    }
}

private fun speechPreparationStatus(record: SpeechPreparationRecord): String = if (record.status == SpeechPreparationStatus.ERROR && record.modelInvoked == false)
    "未调用模型" else when (record.status) {
    SpeechPreparationStatus.EXTRACTING -> "处理中"
    SpeechPreparationStatus.COMPLETE -> "已完成"
    SpeechPreparationStatus.EMPTY -> "没有对白"
    SpeechPreparationStatus.ERROR -> "处理失败"
    SpeechPreparationStatus.CANCELLED -> "已取消"
}

private fun voiceDescription(voice: SpeechVoiceSnapshot, voices: List<DoubaoVoice>): String {
    val engine = if (voice.engine == TtsEngine.DOUBAO) "火山 ${DoubaoVoices.resourceVersion(voice.resourceId) ?: voice.resourceId}" else "硅基流动 · ${voice.resourceId}"
    val name = voices.firstOrNull { it.id == voice.voiceId && DoubaoVoices.resourceVersion(it.resourceId) == DoubaoVoices.resourceVersion(voice.resourceId) }?.name
    return "$engine · ${name ?: voice.voiceId}\n${voice.voiceId}"
}

@Composable private fun SpeechPreparationCard(record: SpeechPreparationRecord, voices: List<DoubaoVoice>) {
    val clipboard = LocalClipboardManager.current
    var showRaw by rememberSaveable(record.id) { mutableStateOf(false) }
    val copyText = buildString {
        append("语音前置处理 · ${speechPreparationStatus(record)}\n模型：${record.model}")
        append("\n处理时间：${SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(record.createdAt))}")
        record.voice?.let { append("\n当次音色：${voiceDescription(it, voices)}") }
        append("\n\n提取对白：\n${record.text}")
        append("\n\n朗读指令：\n${record.instruction}")
        append("\n\n上一条朗读指令（本次参考）：\n${record.previousInstruction.ifBlank { "未参考上一条朗读指令" }}")
        if (record.reasoning.isNotBlank()) append("\n\n前置模型思考：\n${record.reasoning}")
        if (record.rawOutput.isNotBlank()) append("\n\n原始输出：\n${record.rawOutput}")
        if (record.error.isNotBlank()) append("\n\n错误：\n${record.error}")
    }
    Column(Modifier.fillMaxWidth().testTag("speech-preparation").background(ChatToolbar, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("语音前置处理", color = ChatGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(speechPreparationStatus(record), color = ChatSecondary, fontSize = 11.sp, modifier = Modifier.testTag("speech-preparation-status"))
        }
        Text("上次处理 · ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(record.createdAt))}", color = ChatSecondary, fontSize = 11.sp,
            modifier = Modifier.testTag("speech-preparation-time"))
        Text(record.model.ifBlank { "未记录模型" }, color = ChatSecondary, fontSize = 11.sp)
        if (record.modelInvoked == false && record.status == SpeechPreparationStatus.ERROR)
            Text("调用前检查未通过，小模型尚未运行。", color = ChatSecondary, fontSize = 12.sp, modifier = Modifier.testTag("speech-model-not-invoked"))
        if (record.error.isNotBlank()) Text(record.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.testTag("speech-preparation-error"))
        record.voice?.let { voice ->
            Text("当次使用：${voiceDescription(voice, voices)}", color = ChatSecondary, fontSize = 11.sp, lineHeight = 17.sp,
                modifier = Modifier.testTag("attempt-speech-voice"))
        }
        if (record.voice == null && record.status != SpeechPreparationStatus.EXTRACTING)
            Text("此处是上次处理结果；修改设置后需重新提取并朗读。旧记录未保存当次音色。", color = ChatSecondary, fontSize = 11.sp, lineHeight = 17.sp)
        Text("提取对白", color = ChatSecondary, fontSize = 12.sp)
        SelectionContainer {
            Text(record.text.ifBlank { when {
                record.status == SpeechPreparationStatus.EMPTY -> "没有可朗读的对白"
                record.modelInvoked == false -> "尚未提取"
                record.status == SpeechPreparationStatus.EXTRACTING -> "尚未返回对白"
                else -> "未取得有效提取结果"
            } },
                color = if (record.text.isBlank()) ChatSecondary else ChatText, fontSize = 14.sp, lineHeight = 22.sp,
                modifier = Modifier.testTag("speech-preparation-text"))
        }
        Text("朗读指令", color = ChatSecondary, fontSize = 12.sp)
        SelectionContainer {
            Text(record.instruction.ifBlank { if (record.status in setOf(SpeechPreparationStatus.COMPLETE, SpeechPreparationStatus.EMPTY)) "未指定朗读指令" else "尚未生成朗读指令" },
                color = if (record.instruction.isBlank()) ChatSecondary else ChatText, fontSize = 14.sp, lineHeight = 22.sp,
                modifier = Modifier.testTag("speech-preparation-instruction"))
        }
        Text("上一条朗读指令（本次参考）", color = ChatSecondary, fontSize = 12.sp)
        SelectionContainer {
            Text(record.previousInstruction.ifBlank { "未参考上一条朗读指令" },
                color = if (record.previousInstruction.isBlank()) ChatSecondary else ChatText, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.testTag("speech-preparation-previous-instruction"))
        }
        if (record.reasoning.isNotBlank()) {
            Text("前置模型思考", color = ChatSecondary, fontSize = 12.sp)
            SelectionContainer {
                Text(record.reasoning, color = ChatText, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("speech-preparation-reasoning"))
            }
        }
        if (record.rawOutput.isNotBlank()) {
            TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.testTag("toggle-speech-preparation-raw"), contentPadding = PaddingValues(0.dp)) {
                Text(if (showRaw) "收起原始输出" else "查看原始输出", fontSize = 12.sp)
            }
            if (showRaw) SelectionContainer {
                Text(record.rawOutput, color = ChatText, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("speech-preparation-raw"))
            }
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }, modifier = Modifier.testTag("copy-speech-preparation")
            .semantics { contentDescription = "复制语音前置处理" }, contentPadding = PaddingValues(0.dp)) {
            Text("复制语音前置处理", fontSize = 12.sp)
        }
    }
}

@Composable private fun MessageDetails(message: ChatMessage, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, shape = RoundedCornerShape(12.dp), title = { Text("回复详情", color = ChatText, fontSize = 18.sp) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Text("${message.senderName} · ${formatTime(message.createdAt)} · ${statusLabel(message.status)}", color = ChatSecondary, fontSize = 12.sp)
            if (message.error.isNotBlank()) Text(message.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp)
            message.segments.forEach { segment ->
                Column(Modifier.fillMaxWidth().background(ChatToolbar, RoundedCornerShape(7.dp)).padding(12.dp)) {
                    Text("模型 ${segment.label} · ${when (segment.status) {
                        SegmentStatus.RUNNING -> "生成中"; SegmentStatus.COMPLETE -> "完成"; SegmentStatus.CUT -> "按设置中断"; SegmentStatus.ERROR -> "发生错误"; SegmentStatus.INTERRUPTED -> "已停止"
                    }}", fontWeight = FontWeight.SemiBold, color = ChatGreen, fontSize = 12.sp)
                    Text(segment.model, color = ChatSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp, bottom = 9.dp))
                    if (segment.outputSource == ReplyOutputSource.REASONING) Text("此段按正文接收", color = ChatSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 7.dp).testTag("output-source-note-${segment.id}"))
                    SelectionContainer { Text(segment.text.ifBlank { "没有输出正文" }, color = ChatText, fontSize = 14.sp, lineHeight = 22.sp) }
                    if (segment.note.isNotBlank()) Text(segment.note, color = ChatSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
            if (message.segments.isEmpty()) SelectionContainer { Text(message.text) }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成") } }, dismissButton = {
        TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)); onDismiss() }, enabled = message.text.isNotBlank()) { Text("复制正文") }
    })
}
