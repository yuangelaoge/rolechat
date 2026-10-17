package cn.rolechat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.app.AppActions
import cn.rolechat.app.RuntimeUi
import cn.rolechat.app.speech.DoubaoVoices
import cn.rolechat.core.*
import cn.rolechat.core.Character
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private sealed interface Editor {
    data class Role(val character: Character, val section: CharacterSection = CharacterSection.PERSONA) : Editor
    data class Room(val room: ChatRoom, val section: RoomSection = RoomSection.BASIC) : Editor
    data class Compression(val roomId: String) : Editor
    data class Service(val provider: Provider) : Editor
    data class Universe(val world: World) : Editor
    data object Speech : Editor
}

private val EditorSaver = Saver<Editor?, List<String>>(
    save = { editor -> when (editor) {
        is Editor.Role -> listOf("role", Json.encodeToString(editor.character), editor.section.name)
        is Editor.Room -> listOf("room", Json.encodeToString(editor.room), editor.section.name)
        is Editor.Compression -> listOf("compression", editor.roomId)
        is Editor.Service -> listOf("provider", Json.encodeToString(editor.provider))
        is Editor.Universe -> listOf("world", Json.encodeToString(editor.world))
        Editor.Speech -> listOf("speech")
        null -> listOf("none")
    } },
    restore = { saved -> runCatching { when (saved.firstOrNull()) {
        "role" -> Editor.Role(Json.decodeFromString<Character>(saved[1]), saved.getOrNull(2)?.let { CharacterSection.valueOf(it) } ?: CharacterSection.PERSONA)
        "room" -> Editor.Room(Json.decodeFromString<ChatRoom>(saved[1]), saved.getOrNull(2)?.let { RoomSection.valueOf(it) } ?: RoomSection.BASIC)
        "compression" -> Editor.Compression(saved[1])
        "provider" -> Editor.Service(Json.decodeFromString<Provider>(saved[1]))
        "world" -> Editor.Universe(Json.decodeFromString<World>(saved[1]))
        "speech" -> Editor.Speech
        else -> null
    } }.getOrNull() }
)

