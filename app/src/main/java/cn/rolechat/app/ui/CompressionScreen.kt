package cn.rolechat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.app.AppActions
import cn.rolechat.app.CompressionStatus
import cn.rolechat.app.RuntimeUi
import cn.rolechat.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompressionScreen(room: ChatRoom, state: AppState, runtime: RuntimeUi,
    actions: AppActions, onBack: () -> Unit) {
    val preview = runtime.compressions[room.id]
    var settings by rememberSaveable(room.id, stateSaver = serializedSaver(CompressionSettings.serializer())) {
        mutableStateOf(preview?.settings ?: room.compression)
    }
    var advanced by rememberSaveable(room.id) { mutableStateOf(false) }
    var showModels by rememberSaveable(room.id) { mutableStateOf(false) }
    var showExisting by rememberSaveable(room.id) { mutableStateOf(false) }
    var confirmRestore by rememberSaveable(room.id) { mutableStateOf(false) }
    var selectedDraft by rememberSaveable(preview?.id) { mutableStateOf<String?>(null) }
    var selectedSummary by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var edits by rememberSaveable(preview?.id, stateSaver = listSaver<Map<String, String>, String>(
        save = { values -> values.flatMap { listOf(it.key, it.value) } },
        restore = { values -> values.chunked(2).associate { it[0] to it[1] } }
    )) { mutableStateOf(emptyMap<String, String>()) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focus.clearFocus(force = true); keyboard?.hide() }
    fun leave() { dismissKeyboard(); actions.cancelCompression(room.id); onBack() }
    BackHandler(onBack = ::leave)
    val generating = preview?.status == CompressionStatus.GENERATING
    val ready = preview?.status == CompressionStatus.READY
    val busy = room.id in runtime.busyRooms
    val provider = state.providers.find { it.id == settings.model.providerId }
    val loadingModels = settings.model.providerId in runtime.loadingModels
    val planResult = remember(state, room.id, settings) { runCatching { buildCompressionPlans(state, room.id, settings) } }
    val plans = planResult.getOrDefault(emptyList())
    val generateProblem = when {
        busy -> "本会话正在回复，请等本轮结束后再整理。"
        planResult.isFailure -> planResult.exceptionOrNull()?.message ?: "请检查前情整理配置。"
        provider?.id !in runtime.configuredProviders -> "请先在设置中为所选模型服务填写 API 密钥。"
        plans.isEmpty() -> "没有可压缩的旧消息。可减少保留条数，或继续聊天后再来。"
        else -> null
    }
    val drafts = preview?.drafts.orEmpty()
    val texts = drafts.associate { it.characterId to (edits[it.characterId] ?: it.text) }
    val draftsValid = drafts.isNotEmpty() && texts.values.all { it.isNotBlank() && it.length <= 32768 }
    val summaries = state.contextSummaries.asReversed().filter { it.roomId == room.id }.distinctBy { it.characterId }

    Scaffold(modifier = Modifier.fillMaxSize().imePadding().testTag("compression-screen"), containerColor = Paper,
        topBar = {
            TopAppBar(title = { Text("手动压缩", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel, titleContentColor = Ink, navigationIconContentColor = Ink))
        }, bottomBar = {
            Surface(color = Panel) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (ready && !draftsValid) Text("每位角色的前情不能为空，且不能超过 32768 个字符。", color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 7.dp).testTag("compression-draft-error"))
                    if (ready && busy) Text("本会话正在回复，请等本轮结束后确认。", color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = ::leave, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("compression-back"),
                            shape = RoundedCornerShape(10.dp)) { Text("返回") }
                        if (generating) {
                            Button(onClick = { dismissKeyboard(); actions.cancelCompression(room.id) },
                                modifier = Modifier.weight(2f).heightIn(min = 48.dp).testTag("cancel-compression"), shape = RoundedCornerShape(10.dp)) {
                                Text("取消生成")
                            }
                        } else if (ready) {
                            Button(onClick = {
                                dismissKeyboard(); showExisting = true
                                preview?.let { actions.applyCompression(room.id, it.id, texts) }
                            }, enabled = draftsValid && !busy, modifier = Modifier.weight(2f).heightIn(min = 48.dp)
                                .testTag("apply-compression").semantics { contentDescription = "确认使用前情" }, shape = RoundedCornerShape(10.dp)) {
                                Text("确认使用")
                            }
                        } else {
                            Button(onClick = { dismissKeyboard(); actions.generateCompression(room.id, settings) }, enabled = generateProblem == null,
                                modifier = Modifier.weight(2f).heightIn(min = 48.dp).testTag("generate-compression"), shape = RoundedCornerShape(10.dp)) {
                                Text(if (preview?.status == CompressionStatus.ERROR) "重新生成前情" else "生成前情草稿")
                            }
                        }
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(room.name, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (generating) {
                FormCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在生成前情 · ${preview?.completed ?: 0}/${preview?.total ?: 0} 位角色", color = Ink, fontSize = 15.sp,
                            modifier = Modifier.padding(start = 12.dp).testTag("compression-progress"))
                    }
                    Text("按每位角色可见的历史分别整理。返回或取消会停止生成，已生成内容也不会自动生效。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                }
            } else if (ready) {
                FormCard {
                    Text("前情草稿 · 尚未使用", color = Sage, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("检查并编辑各角色的前情，点击底部「确认使用」才会替换后续请求中的旧上下文。原聊天记录保留。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    val selected = drafts.find { it.characterId == selectedDraft } ?: drafts.firstOrNull()
                    if (selected != null) {
                        SelectField("查看角色", selected.characterName, drafts.map { it.characterName }, Modifier.testTag("compression-draft-role")) {
                            dismissKeyboard(); selectedDraft = drafts[it].characterId
                        }
                        Text("整理 ${selected.sourceCount} 条旧消息 · 保留最近 ${selected.recentCount} 条原文", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(value = texts.getValue(selected.characterId), onValueChange = { edits = edits + (selected.characterId to it) },
                            label = { Text("前情回顾") }, minLines = 7, maxLines = 16, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("compression-draft-${selected.characterId}"))
                    }
                    TextButton(onClick = { dismissKeyboard(); actions.cancelCompression(room.id) }, modifier = Modifier.align(Alignment.End)
                        .testTag("revise-compression-settings")) { Text("调整配置并重新生成") }
                }
            }
            if (preview?.status == CompressionStatus.ERROR) {
                FormCard {
                    Text("未应用前情", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(preview.error.ifBlank { "生成失败，请检查配置后重试。" }, color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("compression-error"))
                    Text("原上下文保持原样。", color = Muted, fontSize = 12.sp)
                }
            }
            if (!generating && !ready) {
                FormCard {
                    NumberField("保留最近消息条数", settings.keepRecentMessages, Modifier.fillMaxWidth().testTag("compression-keep-recent"), minimum = 1) {
                        settings = settings.copy(keepRecentMessages = it)
                    }
                    Text("每位角色保留最近 1–200 条可见消息，其余旧消息整理为前情。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    if (plans.isNotEmpty()) {
                        val source = plans.flatMap { it.sourceMessages }.distinctBy { it.id }.sortedBy { it.createdAt }
                        Text("可整理 ${plans.size} 位角色 · ${source.size} 条旧消息", color = Ink, fontSize = 14.sp,
                            modifier = Modifier.testTag("compression-range"))
                        Text("范围：${compressionTime(source.first().createdAt)} — ${compressionTime(source.last().createdAt)}", color = Muted, fontSize = 12.sp)
                        Text(plans.joinToString("；") { "${it.characterName} ${it.sourceMessages.size} 条" }, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Text("按角色可见历史分别生成，可能产生多次模型调用费用。原文保留，确认前不会改变聊天上下文。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                }
                FormCard {
                    Text("前情整理模型", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    SelectField("模型服务", provider?.name ?: "请选择模型服务", state.providers.map { it.name }, Modifier.testTag("compression-provider")) {
                        settings = settings.copy(model = settings.model.copy(providerId = state.providers[it].id))
                    }
                    FormField("模型名称", settings.model.model, { settings = settings.copy(model = settings.model.copy(model = it)) },
                        modifier = Modifier.testTag("compression-model"), hint = "独立于角色的聊天模型，可直接填写")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dismissKeyboard(); showModels = true }, modifier = Modifier.weight(1f).testTag("choose-compression-model"),
                            shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("从列表选择", fontSize = 12.sp) }
                        TextButton(onClick = { dismissKeyboard(); actions.refreshModels(settings.model.providerId) }, enabled = provider != null && !loadingModels,
                            modifier = Modifier.weight(1f).testTag("refresh-compression-models"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                            if (loadingModels) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp)); Text(if (loadingModels) "正在获取" else "刷新模型列表", fontSize = 12.sp)
                        }
                    }
                }
                FormCard {
                    Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.testTag("compression-advanced").padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("压缩提示词 · 高级", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Muted)
                    }
                    if (advanced) {
                        FormField("压缩提示词", settings.systemPrompt, { settings = settings.copy(systemPrompt = it) },
                            multiline = true, modifier = Modifier.testTag("compression-prompt"))
                        TextButton(onClick = { settings = settings.copy(systemPrompt = DEFAULT_COMPRESSION_PROMPT) },
                            modifier = Modifier.align(Alignment.End).testTag("reset-compression-prompt")) { Text("恢复默认提示词") }
                    }
                }
                if (generateProblem != null) Text(generateProblem, color = Muted, fontSize = 12.sp, lineHeight = 19.sp,
                    modifier = Modifier.testTag("compression-unavailable"))
            }
            if (summaries.isNotEmpty()) {
                FormCard {
                    Row(Modifier.fillMaxWidth().clickable { showExisting = !showExisting }.testTag("existing-compression"), verticalAlignment = Alignment.CenterVertically) {
                        Text("已保存前情 · ${summaries.size} 位角色", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Icon(if (showExisting) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Muted)
                    }
                    if (showExisting) {
                        val selected = summaries.find { it.characterId == selectedSummary } ?: summaries.first()
                        fun summaryName(summary: ContextSummary) = state.characters.find { it.id == summary.characterId }?.name ?: "已删除的角色"
                        SelectField("角色前情", summaryName(selected), summaries.map(::summaryName), Modifier.testTag("existing-compression-role")) {
                            selectedSummary = summaries[it].characterId
                        }
                        val actor = state.characters.find { it.id == selected.characterId }
                        val member = room.members.find { it.characterId == selected.characterId }
                        val visible = state.messages.filter { it.roomId == room.id && it.text.isNotBlank() &&
                            it.status in setOf(MessageStatus.COMPLETE, MessageStatus.INTERRUPTED, MessageStatus.ERROR) &&
                            member != null && (member.readHistory || it.createdAt >= member.joinedAt) }
                        val active = actor != null && validContextSummary(state, room, actor, visible)?.id == selected.id
                        Text(if (active) "正在使用 · 覆盖 ${selected.sourceMessageIds.size} 条旧消息" else "未生效 · 角色或历史已改变",
                            color = if (active) Sage else Muted, fontSize = 12.sp, modifier = Modifier.testTag("existing-compression-status"))
                        SelectionContainer { Text(selected.text, color = Ink, fontSize = 14.sp, lineHeight = 22.sp,
                            modifier = Modifier.testTag("existing-compression-text")) }
                    }
                    OutlinedButton(onClick = { dismissKeyboard(); confirmRestore = true }, enabled = !generating && !busy,
                        modifier = Modifier.fillMaxWidth().testTag("clear-compression"), shape = RoundedCornerShape(10.dp)) {
                        Text("恢复完整上下文")
                    }
                }
            }
        }
    }
    if (showModels) ModelPicker(runtime.modelLists[settings.model.providerId].orEmpty(), { showModels = false }) {
        settings = settings.copy(model = settings.model.copy(model = it)); showModels = false
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false }, containerColor = Panel,
        title = { Text("恢复完整上下文？") }, text = { Text("清除本会话已使用的前情回顾，后续回复重新读取各角色有权限查看的完整聊天记录。原聊天记录不会删除。") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; actions.clearCompression(room.id) },
            modifier = Modifier.testTag("confirm-clear-compression")) { Text("恢复完整上下文") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } })
}

private fun compressionTime(time: Long) = SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(time))
