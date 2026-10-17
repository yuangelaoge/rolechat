package cn.rolechat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.app.RuntimeUi
import cn.rolechat.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun replyModeLabel(mode: ReplyMode): String = when (mode) {
    ReplyMode.SEQUENTIAL -> "顺序回复"
    ReplyMode.PARALLEL -> "并发回复"
    ReplyMode.SMART -> "智能接话"
}

@Composable internal fun ReplyModeChoices(mode: ReplyMode, onChange: (ReplyMode) -> Unit) {
    listOf(ReplyMode.SMART, ReplyMode.SEQUENTIAL, ReplyMode.PARALLEL).forEach { option ->
        Row(Modifier.fillMaxWidth().selectable(mode == option, role = Role.RadioButton, onClick = { onChange(option) })
            .testTag("reply-mode-${option.name}").padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == option, onClick = null, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (option == ReplyMode.SMART) "智能接话（推荐）" else replyModeLabel(option), color = Ink, fontSize = 15.sp)
                Text(when (option) {
                    ReplyMode.SMART -> "根据话题判断下一位，或等待你回复"
                    ReplyMode.SEQUENTIAL -> "依次回复，后面的角色能看到前文"
                    ReplyMode.PARALLEL -> "同时回复，各自读取相同的群记录"
                }, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable internal fun SmartRoutingSettings(routing: RoutingSettings, providers: List<Provider>, runtime: RuntimeUi,
    onRefreshModels: (String) -> Unit, onChange: (RoutingSettings) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    val provider = providers.find { it.id == routing.model.providerId }
    val loading = routing.model.providerId in runtime.loadingModels
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    FormCard {
        Text("接话判断模型", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        SelectField("模型服务", provider?.name ?: "请选择模型服务", providers.map { it.name }, Modifier.testTag("routing-provider")) { index ->
            onChange(routing.copy(model = routing.model.copy(providerId = providers[index].id)))
        }
        FormField("接话判断模型", routing.model.model, { onChange(routing.copy(model = routing.model.copy(model = it))) },
            modifier = Modifier.testTag("routing-model"), hint = "独立判断谁接话，可直接填写模型名称")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); showModels = true }, shape = RoundedCornerShape(9.dp),
                modifier = Modifier.weight(1f).testTag("choose-routing-model"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("从列表选择", fontSize = 12.sp)
            }
            TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); onRefreshModels(routing.model.providerId) },
                enabled = provider != null && !loading, modifier = Modifier.weight(1f).testTag("refresh-routing-models"),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp)); Text(if (loading) "正在获取" else "刷新模型列表", fontSize = 12.sp)
            }
        }
        if (provider != null && provider.id !in runtime.configuredProviders) Text("请先在设置中为「${provider.name}」填写 API 密钥。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("点名优先；问题交给你时会等待。需要判断时会额外调用此模型，产生相应费用。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        Text("每次保留本轮原要求，举例或偏题时提醒接回原主题。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        Text("你明确要求转场或跳过时间时，会尝试安排场景角色推进；场景角色需已加入群聊。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    }
    FormCard {
        RoutingSlider("最近消息条数", routing.recentMessages, " 条", "routing-recent-messages") { onChange(routing.copy(recentMessages = it)) }
        RoutingSlider("整轮最多发言", routing.maxAutoReplies, " 条", "routing-max-replies") { onChange(routing.copy(maxAutoReplies = it)) }
        Text("发言上限包含场景角色。达到上限后等待你发消息，避免角色一直聊下去。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    FormCard {
        Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.testTag("routing-advanced").padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("接话提示词", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("高级设置", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (advanced) "收起接话提示词" else "展开接话提示词", tint = Muted)
        }
        if (advanced) {
            FormField("接话提示词", routing.systemPrompt, { onChange(routing.copy(systemPrompt = it)) },
                multiline = true, modifier = Modifier.testTag("routing-prompt"))
            TextButton(onClick = { onChange(routing.copy(systemPrompt = DEFAULT_ROUTING_PROMPT)) },
                modifier = Modifier.align(Alignment.End).testTag("reset-routing-prompt")) { Text("恢复默认提示词") }
        }
    }
    if (showModels) ModelPicker(runtime.modelLists[routing.model.providerId].orEmpty(), { showModels = false }) { model ->
        onChange(routing.copy(model = routing.model.copy(model = model))); showModels = false
    }
}

@Composable private fun RoutingSlider(label: String, value: Int, suffix: String, tag: String, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Ink, fontSize = 14.sp)
            Text("$value$suffix", color = Sage, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Slider(value.coerceIn(1, 20).toFloat(), { onChange(it.toInt().coerceIn(1, 20)) }, valueRange = 1f..20f, steps = 18,
            modifier = Modifier.testTag(tag))
    }
}