@Composable fun RoleChatApp(state: AppState, runtime: RuntimeUi, actions: AppActions) {
    val colors = darkColorScheme(primary = Sage, onPrimary = AvatarInk, primaryContainer = SoftGreen,
        onPrimaryContainer = Ink, secondary = Sage, onSecondary = AvatarInk, secondaryContainer = SoftGreen, onSecondaryContainer = Ink,
        tertiary = Sage, onTertiary = AvatarInk, tertiaryContainer = SoftGreen, onTertiaryContainer = Ink,
        background = Paper, onBackground = Ink, surface = Panel, onSurface = Ink,
        surfaceVariant = Raised, onSurfaceVariant = Muted, outline = Muted.copy(alpha = .65f), outlineVariant = Line,
        surfaceTint = Color.Transparent, surfaceDim = Paper, surfaceBright = Raised,
        surfaceContainerLowest = Paper, surfaceContainerLow = Panel, surfaceContainer = Panel,
        surfaceContainerHigh = Raised, surfaceContainerHighest = Raised,
        inverseSurface = Raised, inverseOnSurface = Ink, inversePrimary = Sage,
        error = Color(0xFFFF8A80), onError = Color(0xFF3C1010), errorContainer = Color(0xFF512824), onErrorContainer = Color(0xFFFFDAD6))
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var roomId by rememberSaveable { mutableStateOf<String?>(null) }
        var editor by rememberSaveable(stateSaver = EditorSaver) { mutableStateOf<Editor?>(null) }
        val conversationState = rememberSaveableStateHolder()
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(editor, roomId, tab) { focus.clearFocus(force = true); keyboard?.hide() }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(runtime.notice) { if (runtime.notice.isNotBlank()) snackbar.showSnackbar(runtime.notice) }
        BackHandler(enabled = editor != null || roomId != null) { if (editor != null) editor = null else roomId = null }
        val closeEditor = { editor = null }
        val startDirect: (Character) -> Unit = { character ->
            val existing = state.rooms.find { it.direct && it.members.size == 1 && it.members.first().characterId == character.id }
            val room = existing ?: ChatRoom(name = character.name, direct = true, members = listOf(Member(character.id)), worldId = character.worldId)
            if (existing == null) actions.saveRoom(room)
            roomId = room.id
        }
        Box(Modifier.fillMaxSize().background(Paper)) {
            when (val edit = editor) {
                is Editor.Role -> CharacterEditor(edit.character, state, runtime, onBack = closeEditor, initialSection = edit.section,
                    onSave = { actions.saveCharacter(it); editor = null }, onDelete = { actions.deleteCharacter(edit.character.id); editor = null },
                    onPreviewVoice = actions::previewVoice, onStopAudio = actions::stopAudio, onRefreshDoubaoVoices = actions::refreshDoubaoVoices,
                    onSaveVoice = { voice -> actions.setCharacterVoice(edit.character.id, voice) })
                is Editor.Room -> RoomEditor(edit.room, state, onBack = closeEditor, initialSection = edit.section,
                    runtime = runtime, onRefreshModels = actions::refreshModels,
                    onSave = { actions.saveRoom(it); editor = null; roomId = it.id },
                    onDelete = { actions.deleteRoom(edit.room.id); editor = null; if (roomId == edit.room.id) roomId = null })
                is Editor.Compression -> {
                    val compressionRoom = state.rooms.find { it.id == edit.roomId }
                    if (compressionRoom != null) CompressionScreen(compressionRoom, state, runtime, actions, closeEditor)
                    else LaunchedEffect(edit.roomId) { actions.cancelCompression(edit.roomId); editor = null }
                }
                is Editor.Service -> ProviderEditor(edit.provider, runtime, actions, closeEditor) { provider, key -> actions.saveProvider(provider, key); editor = null }
                is Editor.Universe -> WorldEditor(edit.world, closeEditor) { actions.saveWorld(it); editor = null }
                Editor.Speech -> TtsEditor(state.tts, state, runtime, closeEditor) { settings, key, token, voiceAccessKey, voiceSecretKey ->
                    actions.saveTts(settings, key, token, voiceAccessKey, voiceSecretKey); editor = null
                }
                null -> {
                    val room = state.rooms.find { it.id == roomId }
                    if (room != null) {
                        conversationState.SaveableStateProvider(room.id) {
                            ChatScreen(room, state, runtime, actions, onBack = { roomId = null }, onEdit = { section -> editor = Editor.Room(room, section) },
                                onSettings = { roomId = null; tab = 2 }, onEditCharacter = { characterId ->
                                    state.characters.find { it.id == characterId }?.let { editor = Editor.Role(it) }
                                }, onCompression = { editor = Editor.Compression(room.id) })
                        }
                    } else {
                        Scaffold(containerColor = Paper, bottomBar = { MainNavigation(tab) { tab = it } }) { padding ->
                            when (tab) {
                                0 -> ChatList(state, runtime, actions, Modifier.padding(padding), onOpen = { roomId = it },
                                    onCreate = { editor = Editor.Room(ChatRoom(name = "", mode = ReplyMode.SMART,
                                        routing = RoutingSettings(model = state.tts.dialogueExtraction.model.copy(thinking = ThinkingMode.OFF,
                                            temperature = 0.0, maxTokens = 512)))) }, onSetup = { tab = 2 })
                                1 -> CharacterList(state, Modifier.padding(padding), onEdit = { role, section -> editor = Editor.Role(role, section) },
                                    onCreate = { editor = Editor.Role(newCharacter(state, scene = false)) },
                                    onCreateScene = { editor = Editor.Role(newCharacter(state, scene = true)) }, onChat = startDirect)
                                else -> SettingsScreen(state, runtime, actions, Modifier.padding(padding),
                                    onProvider = { editor = Editor.Service(it) }, onWorld = { editor = Editor.Universe(it) }, onSpeech = { editor = Editor.Speech })
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (editor == null && roomId == null) 80.dp else 12.dp))
        }
    }
}

private fun newCharacter(state: AppState, scene: Boolean) = Character(
    name = if (scene) "场景提示" else "", avatar = if (scene) "景" else "✦",
    prompt = if (scene) "你是场景叙述者。根据共享世界观和当前会话，用简短的第三人称描写环境变化、时间推进、人物动作与气氛。只输出场景描述，不代替用户说话，不冒充其他角色，不重复已有对话。" else "自然、简洁地参与聊天。",
    messageStyle = if (scene) MessageStyle.SCENE else MessageStyle.CHAT,
    worldId = state.worlds.firstOrNull()?.id,
    voice = DoubaoVoices.defaultVoice(state.tts.resourceId),
    primary = ModelConfig(providerId = state.providers.firstOrNull()?.id ?: ""),
    secondary = ModelConfig(providerId = state.providers.firstOrNull()?.id ?: "")
)

@Composable private fun MainNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = Panel, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("聊天" to Icons.Outlined.ChatBubbleOutline, "角色" to Icons.Outlined.PeopleOutline, "设置" to Icons.Outlined.Tune).forEachIndexed { index, item ->
                    Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.width(52.dp).height(28.dp), contentAlignment = Alignment.Center) {
                            Icon(item.second, item.first, Modifier.size(23.dp), tint = if (selected == index) Sage else Muted)
                        }
                        Text(item.first, fontSize = 11.sp, color = if (selected == index) Sage else Muted, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun PageHeader(title: String, subtitle: String, onAdd: (() -> Unit)? = null, onCreateScene: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1)
        if (onCreateScene != null) TextButton(onClick = onCreateScene,
            modifier = Modifier.padding(start = 5.dp).semantics { contentDescription = "创建场景提示" },
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("场景提示", fontSize = 13.sp) }
        if (onAdd != null) IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, "新建", tint = Ink) }
    }
}

