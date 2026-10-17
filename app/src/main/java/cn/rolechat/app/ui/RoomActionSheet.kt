package cn.rolechat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.core.ChatRoom

private enum class RoomConfirmation { DELETE, CLEAR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RoomActionSheet(
    room: ChatRoom, onDismiss: () -> Unit, onTogglePinned: () -> Unit, onDelete: () -> Unit,
    onExport: () -> Unit, onClearHistory: (() -> Unit)? = null, onCompression: (() -> Unit)? = null,
    settingsContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    var confirmation by rememberSaveable(room.id) { mutableStateOf<RoomConfirmation?>(null) }
    when (confirmation) {
        RoomConfirmation.DELETE -> RoomDeleteConfirmation(room, onDismiss = { confirmation = null }, onConfirm = { onDismiss(); onDelete() })
        RoomConfirmation.CLEAR -> RoomClearConfirmation(room, onDismiss = { confirmation = null }, onConfirm = { onDismiss(); onClearHistory?.invoke() })
        null -> ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().testTag("room-action-sheet").verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                Text(room.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
                if (settingsContent != null) {
                    settingsContent()
                    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 6.dp))
                }
                RoomActionRow(if (room.pinned) "取消置顶" else "置顶", "room-pin-action", Icons.Outlined.PushPin) { onDismiss(); onTogglePinned() }
                if (onCompression != null) RoomActionRow("手动压缩", "room-compression-action", Icons.Outlined.Summarize,
                    subtitle = "整理旧前情，保留原聊天记录") { onDismiss(); onCompression() }
                if (!room.direct) RoomActionRow("导出群聊", "room-export-action", Icons.Outlined.FileUpload,
                    subtitle = "仅群聊配置，不含聊天记录") { onDismiss(); onExport() }
                if (onClearHistory != null) RoomActionRow("清空聊天记录", "room-clear-action", Icons.Outlined.DeleteSweep) { confirmation = RoomConfirmation.CLEAR }
                RoomActionRow("删除对话", "room-delete-action", Icons.Outlined.DeleteOutline, destructive = true) { confirmation = RoomConfirmation.DELETE }
            }
        }
    }
}

@Composable private fun RoomActionRow(label: String, tag: String, icon: ImageVector, subtitle: String? = null,
    destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else Ink
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick).semantics { contentDescription = label }
        .heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (destructive) color else Muted)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, color = color, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable internal fun RoomDeleteConfirmation(room: ChatRoom, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("delete-room-dialog"), containerColor = Panel,
        title = { Text("删除对话？") },
        text = { Text("将删除「${room.name}」及全部聊天记录、思考内容和会话上下文。角色和世界观会保留，此操作无法撤销。") },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-delete-room").semantics { contentDescription = "确认删除对话" }) {
            Text("删除对话", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun RoomClearConfirmation(room: ChatRoom, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("clear-history-dialog"), containerColor = Panel,
        title = { Text("清空聊天记录？") },
        text = { Text("将清空「${room.name}」的全部聊天记录、思考内容和会话上下文。会话、成员和设置会保留，接下来从空白记录开始。") },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-clear-history").semantics { contentDescription = "确认清空聊天记录" }) {
            Text("清空聊天记录", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