internal fun routingStatusLabel(record: RoutingRecord): String = when (record.status) {
    RoutingStatus.DECIDING -> "正在判断接话"
    RoutingStatus.DECIDED -> "已完成判断"
    RoutingStatus.ERROR -> "接话判断失败"
    RoutingStatus.CANCELLED -> "接话判断已取消"
}

private fun routingActionLabel(record: RoutingRecord): String = when (record.status) {
    RoutingStatus.DECIDING -> "等待判断结果"
    RoutingStatus.ERROR, RoutingStatus.CANCELLED -> "尚未安排下一位"
    RoutingStatus.DECIDED -> when (record.action) {
        RoutingAction.ACTOR -> "已安排 ${record.selectedName.ifBlank { record.selectedCharacterId ?: record.targetId }.ifBlank { "角色" }} 接话"
        RoutingAction.WAIT_USER -> "等待你回复"
        RoutingAction.OPEN -> if (record.selectedName.isNotBlank()) "已安排 ${record.selectedName} 接话（开放话题）" else "开放接话"
        RoutingAction.STOP -> "本轮接话已结束"
        null -> "尚未安排下一位"
    }
}

private fun routingSourceLabel(record: RoutingRecord): String = when (record.source) {
    RoutingSource.MODEL -> "判断模型 · ${record.model.ifBlank { "未记录模型" }}"
    RoutingSource.MENTION -> "来源 · 手动 @ 点名"
    RoutingSource.RULE -> "来源 · 会话规则"
}

private fun routingTime(record: RoutingRecord): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(record.createdAt))

private fun routingTopicLabel(topic: TopicStatus): String = when (topic) {
    TopicStatus.ON_TOPIC -> "围绕原要求"
    TopicStatus.EXAMPLE -> "举例说明"
    TopicStatus.OFF_TOPIC -> "偏离原要求"
}

private fun routingTopicNote(record: RoutingRecord): String? {
    if (record.topicStatus == null || record.topicStatus == TopicStatus.ON_TOPIC) return null
    val hasNextActor = record.status == RoutingStatus.DECIDED && !record.selectedCharacterId.isNullOrBlank() &&
        record.action in setOf(RoutingAction.ACTOR, RoutingAction.OPEN)
    return if (hasNextActor) {
        if (record.topicStatus == TopicStatus.EXAMPLE) "下一位接话时会提醒回到原主题。" else "下一位接话时会优先本轮原要求。"
    } else "尚未安排下一位；后续接话时应回到原主题。"
}

private fun routingCopyText(record: RoutingRecord): String = buildString {
    append("接话安排\n状态：${routingStatusLabel(record)}\n安排：${routingActionLabel(record)}")
    if (record.reason.isNotBlank()) append("\n依据：${record.reason}")
    append("\n${routingSourceLabel(record)}")
    if (record.error.isNotBlank()) append("\n错误：${record.error}")
    append("\n时间：${routingTime(record)}")
    record.topicStatus?.let { append("\n本次话题判断：${routingTopicLabel(it)}") }
    routingTopicNote(record)?.let { append("\n接话提示：$it") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RoutingDecisionSheet(message: ChatMessage?, onDismiss: () -> Unit) {
    val record = message?.routingDecision
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).testTag("routing-sheet").padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("接话安排", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭接话安排", tint = Muted) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (record == null) Text("当前会话尚无接话记录。", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                else {
                    if (message.text.isNotBlank()) Text("${message.senderName}：${message.text}", color = Muted, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(routingStatusLabel(record), color = if (record.status == RoutingStatus.ERROR) MaterialTheme.colorScheme.error else Muted,
                        fontSize = 12.sp, modifier = Modifier.testTag("routing-status"))
                    Text(routingActionLabel(record), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("routing-action"))
                    if (record.reason.isNotBlank()) SelectionContainer {
                        Text(record.reason, color = Ink, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("routing-reason"))
                    }
                    Text(routingSourceLabel(record), color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("routing-source"))
                    if (record.error.isNotBlank()) SelectionContainer {
                        Text(record.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("routing-error"))
                    }
                    Text(routingTime(record), color = Muted, fontSize = 11.sp, modifier = Modifier.testTag("routing-time"))
                    record.topicStatus?.let { topic ->
                        Column(Modifier.fillMaxWidth().testTag("routing-topic"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            HorizontalDivider(color = Line)
                            Text("本次话题判断", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                            Text(routingTopicLabel(topic), color = Ink, fontSize = 14.sp, modifier = Modifier.testTag("routing-topic-status"))
                            routingTopicNote(record)?.let { note ->
                                Text(note, color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("routing-topic-note"))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { record?.let { clipboard.setText(AnnotatedString(routingCopyText(it))) } }, enabled = record != null,
                modifier = Modifier.fillMaxWidth().testTag("copy-routing-record").semantics { contentDescription = "复制接话记录" },
                shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("复制接话记录")
            }
        }
    }
}