@Composable private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        placeholder = { Text(hint, fontSize = 14.sp) }, singleLine = true, shape = RoundedCornerShape(10.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = Muted) },
        trailingIcon = if (value.isNotEmpty()) { { IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(18.dp)) } } } else null,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = Sage.copy(alpha = .65f), unfocusedContainerColor = Panel, focusedContainerColor = Panel))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ChatList(state: AppState, runtime: RuntimeUi, actions: AppActions, modifier: Modifier, onOpen: (String) -> Unit, onCreate: () -> Unit, onSetup: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showCreateMenu by rememberSaveable { mutableStateOf(false) }
    var actionRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    val inputFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun cancelSearch() {
        query = ""
        searching = false
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    BackHandler(enabled = searching) { cancelSearch() }
    LaunchedEffect(searching) {
        if (searching) {
            withFrameNanos { }
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }
    val rooms = state.rooms.filter { room -> query.isBlank() || room.name.contains(query, true) || room.userName.contains(query, true) ||
        room.members.any { m -> state.characters.any { it.id == m.characterId && it.name.contains(query, true) } } }
        .sortedWith(compareByDescending<ChatRoom> { it.pinned }
            .thenByDescending { room -> state.messages.lastOrNull { it.roomId == room.id }?.createdAt ?: room.createdAt })
    Column(modifier.fillMaxSize().background(Color(0xFF191919))) {
        Box(Modifier.fillMaxWidth().background(Paper).height(if (searching) 60.dp else 56.dp).testTag("chat-list-header")) {
            if (searching) {
                Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(query, { query = it },
                        modifier = Modifier.weight(1f).height(42.dp).focusRequester(inputFocus).testTag("search-input"),
                        textStyle = TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 20.sp), singleLine = true,
                        cursorBrush = SolidColor(Sage), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                        decorationBox = { innerTextField ->
                            Row(Modifier.fillMaxSize().background(Raised, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = Muted)
                                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                                    if (query.isEmpty()) Text("搜索群聊、角色或我的身份", color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    innerTextField()
                                }
                                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(17.dp), tint = Muted)
                                }
                            }
                        })
                    TextButton(onClick = { cancelSearch() }, modifier = Modifier.semantics { contentDescription = "取消搜索" },
                        contentPadding = PaddingValues(horizontal = 10.dp)) { Text("取消", color = Ink, fontSize = 15.sp) }
                }
            } else {
                Text("片刻", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Row(Modifier.align(Alignment.CenterEnd).padding(end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "搜索会话", Modifier.size(24.dp), tint = Ink) }
                    Box {
                        IconButton(onClick = { showCreateMenu = true }) { Icon(Icons.Outlined.AddCircleOutline, "新建", Modifier.size(25.dp), tint = Ink) }
                        DropdownMenu(expanded = showCreateMenu, onDismissRequest = { showCreateMenu = false }, modifier = Modifier.testTag("chat-create-menu")) {
                            DropdownMenuItem(text = { Text("创建群聊") }, onClick = { showCreateMenu = false; onCreate() },
                                leadingIcon = { Icon(Icons.Outlined.GroupAdd, null) },
                                modifier = Modifier.testTag("create-group-action").semantics { contentDescription = "创建群聊" })
                            DropdownMenuItem(text = { Text("导入群聊") }, onClick = { showCreateMenu = false; actions.importGroup() },
                                leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                                modifier = Modifier.testTag("import-group-action").semantics { contentDescription = "导入群聊" })
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat-list")) {
            if (runtime.configuredProviders.isEmpty()) item {
                Row(Modifier.fillMaxWidth().background(SoftGreen).clickable(onClick = onSetup).heightIn(min = 38.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, null, tint = Sage, modifier = Modifier.size(16.dp))
                    Text("连接模型服务，开始聊天", Modifier.weight(1f).padding(horizontal = 8.dp), color = Sage, fontSize = 12.sp)
                    Icon(Icons.Outlined.ChevronRight, null, tint = Sage, modifier = Modifier.size(17.dp))
                }
            }
            if (rooms.isEmpty()) item { EmptyPanel(if (query.isBlank()) "还没有会话" else "没有找到会话", if (query.isBlank()) "创建群聊，或到角色页发起单聊。" else "试试其他群名、角色名或身份名。", "☁") {
                if (query.isBlank()) Button(onCreate, shape = RoundedCornerShape(8.dp)) { Text("创建群聊") }
            } }
            items(rooms, key = { it.id }) { room ->
                val last = state.messages.lastOrNull { it.roomId == room.id }
                val busy = room.id in runtime.busyRooms
                Column(Modifier.background(if (room.pinned) Color(0xFF232323) else Color(0xFF191919))) {
                    Row(Modifier.fillMaxWidth().testTag("chat-list-row-${room.id}")
                        .combinedClickable(onClick = { onOpen(room.id) }, onLongClick = { focus.clearFocus(); keyboard?.hide(); actionRoomId = room.id }).heightIn(min = 74.dp)
                        .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoomAvatar(room, state, Modifier.testTag("chat-list-avatar-${room.id}"))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(room.name, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (room.pinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.padding(start = 8.dp).size(12.dp).testTag("chat-list-pinned-${room.id}"), tint = Muted)
                                if (last != null) Text(chatListTime(last.createdAt), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 12.dp))
                            }
                            Text(if (busy) "正在输入…" else chatPreview(room, last), color = if (busy) Sage else Muted,
                                fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 72.dp), thickness = .5.dp, color = Line)
                }
            }
        }
    }
    state.rooms.find { it.id == actionRoomId }?.let { room ->
        RoomActionSheet(room, onDismiss = { actionRoomId = null },
            onTogglePinned = { actions.setRoomPinned(room.id, !room.pinned) },
            onDelete = { actions.deleteRoom(room.id) }, onExport = { actions.exportGroup(room.id) })
    }
}

private fun chatPreview(room: ChatRoom, message: ChatMessage?): String {
    if (message == null) return if (room.direct) "点击开始聊天" else "${room.members.size} 位角色 · 点击开始聊天"
    val scene = if (message.messageStyle == MessageStyle.SCENE) "[场景] " else ""
    val sender = if (!room.direct && message.senderName.isNotBlank()) "${message.senderName}：" else ""
    val body = message.text.ifBlank { statusLabel(message.status) }.replace(Regex("\\s+"), " ").trim()
    return scene + sender + body
}

private fun chatListTime(time: Long): String {
    val today = LocalDate.now()
    val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return when {
        date == today -> formatTime(time)
        date == today.minusDays(1) -> "昨天"
        !date.isAfter(today) && !date.isBefore(weekStart) -> "星期" + listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
        else -> date.format(DateTimeFormatter.ofPattern("MM/dd", Locale.CHINA))
    }
}

@Composable internal fun RoomAvatar(room: ChatRoom, state: AppState, modifier: Modifier = Modifier) {
    val characters = room.members.mapNotNull { member -> state.characters.find { it.id == member.characterId } }
    val direct = room.direct && characters.isNotEmpty()
    Box(modifier.size(46.dp).clip(RoundedCornerShape(5.dp)).background(if (direct) Color(characters.first().color) else Raised), contentAlignment = Alignment.Center) {
        if (direct) Avatar(characters.first(), size = 46)
        else if (characters.isEmpty()) Icon(Icons.Outlined.Forum, null, tint = Sage)
        else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                characters.take(4).chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { pair.forEach { Avatar(it, size = if (characters.size == 1) 36 else 20) } } }
            }
        }
    }
}

