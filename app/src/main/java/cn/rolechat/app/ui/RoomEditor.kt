package cn.rolechat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.app.RuntimeUi
import cn.rolechat.core.*

internal enum class RoomSection { BASIC, IDENTITY, MEMBERS, REPLY }

@Composable internal fun RoomEditor(initial: ChatRoom, state: AppState, onBack: () -> Unit,
    onSave: (ChatRoom) -> Unit, onDelete: () -> Unit, initialSection: RoomSection = RoomSection.BASIC,
    runtime: RuntimeUi = RuntimeUi(), onRefreshModels: (String) -> Unit = {}) {
    var room by rememberSaveable(initial.id, stateSaver = serializedSaver(ChatRoom.serializer())) { mutableStateOf(initial) }
    var avatarLoading by remember(initial.id) { mutableStateOf(false) }
    var section by rememberSaveable(initial.id, initialSection) { mutableStateOf(initialSection) }
    var expandedMember by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    val scrollStates = RoomSection.entries.map { rememberScrollState() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val exists = state.rooms.any { it.id == initial.id }
    val routingValidation = runCatching {
        if (!room.direct && room.mode == ReplyMode.SMART) validateRoutingSettings(room.routing, state.providers)
    }
    val routingValid = routingValidation.isSuccess
    val valid = room.name.isNotBlank() && room.members.isNotEmpty() && routingValid
    EditorScaffold(if (initial.direct) "聊天设置" else if (exists) "群聊设置" else "创建群聊", onBack, {
        onSave(room.copy(name = room.name.trim(), userName = room.userName.trim().ifBlank { "我" }, userBackground = room.userBackground.trim(),
            routing = room.routing.copy(model = room.routing.model.copy(model = room.routing.model.model.trim()))))
    }, valid && !avatarLoading) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            TabRow(selectedTabIndex = section.ordinal, containerColor = Panel, contentColor = Sage,
                divider = { HorizontalDivider(color = Line) }) {
                listOf("基础", "我的身份", "成员", "回复").forEachIndexed { index, title ->
                    Tab(selected = section.ordinal == index, onClick = { focusManager.clearFocus(); keyboard?.hide(); section = RoomSection.entries[index] },
                        text = { Text(title, fontSize = 14.sp, fontWeight = if (section.ordinal == index) FontWeight.SemiBold else FontWeight.Normal) },
                        selectedContentColor = Sage, unselectedContentColor = Muted)
                }
            }
            if (!valid) Text(when {
                room.name.isBlank() -> "请在「基础」填写会话名称"
                room.members.isEmpty() -> "请在「成员」添加至少一位角色"
                else -> "请检查「回复」：${routingValidation.exceptionOrNull()?.message ?: "接话模型或提示词配置无效。"}"
            }, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).then(if (!routingValid) Modifier.testTag("routing-validation-error") else Modifier))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollStates[section.ordinal]).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (section) {
                    RoomSection.BASIC -> {
                        FormCard {
                            FormField(if (room.direct) "会话名称" else "群聊名称", room.name, { room = room.copy(name = it) })
                            WorldSelector(state.worlds, room.worldId, emptyLabel = "使用角色各自的世界观") { room = room.copy(worldId = it) }
                            Text("会话选择世界观时优先使用会话世界观；否则使用角色世界观", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        FormCard {
                            ToggleRow("自动朗读回复", "使用角色音色，需要先配置语音服务", room.autoSpeak) { room = room.copy(autoSpeak = it) }
                        }
                        Text("聊天记录仅在当前会话中共享。", color = Muted, fontSize = 12.sp)
                        if (exists) OutlinedButton(onClick = { delete = true }, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("删除对话")
                        }
                    }
                    RoomSection.IDENTITY -> {
                        FormCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(null, modifier = Modifier.testTag("user-avatar-preview"), size = 48,
                                    label = room.userName.trim().ifBlank { "我" }.let { it.substring(0, it.offsetByCodePoints(0, 1)) }, image = room.userAvatarImage)
                                Column {
                                    Text("我的头像", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("只用于当前${if (room.direct) "会话" else "群聊"}", color = Muted, fontSize = 12.sp)
                                }
                            }
                            AvatarPhotoPicker(room.userAvatarImage, { room = room.copy(userAvatarImage = it) }, testTagPrefix = "user-avatar",
                                onLoadingChange = { avatarLoading = it })
                        }
                        FormCard {
                            FormField("我的名字", room.userName, { room = room.copy(userName = it) }, hint = "留空默认为「我」")
                            FormField("我的角色背景", room.userBackground, { room = room.copy(userBackground = it) }, multiline = true,
                                hint = "身份、经历、性格，以及你与其他角色的关系")
                        }
                        Text("仅当前${if (room.direct) "会话" else "群聊"}有效；修改后，旧消息仍保留原署名。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                    RoomSection.MEMBERS -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (room.direct) "聊天角色" else "已加入 ${room.members.size} 位角色", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (!room.direct) Text("点击成员调整", color = Muted, fontSize = 12.sp)
                        }
                        val selected = room.members.mapNotNull { member -> state.characters.find { it.id == member.characterId } }
                        selected.forEach { character ->
                            val member = room.members.first { it.characterId == character.id }
                            val expanded = expandedMember == character.id
                            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp))) {
                                Row(Modifier.fillMaxWidth().clickable(enabled = !room.direct) { expandedMember = if (expanded) null else character.id }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(character, size = 38)
                                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(character.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink,
                                                modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (character.messageStyle == MessageStyle.SCENE) SceneBadge()
                                        }
                                        Text(if (room.direct) "独立会话" else "参与 ${member.probabilityOverride ?: character.probability}% · 第 ${room.members.indexOf(member) + 1} 位", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                    }
                                    if (!room.direct) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "调整${character.name}", tint = Muted, modifier = Modifier.size(21.dp))
                                }
                                if (expanded && !room.direct) {
                                    Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        fun updateMember(updated: Member) { room = room.copy(members = room.members.map { if (it.characterId == character.id) updated else it }) }
                                        HorizontalDivider(color = Line)
                                        ToggleRow("单独设置参与概率", value = member.probabilityOverride != null,
                                            onChange = { updateMember(member.copy(probabilityOverride = if (it) character.probability else null)) })
                                        member.probabilityOverride?.let { probability -> IntSlider("参与概率", probability, 0..100, "%") { updateMember(member.copy(probabilityOverride = it)) } }
                                        ToggleRow("读取入群前的历史", value = member.readHistory, onChange = { updateMember(member.copy(readHistory = it)) })
                                        val index = room.members.indexOf(member)
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("发言顺序", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                            IconButton(enabled = index > 0, onClick = {
                                                val list = room.members.toMutableList(); val moved = list.removeAt(index); list.add(index - 1, moved); room = room.copy(members = list)
                                            }) { Icon(Icons.Outlined.ArrowUpward, "向前移动", Modifier.size(19.dp)) }
                                            IconButton(enabled = index < room.members.lastIndex, onClick = {
                                                val list = room.members.toMutableList(); val moved = list.removeAt(index); list.add(index + 1, moved); room = room.copy(members = list)
                                            }) { Icon(Icons.Outlined.ArrowDownward, "向后移动", Modifier.size(19.dp)) }
                                            TextButton(onClick = {
                                                room = room.copy(members = room.members.filterNot { it.characterId == character.id }); expandedMember = null
                                            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移出") }
                                        }
                                    }
                                }
                            }
                        }
                        if (!room.direct) {
                            val available = state.characters.filter { character -> room.members.none { it.characterId == character.id } }
                            if (available.isNotEmpty()) {
                                Text("添加角色", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp))) {
                                    available.forEachIndexed { index, character ->
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Avatar(character, size = 36)
                                            Row(Modifier.weight(1f).padding(start = 11.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(character.name, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false),
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (character.messageStyle == MessageStyle.SCENE) SceneBadge()
                                            }
                                            TextButton(onClick = { room = room.copy(members = room.members + Member(character.id)) }) {
                                                Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("加入")
                                            }
                                        }
                                        if (index < available.lastIndex) HorizontalDivider(Modifier.padding(start = 61.dp), color = Line)
                                    }
                                }
                            }
                            Text("新成员默认只能读取入群后的消息。", color = Muted, fontSize = 12.sp)
                        }
                        if (state.characters.isEmpty()) Text("还没有角色，请先到「角色」创建。", color = Muted, fontSize = 13.sp)
                        else if (room.direct && selected.isEmpty()) Text("这个角色已移除，历史消息仍可查看。", color = Muted, fontSize = 13.sp)
                    }
                    RoomSection.REPLY -> {
                        if (room.direct) {
                            FormCard {
                                Text("单聊直接回复你的消息", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("模型与双模型接续参数，可在角色设置的「模型」中调整。", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                            }
                        } else {
                            FormCard {
                                ReplyModeChoices(room.mode) { room = room.copy(mode = it) }
                            }
                            if (room.mode == ReplyMode.SMART) {
                                SmartRoutingSettings(room.routing, state.providers, runtime, onRefreshModels) { room = room.copy(routing = it) }
                            } else {
                                FormCard {
                                    IntSlider("第一批最多回复", room.maxInitialReplies, 1..12, " 位") { room = room.copy(maxInitialReplies = it) }
                                    ToggleRow("随机发言顺序", "关闭时按成员顺序回复", room.shuffleOrder) { room = room.copy(shuffleOrder = it) }
                                }
                                FormCard {
                                    ToggleRow("角色互聊", "第一批结束后，继续顺序接话", room.interChat) { room = room.copy(interChat = it) }
                                    if (room.interChat) IntSlider("最多追加发言", room.maxFollowUps, 1..20, " 条") { room = room.copy(maxFollowUps = it) }
                                }
                            }
                            Text("发送新消息会打断当前轮；被 @ 的角色优先回复。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (delete) RoomDeleteConfirmation(room, onDismiss = { delete = false }, onConfirm = { delete = false; onDelete() })
}
