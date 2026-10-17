package cn.rolechat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.app.RuntimeUi
import cn.rolechat.app.speech.DoubaoVoices
import cn.rolechat.core.*
import cn.rolechat.core.Character

internal enum class CharacterSection { PERSONA, MODELS, BEHAVIOR, VOICE }

@Composable internal fun CharacterEditor(initial: Character, state: AppState, runtime: RuntimeUi, onBack: () -> Unit,
    onSave: (Character) -> Unit, onDelete: () -> Unit, onPreviewVoice: (Character) -> Unit, onStopAudio: () -> Unit,
    onRefreshDoubaoVoices: () -> Unit, onSaveVoice: (String) -> Boolean, initialSection: CharacterSection = CharacterSection.PERSONA) {
    var character by rememberSaveable(initial.id, stateSaver = serializedSaver(Character.serializer())) { mutableStateOf(initial) }
    var avatarLoading by remember(initial.id) { mutableStateOf(false) }
    var section by rememberSaveable(initial.id, initialSection) { mutableStateOf(initialSection) }
    var aAdvanced by rememberSaveable(initial.id) { mutableStateOf(false) }
    var bAdvanced by rememberSaveable(initial.id) { mutableStateOf(false) }
    var timeoutsExpanded by rememberSaveable(initial.id) { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var didPreview by remember(initial.id) { mutableStateOf(false) }
    var showVoicePicker by rememberSaveable(initial.id) { mutableStateOf(false) }
    var voiceSaveError by remember(initial.id) { mutableStateOf<String?>(null) }
    val stopAudio by rememberUpdatedState(onStopAudio)
    DisposableEffect(initial.id) {
        onDispose { if (didPreview) stopAudio() }
    }
    val scrollStates = CharacterSection.entries.map { rememberScrollState() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val doubaoVoiceError = if (state.tts.engine == TtsEngine.DOUBAO)
        DoubaoVoices.validationError(state.tts.resourceId, character.voice, runtime.doubaoVoices) else null
    fun previewVoice(draft: Character) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        didPreview = true
        onPreviewVoice(draft)
    }
    fun stopPreview() { stopAudio(); didPreview = false }
    fun openVoicePicker() { focusManager.clearFocus(); keyboard?.hide(); voiceSaveError = null; showVoicePicker = true }
    val savedCharacter = state.characters.find { it.id == initial.id }
    val exists = savedCharacter != null
    val validation = when {
        character.name.isBlank() || character.prompt.isBlank() -> "请在「人设」填写名称和角色提示词"
        character.primary.model.isBlank() || state.providers.none { it.id == character.primary.providerId } || character.primary.maxTokens <= 0 -> "请检查「模型」中的模型 A 配置"
        character.dualModel && (character.secondary.model.isBlank() || state.providers.none { it.id == character.secondary.providerId } || character.secondary.maxTokens <= 0) -> "请检查「模型」中的模型 B 配置"
        character.dualModel && (character.cutMin <= 0 || character.cutMax < character.cutMin) -> "请检查「模型」中的截断长度范围"
        character.firstTextTimeoutSec <= 0 || character.stallTimeoutSec <= 0 -> "请检查「模型」中的超时设置"
        else -> null
    }
    EditorScaffold(if (exists) "编辑角色" else if (character.messageStyle == MessageStyle.SCENE) "创建场景提示" else "创建角色", onBack, {
        onSave(character.copy(name = character.name.trim(), primary = character.primary.copy(model = character.primary.model.trim()), secondary = character.secondary.copy(model = character.secondary.model.trim(),
            thinking = if (character.secondaryOutputSource == ReplyOutputSource.REASONING) ThinkingMode.ON else character.secondary.thinking),
            siliconModel = character.siliconModel.trim(), siliconVoice = character.siliconVoice.trim()))
    }, validation == null && !avatarLoading) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            TabRow(selectedTabIndex = section.ordinal, containerColor = Panel, contentColor = Sage,
                divider = { HorizontalDivider(color = Line) }) {
                listOf("人设", "模型", "行为", "声音").forEachIndexed { index, title ->
                    Tab(selected = section.ordinal == index, onClick = { focusManager.clearFocus(); keyboard?.hide(); section = CharacterSection.entries[index] },
                        text = { Text(title, fontSize = 14.sp, fontWeight = if (section.ordinal == index) FontWeight.SemiBold else FontWeight.Normal) },
                        selectedContentColor = Sage, unselectedContentColor = Muted)
                }
            }
            if (validation != null) Text(validation, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollStates[section.ordinal]).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (section) {
                    CharacterSection.PERSONA -> {
                        FormCard {
                            FormField("角色名称", character.name, { character = character.copy(name = it) })
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(character, size = 48, modifier = Modifier.testTag("character-avatar-preview"))
                                FormField("头像文字或表情", character.avatar, { character = character.copy(avatar = it) }, modifier = Modifier.weight(1f))
                            }
                            AvatarPhotoPicker(character.avatarImage, { character = character.copy(avatarImage = it) },
                                testTagPrefix = "character-avatar", onLoadingChange = { avatarLoading = it })
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AvatarColors.forEach { color ->
                                    Box(Modifier.size(28.dp).background(Color(color), CircleShape).clickable { character = character.copy(color = color) }, contentAlignment = Alignment.Center) {
                                        if (character.color == color) Icon(Icons.Outlined.Check, "已选颜色", tint = AvatarInk, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        FormCard {
                            Text("消息样式", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            ChoiceChips(listOf("角色对话", "场景提示"), if (character.messageStyle == MessageStyle.SCENE) 1 else 0) {
                                character = character.copy(messageStyle = if (it == 1) MessageStyle.SCENE else MessageStyle.CHAT)
                            }
                            Text(if (character.messageStyle == MessageStyle.SCENE) "以居中的场景提示显示，人设、模型和回复规则照常生效。" else "以角色头像、署名和聊天气泡显示。",
                                color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                        }
                        FormCard {
                            FormField("角色提示词", character.prompt, { character = character.copy(prompt = it) }, multiline = true)
                            WorldSelector(state.worlds, character.worldId) { character = character.copy(worldId = it) }
                            Text("会话选择世界观时优先使用会话世界观；否则使用角色世界观", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        Text("角色提示词仅发送给这个角色，聊天记忆按会话隔离。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                        if (exists) OutlinedButton(onClick = { delete = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除角色") }
                    }
                    CharacterSection.MODELS -> {
                        FormCard {
                            ToggleRow("双模型接续", "A 写开头，B 接着生成", character.dualModel) { character = character.copy(dualModel = it) }
                        }
                        FormCard {
                            ModelSection(if (character.dualModel) "模型 A · 开头" else "回复模型", character.primary, state.providers, runtime,
                                aAdvanced, { aAdvanced = !aAdvanced }) { character = character.copy(primary = it) }
                        }
                        if (character.dualModel) {
                            FormCard {
                                val compatibleOutput = character.secondaryOutputSource == ReplyOutputSource.REASONING
                                Text("模型 B · 接续", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
                                SelectField("续写输出来源", if (compatibleOutput) "思考字段兼容" else "正文", listOf("正文", "思考字段兼容"),
                                    modifier = Modifier.testTag("secondary-output-source")) { index ->
                                    character = character.copy(secondaryOutputSource = if (index == 1) ReplyOutputSource.REASONING else ReplyOutputSource.CONTENT,
                                        secondary = if (index == 1) character.secondary.copy(thinking = ThinkingMode.ON) else character.secondary)
                                }
                                if (compatibleOutput) Text("仅用于接口把正文放在思考字段的模型。强制开启 B 的思考，将该字段作为正文接收，不另存为 COT。",
                                    color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                                ModelSection("", character.secondary, state.providers, runtime,
                                    bAdvanced, { bAdvanced = !bAdvanced }, forceThinking = compatibleOutput) {
                                    character = character.copy(secondary = if (compatibleOutput) it.copy(thinking = ThinkingMode.ON) else it)
                                }
                            }
                            FormCard {
                                Text("A 的截断长度", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    NumberField("最少字符", character.cutMin, Modifier.weight(1f), minimum = 1) { character = character.copy(cutMin = it) }
                                    NumberField("最多字符", character.cutMax, Modifier.weight(1f), minimum = character.cutMin.coerceAtLeast(1)) { character = character.copy(cutMax = it) }
                                }
                                Text("仅计算正文；A 是否思考，都按此规则接续。", color = Muted, fontSize = 12.sp)
                            }
                        }
                        FormCard {
                            ExpansionRow("超时设置", "首段 ${character.firstTextTimeoutSec} 秒 · 停滞 ${character.stallTimeoutSec} 秒", timeoutsExpanded) { timeoutsExpanded = !timeoutsExpanded }
                            if (timeoutsExpanded) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    NumberField("首段正文 / 秒", character.firstTextTimeoutSec, Modifier.weight(1f), minimum = 1) { character = character.copy(firstTextTimeoutSec = it) }
                                    NumberField("输出停滞 / 秒", character.stallTimeoutSec, Modifier.weight(1f), minimum = 1) { character = character.copy(stallTimeoutSec = it) }
                                }
                                Text(if (character.dualModel) "A 超时自动切换 B；无正文时，B 从头回答。" else "超时结束等待，保留已收到的内容。", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                    CharacterSection.BEHAVIOR -> {
                        FormCard {
                            IntSlider("普通消息参与概率", character.probability, 0..100, "%") { character = character.copy(probability = it) }
                            IntSlider("发言冷却", character.cooldownSec, 0..120, " 秒") { character = character.copy(cooldownSec = it) }
                        }
                        FormCard {
                            ToggleRow("参与角色互聊", "群聊开启互聊后，可接其他角色的话", character.proactive) { character = character.copy(proactive = it) }
                            IntSlider("每轮最多发言", character.maxRepliesPerRound, 1..8, " 次") { character = character.copy(maxRepliesPerRound = it) }
                        }
                        Text("群内可单独覆盖参与概率。被 @ 时优先回复，绕过概率与冷却。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                    CharacterSection.VOICE -> {
                        if (state.tts.engine == TtsEngine.SILICONFLOW) {
                            Text("当前语音服务：硅基流动", color = Muted, fontSize = 12.sp)
                            FormCard {
                                FormField("语音模型覆盖", character.siliconModel, { character = character.copy(siliconModel = it) },
                                    hint = "留空使用默认：${state.tts.siliconModel}")
                                TextButton(onClick = { character = character.copy(siliconModel = SiliconSpeechPresetModel) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                    Text("使用 CosyVoice2", fontSize = 12.sp)
                                }
                            }
                            FormCard {
                                val presetChoices = listOf("使用全局默认（${state.tts.siliconVoice}）") + SiliconVoicePresets
                                val currentChoice = if (character.siliconVoice.isBlank()) presetChoices.first() else character.siliconVoice.takeIf { it in SiliconVoicePresets } ?: "自定义音色"
                                SelectField("预置音色", currentChoice, presetChoices) { index ->
                                    character = character.copy(siliconVoice = if (index == 0) "" else SiliconVoicePresets[index - 1])
                                }
                                FormField("音色覆盖", character.siliconVoice, { character = character.copy(siliconVoice = it) },
                                    hint = "留空使用默认：${state.tts.siliconVoice}；也可填完整音色 ID")
                            }
                            TextButton(onClick = { character = character.copy(siliconModel = "", siliconVoice = "") }, modifier = Modifier.align(Alignment.End)) { Text("恢复全局默认") }
                            Text("只覆盖这个角色的声音。A 和 B 共用音色，语速沿用全局设置。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                        } else {
                            Text("当前语音服务：火山引擎", color = Muted, fontSize = 12.sp)
                            FormCard {
                                Text("当前资源：${DoubaoVoices.resourceVersion(state.tts.resourceId)?.let { "语音合成 $it" } ?: state.tts.resourceId}",
                                    color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("doubao-active-resource"))
                                FormField("豆包音色 ID", character.voice, { character = character.copy(voice = it) })
                                if (savedCharacter != null) {
                                    Text("已保存音色：${savedCharacter.voice.ifBlank { "未设置" }}", color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                                        modifier = Modifier.testTag("saved-doubao-voice"))
                                    Text(if (character.voice.isBlank() && savedCharacter.voice.isBlank()) "尚未设置音色。"
                                        else if (character.voice.trim() == savedCharacter.voice.trim()) "当前音色已保存。"
                                        else "当前输入尚未保存；试听不会保存音色。手填音色需点击顶部「保存」后生效。",
                                        color = if (savedCharacter.voice.isNotBlank() && character.voice.trim() == savedCharacter.voice.trim()) Sage else Muted, fontSize = 12.sp, lineHeight = 18.sp,
                                        modifier = Modifier.testTag("doubao-voice-draft-status"))
                                } else Text("此角色尚未创建，选择音色后仍需点击顶部「保存」。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.testTag("doubao-voice-draft-status"))
                                OutlinedButton(onClick = { openVoicePicker() },
                                    modifier = Modifier.fillMaxWidth().testTag("choose-doubao-voice"), shape = RoundedCornerShape(10.dp)) { Text("选择音色") }
                                if (doubaoVoiceError != null) {
                                    Text(doubaoVoiceError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 19.sp,
                                        modifier = Modifier.testTag("doubao-voice-validation-error"))
                                    TextButton(onClick = { openVoicePicker() },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("选择匹配音色", fontSize = 12.sp) }
                                }
                            }
                            Text("先在设置中配置语音服务，再到会话设置开启自动朗读。A 和 B 共用这个音色。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                        }
                        FormCard {
                            Text("试听内容", color = Muted, fontSize = 12.sp)
                            Text("你好，我是${character.name.trim().ifBlank { character.id }}，很高兴见到你。",
                                color = Ink, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag("character-voice-preview-text"))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = {
                                    previewVoice(character)
                                }, enabled = character.name.isNotBlank() && (state.tts.engine != TtsEngine.DOUBAO || (character.voice.isNotBlank() && doubaoVoiceError == null)),
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("preview-character-voice"), shape = RoundedCornerShape(10.dp)) {
                                    Text("试听")
                                }
                                OutlinedButton(onClick = { stopPreview() }, enabled = didPreview,
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("stop-character-voice-preview"), shape = RoundedCornerShape(10.dp)) {
                                    Text("停止试听")
                                }
                            }
                            Text("使用当前填写的音色，不会保存角色设置。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                            if (runtime.audioStatus.isNotBlank()) Text(runtime.audioStatus, color = Muted, fontSize = 12.sp, lineHeight = 19.sp,
                                modifier = Modifier.testTag("character-voice-preview-status"))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (showVoicePicker) DoubaoVoicePicker(state.tts.resourceId, character.copy(voice = savedCharacter?.voice ?: character.voice), runtime, canStopPreview = didPreview,
        onDismiss = { showVoicePicker = false }, onRefresh = onRefreshDoubaoVoices, onPreview = { voice -> previewVoice(character.copy(voice = voice.id)) },
        onStopPreview = { stopPreview() }, selectionLabel = if (exists) "使用并保存" else "选择",
        selectionDescription = voiceSaveError ?: if (exists) "试听不会选用音色。「使用并保存」只更新角色音色，其他草稿仍需点击顶部「保存」。"
            else "试听不会选用音色。「选择」只填入草稿，创建角色仍需点击顶部「保存」。",
        onSelect = { voice ->
            if (!exists || onSaveVoice(voice.id)) {
                character = character.copy(voice = voice.id)
                voiceSaveError = null
                showVoicePicker = false
            } else voiceSaveError = "音色未保存，请检查资源版本与音色配置后重试。试听不会选用或保存音色。"
        })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除 ${initial.name}？") },
        text = { Text("这个角色会从群成员中移除，已有聊天消息会保留。") },
        confirmButton = { TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@Composable private fun ExpansionRow(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起$title" else "展开$title", tint = Muted, modifier = Modifier.size(21.dp))
    }
}

@Composable internal fun NumberField(label: String, value: Int, modifier: Modifier = Modifier, minimum: Int = 0, onChange: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { raw -> if (raw.length <= 8 && raw.all { it.isDigit() }) { text = raw; onChange(raw.toIntOrNull() ?: 0) } }, modifier,
        label = { Text(label, fontSize = 12.sp) }, singleLine = true, isError = text.isBlank() || value < minimum,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
}

@Composable internal fun WorldSelector(worlds: List<World>, selected: String?, emptyLabel: String = "不使用世界观", onChange: (String?) -> Unit) {
    SelectField("世界观", worlds.find { it.id == selected }?.name ?: emptyLabel, listOf(emptyLabel) + worlds.map { it.name }) { index -> onChange(if (index == 0) null else worlds[index - 1].id) }
}

@Composable internal fun SelectField(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp)) {
                Text(value, Modifier.weight(1f), color = Ink, maxLines = 2); Icon(Icons.Outlined.ExpandMore, "选择")
            }
            DropdownMenu(expanded, { expanded = false }) { options.forEachIndexed { index, item -> DropdownMenuItem(text = { Text(item) }, onClick = { onSelect(index); expanded = false }) } }
        }
    }
}

@Composable private fun ModelSection(title: String, config: ModelConfig, providers: List<Provider>, runtime: RuntimeUi,
    expanded: Boolean, onExpand: () -> Unit, forceThinking: Boolean = false, onChange: (ModelConfig) -> Unit) {
    if (title.isNotBlank()) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
    SelectField("模型服务", providers.find { it.id == config.providerId }?.name ?: "请选择模型服务", providers.map { it.name }) { onChange(config.copy(providerId = providers[it].id)) }
    var showModels by remember { mutableStateOf(false) }
    OutlinedTextField(config.model, { onChange(config.copy(model = it)) }, Modifier.fillMaxWidth(), label = { Text("模型名称") },
        singleLine = true, shape = RoundedCornerShape(10.dp),
        trailingIcon = { IconButton(onClick = { showModels = true }) { Icon(Icons.Outlined.Search, "选择模型") } })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("思考", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        if (forceThinking) Text("开启 · 兼容模式固定", color = Sage, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 9.dp).testTag("b-thinking-forced"))
        else Box(Modifier.weight(1f)) { ChoiceChips(listOf("默认", "开启", "关闭"), config.thinking.ordinal) { onChange(config.copy(thinking = ThinkingMode.entries[it])) } }
    }
    ExpansionRow("高级参数", "${config.maxTokens} Token · 随机性 ${String.format(java.util.Locale.US, "%.1f", config.temperature)}", expanded, onExpand)
    if (expanded) {
        NumberField("最大输出 Token", config.maxTokens, Modifier.fillMaxWidth(), minimum = 1) { onChange(config.copy(maxTokens = it)) }
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("随机性", fontSize = 13.sp, color = Muted)
                Text(String.format(java.util.Locale.US, "%.1f", config.temperature), fontSize = 13.sp, color = Sage)
            }
            Slider(config.temperature.toFloat(), { onChange(config.copy(temperature = (it * 10).toInt() / 10.0)) }, valueRange = 0f..2f, steps = 19)
        }
    }
    if (showModels) ModelPicker(runtime.modelLists[config.providerId].orEmpty(), onDismiss = { showModels = false }) { onChange(config.copy(model = it)); showModels = false }
}

@Composable internal fun ModelPicker(models: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择模型") }, text = {
        Column {
            FormField("搜索模型", search, { search = it })
            if (models.isEmpty()) Text("还没有获取模型列表。可在模型服务设置中刷新，也可以直接输入模型名称。", color = Muted, modifier = Modifier.padding(top = 15.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                items(models.filter { it.contains(search, ignoreCase = true) }) { name ->
                    Text(name, modifier = Modifier.fillMaxWidth().clickable { onSelect(name) }.padding(vertical = 15.dp), fontSize = 13.sp)
                    HorizontalDivider(color = Line)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("关闭") } })
}