@Composable private fun CharacterList(state: AppState, modifier: Modifier, onEdit: (Character, CharacterSection) -> Unit, onCreate: () -> Unit, onCreateScene: () -> Unit, onChat: (Character) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val roles = state.characters.filter { query.isBlank() || it.name.contains(query, true) || it.prompt.contains(query, true) || it.primary.model.contains(query, true) }
    Column(modifier.fillMaxSize()) {
        PageHeader("角色", "${state.characters.size} 位", onCreate, onCreateScene)
        SearchField(query, { query = it }, "搜索角色、人设或模型")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (roles.isEmpty()) item { EmptyPanel(if (query.isBlank()) "创建第一个角色" else "没有找到角色", "为角色设置人设、模型与声音。") {
                if (query.isBlank()) Button(onCreate, shape = RoundedCornerShape(8.dp)) { Text("创建角色") }
            } }
            items(roles, key = { it.id }) { character ->
                Surface(shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.fillMaxWidth(), onClick = { onEdit(character, CharacterSection.PERSONA) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(character, size = 44)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(character.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                                        modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (character.messageStyle == MessageStyle.SCENE) SceneBadge()
                                }
                                Text(character.primary.model.substringAfterLast('/').ifBlank { "未选择模型" }, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                            Icon(Icons.Outlined.ChevronRight, "编辑角色", tint = Muted, modifier = Modifier.size(20.dp))
                        }
                        Text(character.prompt.ifBlank { "点击填写角色背景" }, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 11.dp, bottom = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (character.dualModel) "A + B 接续" else "单模型", color = Sage, fontSize = 11.sp, modifier = Modifier.background(SoftGreen, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onEdit(character, CharacterSection.PERSONA) }, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.widthIn(min = 48.dp)) { Text("人设", fontSize = 13.sp) }
                            TextButton(onClick = { onEdit(character, CharacterSection.MODELS) }, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.widthIn(min = 48.dp)) { Text("模型", fontSize = 13.sp) }
                            FilledTonalButton(onClick = { onChat(character) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) { Text("聊天", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(state: AppState, runtime: RuntimeUi, actions: AppActions, modifier: Modifier,
    onProvider: (Provider) -> Unit, onWorld: (World) -> Unit, onSpeech: () -> Unit) {
    var confirmImport by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("设置", "片刻 0.1.18") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("模型服务")
                FormCard {
                    state.providers.forEachIndexed { index, provider ->
                        SettingsRow(provider.name, if (provider.id in runtime.configuredProviders) "已配置密钥" else "尚未配置密钥", Icons.Outlined.Hub, onClick = { onProvider(provider) })
                        if (index < state.providers.lastIndex) HorizontalDivider(color = Line)
                    }
                    TextButton(onClick = { onProvider(Provider(name = "", baseUrl = "", prefixMode = PrefixMode.ASSISTANT)) }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("添加模型服务")
                    }
                }
                SectionLabel("共享世界观")
                FormCard {
                    state.worlds.forEachIndexed { index, world ->
                        SettingsRow(world.name, world.prompt.ifBlank { "尚未填写世界观" }, Icons.Outlined.Public, onClick = { onWorld(world) })
                        if (index < state.worlds.lastIndex) HorizontalDivider(color = Line)
                    }
                    TextButton(onClick = { onWorld(World(name = "")) }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("创建世界观") }
                }
                SectionLabel("群聊配置")
                FormCard {
                    SettingsRow("导入群聊配置（不含聊天记录）", "从配置文件创建新群聊", Icons.Outlined.GroupAdd, onClick = actions::importGroup,
                        modifier = Modifier.testTag("settings-import-group").semantics { contentDescription = "导入群聊配置（不含聊天记录）" })
                }
                SectionLabel("声音与数据")
                FormCard {
                    SettingsRow("语音服务", (if (state.tts.engine == TtsEngine.SILICONFLOW) "硅基流动" else "火山引擎") + when { !state.tts.enabled -> " · 未开启"; runtime.ttsConfigured -> " · 已配置"; else -> " · 待配置密钥" }, Icons.Outlined.GraphicEq, onClick = onSpeech)
                    HorizontalDivider(color = Line)
                    SettingsRow("导出聊天备份", "角色、世界观、会话和消息；不含密钥", Icons.Outlined.FileUpload, onClick = actions::exportBackup)
                    HorizontalDivider(color = Line)
                    SettingsRow("导入聊天备份", "从本地备份替换当前聊天数据", Icons.Outlined.FileDownload, onClick = { confirmImport = true })
                }
                Text("片刻 · 本地保存，每段会话独立记忆", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 27.dp))
            }
        }
    }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("导入备份并替换数据？") },
        text = { Text("导入会替换本机现有的角色、世界观、会话和消息。备份不包含密钥；服务地址变化时需要重新填写密钥。建议先导出当前备份。") },
        confirmButton = { TextButton(onClick = { confirmImport = false; actions.importBackup() }) { Text("选择备份") } },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } })
}

@Composable private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sage, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), lineHeight = 17.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
    }
}

internal fun formatTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(time))
internal fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.QUEUED -> "等待发言"
    MessageStatus.GENERATING_A -> "正在输入"
    MessageStatus.THINKING_A -> "A 思考中"
    MessageStatus.HANDOFF -> "正在切换 B"
    MessageStatus.GENERATING_B -> "B 接续中"
    MessageStatus.THINKING_B -> "B 思考中"
    MessageStatus.COMPLETE -> "已完成"
    MessageStatus.INTERRUPTED -> "已停止"
    MessageStatus.ERROR -> "回复未完成"
}
