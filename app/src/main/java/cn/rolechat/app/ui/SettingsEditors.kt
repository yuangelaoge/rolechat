package cn.rolechat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.app.AppActions
import cn.rolechat.app.RuntimeUi
import cn.rolechat.app.data.serviceOrigin
import cn.rolechat.app.speech.DoubaoVoices
import cn.rolechat.core.*

@Composable internal fun ProviderEditor(initial: Provider, runtime: RuntimeUi, actions: AppActions, onBack: () -> Unit,
    onSave: (Provider, String?) -> Unit) {
    var provider by rememberSaveable(initial.id, stateSaver = serializedSaver(Provider.serializer())) { mutableStateOf(initial) }
    var key by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var advanced by rememberSaveable(initial.id) { mutableStateOf(false) }
    val origin = runCatching { serviceOrigin(provider.baseUrl) }.getOrNull()
    val initialOrigin = remember(initial.baseUrl) { runCatching { serviceOrigin(initial.baseUrl) }.getOrNull() }
    val valid = provider.name.isNotBlank() && origin != null
    val configured = provider.id in runtime.configuredProviders
    val savedKeyHere = configured && origin != null && origin == initialOrigin
    val loading = provider.id in runtime.loadingModels
    val count = runtime.modelLists[provider.id]?.size ?: 0
    fun normalized() = provider.copy(name = provider.name.trim(), baseUrl = provider.baseUrl.trim().trimEnd('/'))
    EditorScaffold("模型服务", onBack, { onSave(normalized(), key.trim().takeIf { it.isNotBlank() }) }, valid) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionLabel("连接信息", if (savedKeyHere) "密钥已保存，可直接获取模型" else "填写服务地址和密钥，即可开始使用")
            FormCard {
                FormField("服务名称", provider.name, { provider = provider.copy(name = it) })
                FormField("API Base URL", provider.baseUrl, { provider = provider.copy(baseUrl = it) }, hint = "例如 https://api.siliconflow.cn/v1", keyboardType = KeyboardType.Uri)
                SecretField("API 密钥", key, { key = it }, if (savedKeyHere) "已保存，留空保留原密钥" else "填写当前服务商提供的 API 密钥")
            }
            SectionLabel("模型列表")
            FormCard {
                Button(onClick = {
                    actions.saveProvider(normalized(), key.trim().takeIf { it.isNotBlank() })
                    actions.refreshModels(provider.id)
                }, enabled = valid && (savedKeyHere || key.isNotBlank()) && !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(17.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (loading) "正在获取…" else "保存连接并获取模型")
                }
                if (count > 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("已获取 $count 个模型", color = Muted, fontSize = 13.sp)
                        TextButton(onClick = { showModels = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("查看 $count 个模型") }
                    }
                } else Text("也可在角色设置中直接填写模型名称。", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            SettingsDisclosure("双模型续写参数", "高级选项 · 仅影响模型 B 的接续", advanced, { advanced = !advanced }) {
                SelectField("前缀提交方式", when (provider.prefixMode) {
                    PrefixMode.SILICONFLOW -> "硅基流动 · prefix"
                    PrefixMode.ASSISTANT_FLAG -> "Assistant · prefix 标记"
                    PrefixMode.ASSISTANT -> "末尾 Assistant 消息"
                }, listOf("硅基流动 · prefix", "Assistant · prefix 标记", "末尾 Assistant 消息")) { provider = provider.copy(prefixMode = PrefixMode.entries[it]) }
                ToggleRow("响应包含输入前缀", "仅在接口会原样回显前缀时开启，用于移除重复部分", provider.echoPrefix) { provider = provider.copy(echoPrefix = it) }
                Text("模型 B 会收到已有正文并继续生成。请按服务商接口选择提交方式，模型可自由填写。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (!valid) Text("请填写服务名称和有效的 HTTPS 地址。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (showModels) ModelPicker(runtime.modelLists[provider.id].orEmpty(), { showModels = false }) { showModels = false }
}

@Composable internal fun WorldEditor(initial: World, onBack: () -> Unit, onSave: (World) -> Unit) {
    var world by rememberSaveable(initial.id, stateSaver = serializedSaver(World.serializer())) { mutableStateOf(initial) }
    EditorScaffold("世界观", onBack, { onSave(world.copy(name = world.name.trim(), prompt = world.prompt.trim())) }, world.name.isNotBlank() && world.prompt.isNotBlank()) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionLabel("共享设定", "写清共同背景、人物关系和需要遵守的规则")
            FormCard {
                FormField("世界观名称", world.name, { world = world.copy(name = it) })
                OutlinedTextField(world.prompt, { world = world.copy(prompt = it) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("世界观提示词") }, minLines = 7, maxLines = 14,
                    shape = RoundedCornerShape(10.dp), supportingText = {
                        Text("${world.prompt.codePointCount(0, world.prompt.length)} 字符", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    })
            }
            Text("此设定会与角色提示词一起生效。多个会话可以共用世界观，聊天记录仍按会话隔离。", color = Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 2.dp, vertical = 12.dp))
        }
    }
}

internal val SiliconVoicePresets = listOf("alex", "benjamin", "charles", "david", "anna", "bella", "claire", "diana")
internal const val SiliconSpeechPresetModel = "FunAudioLLM/CosyVoice2-0.5B"

@Composable internal fun TtsEditor(initial: TtsSettings, state: AppState, runtime: RuntimeUi, onBack: () -> Unit,
    onSave: (TtsSettings, String?, String?, String?, String?) -> Unit) {
    var settings by rememberSaveable(stateSaver = serializedSaver(TtsSettings.serializer())) { mutableStateOf(initial) }
    var legacy by rememberSaveable { mutableStateOf(initial.appId.isNotBlank()) }
    var apiKey by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var voiceAccessKey by remember { mutableStateOf("") }
    var voiceSecretKey by remember { mutableStateOf("") }
    var voiceCatalogExpanded by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(initial.endpoint != "wss://openspeech.bytedance.com/api/v3/tts/bidirection") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var showExtractionModels by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    fun openLink(url: String) {
        linkError = null
        runCatching { uriHandler.openUri(url) }.onFailure { linkError = "暂时无法打开浏览器，请检查设备是否已安装浏览器。" }
    }
    val siliconFlow = settings.engine == TtsEngine.SILICONFLOW
    val siliconProvider = state.providers.find { it.id == settings.siliconProviderId }
    val siliconKeySaved = settings.siliconProviderId in runtime.configuredProviders
    val extraction = settings.dialogueExtraction
    val extractionProvider = state.providers.find { it.id == extraction.model.providerId }
    val extractionKeySaved = extraction.model.providerId in runtime.configuredProviders
    fun updateExtraction(change: (DialogueExtractionSettings) -> DialogueExtractionSettings) {
        settings = settings.copy(dialogueExtraction = change(settings.dialogueExtraction))
    }
    val endpointValid = runCatching { serviceOrigin(settings.endpoint, webSocket = true) }.isSuccess
    val speechValid = if (siliconFlow) {
        siliconProvider != null && settings.siliconModel.isNotBlank() && settings.siliconVoice.isNotBlank() &&
            settings.siliconSpeed.isFinite() && settings.siliconSpeed in 0.25..4.0
    } else endpointValid && (!settings.enabled || (settings.resourceId.isNotBlank() && (!legacy || settings.appId.isNotBlank())))
    val extractionValid = !extraction.enabled || (extractionProvider != null && extraction.model.model.isNotBlank() && extraction.systemPrompt.isNotBlank() && extraction.systemPrompt.length <= 32768)
    val catalogKeysValid = (voiceAccessKey.isBlank() && voiceSecretKey.isBlank()) || (voiceAccessKey.isNotBlank() && voiceSecretKey.isNotBlank())
    val valid = speechValid && extractionValid && catalogKeysValid
    EditorScaffold("语音服务", onBack, {
        onSave(settings.copy(appId = if (legacy) settings.appId.trim() else "", resourceId = settings.resourceId.trim(), endpoint = settings.endpoint.trim(),
            siliconProviderId = settings.siliconProviderId.trim(), siliconModel = settings.siliconModel.trim(), siliconVoice = settings.siliconVoice.trim(),
            dialogueExtraction = extraction.copy(model = extraction.model.copy(model = extraction.model.model.trim()), systemPrompt = extraction.systemPrompt.trim())),
            apiKey.trim().takeIf { it.isNotBlank() }, token.trim().takeIf { it.isNotBlank() },
            voiceAccessKey.trim().takeIf { it.isNotBlank() }, voiceSecretKey.trim().takeIf { it.isNotBlank() })
    }, valid) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionLabel("语音引擎")
            FormCard {
                ChoiceChips(listOf("硅基流动", "火山引擎"), if (siliconFlow) 0 else 1) {
                    settings = settings.copy(engine = if (it == 0) TtsEngine.SILICONFLOW else TtsEngine.DOUBAO)
                    linkError = null
                }
                ToggleRow("启用语音", value = settings.enabled, onChange = { settings = settings.copy(enabled = it) })
                Text(if (extraction.enabled) "回复完成后提前提取对白。播放当前语音时，会继续准备后续对白和音频。"
                    else if (siliconFlow) "按句提交文字，音频返回后边接收边播放。" else "边生成文字，边合成并播放语音。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            }
            SectionLabel("对白朗读", "两种语音引擎共用")
            FormCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { updateExtraction { it.copy(enabled = !it.enabled) } }.padding(vertical = 8.dp, horizontal = 0.dp)) {
                        Text("提取对白后朗读", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("略过动作、心理和场景描写", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Switch(extraction.enabled, { enabled -> updateExtraction { it.copy(enabled = enabled) } }, modifier = Modifier.testTag("dialogue-extraction-toggle"))
                }
                if (extraction.enabled) {
                    Text("提取模型读取当前回复，并参考同一会话、同一角色上一条有效朗读指令，让语气自然衔接。不发送角色设定、原聊天历史或上一条回复原文。没有对白或提取失败时跳过朗读。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("dialogue-extraction-context"))
                    Text("两种引擎都朗读对白；朗读指令仅在火山语音合成 2.0 生效。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("dialogue-instruction-support"))
                    SelectField("提取模型服务", extractionProvider?.name ?: "请选择模型服务", state.providers.map { it.name },
                        modifier = Modifier.testTag("dialogue-extraction-provider")) { index ->
                        updateExtraction { it.copy(model = it.model.copy(providerId = state.providers[index].id)) }
                    }
                    Text(if (extractionKeySaved && extractionProvider != null) "已配置该服务的密钥" else "请到「设置 → 模型服务」填写所选服务的 API 密钥。",
                        color = if (extractionKeySaved && extractionProvider != null) Sage else MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 19.sp)
                    OutlinedTextField(extraction.model.model, { model -> updateExtraction { it.copy(model = it.model.copy(model = model)) } },
                        modifier = Modifier.fillMaxWidth().testTag("dialogue-extraction-model"), label = { Text("提取模型名称") }, singleLine = true,
                        shape = RoundedCornerShape(10.dp), trailingIcon = {
                            IconButton(onClick = { showExtractionModels = true }) { Icon(Icons.Outlined.Search, "选择对白提取模型") }
                        })
                    OutlinedTextField(extraction.systemPrompt, { prompt -> updateExtraction { it.copy(systemPrompt = prompt) } },
                        modifier = Modifier.fillMaxWidth().testTag("dialogue-extraction-prompt"), label = { Text("对白提取提示词") },
                        minLines = 4, maxLines = 8, shape = RoundedCornerShape(10.dp))
                    Text("输出格式需保留 text（对白）和 instruction（朗读指令）两个字段。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp,
                        modifier = Modifier.testTag("dialogue-extraction-format"))
                    TextButton(onClick = { updateExtraction { it.copy(systemPrompt = DEFAULT_DIALOGUE_EXTRACTION_PROMPT) } },
                        modifier = Modifier.align(Alignment.End).testTag("dialogue-extraction-reset"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("恢复默认提示词", fontSize = 12.sp)
                    }
                }
            }
            if (siliconFlow) {
                SectionLabel("连接与密钥", "复用已有模型服务，无需重复填写密钥")
                FormCard {
                    SelectField("模型服务", siliconProvider?.name ?: "请选择模型服务", state.providers.map { it.name }) { index ->
                        settings = settings.copy(siliconProviderId = state.providers[index].id)
                    }
                    Text(if (siliconKeySaved && siliconProvider != null) "已配置该服务的密钥" else "尚未配置密钥：请到「设置 → 模型服务」填写所选服务的 API 密钥。",
                        color = if (siliconKeySaved && siliconProvider != null) Sage else MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 19.sp)
                }
                SectionLabel("默认声音", "角色可以单独覆盖语音模型与音色")
                FormCard {
                    FormField("语音模型", settings.siliconModel, { settings = settings.copy(siliconModel = it) }, hint = "可自由填写语音模型的完整名称")
                    TextButton(onClick = { settings = settings.copy(siliconModel = SiliconSpeechPresetModel) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("使用 CosyVoice2", fontSize = 12.sp)
                    }
                    SelectField("预置音色", settings.siliconVoice.takeIf { it in SiliconVoicePresets } ?: "自定义音色", SiliconVoicePresets) { index ->
                        settings = settings.copy(siliconVoice = SiliconVoicePresets[index])
                    }
                    FormField("默认音色 ID", settings.siliconVoice, { settings = settings.copy(siliconVoice = it) }, hint = "可填预置名称或完整音色 ID")
                }
                SectionLabel("播放语速")
                FormCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("语速", fontSize = 14.sp, color = Ink)
                        Text(String.format(java.util.Locale.US, "%.2f×", settings.siliconSpeed), color = Sage, fontSize = 14.sp)
                    }
                    Slider(value = settings.siliconSpeed.toFloat().takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f,
                        onValueChange = { settings = settings.copy(siliconSpeed = kotlin.math.round(it * 20.0) / 20.0) }, valueRange = 0.25f..4f, steps = 74)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("0.25× — 4.00×", color = Muted, fontSize = 11.sp)
                        TextButton(onClick = { settings = settings.copy(siliconSpeed = 1.0) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("恢复 1.00×", fontSize = 12.sp) }
                    }
                }
            } else {
                SectionLabel("接入火山引擎", "先开通语音合成服务，再获取对应项目的 API Key")
                FormCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ConsoleLink("登录火山引擎", Modifier.weight(1f)) { openLink("https://console.volcengine.com/home") }
                        ConsoleLink("获取 API Key", Modifier.weight(1f)) { openLink("https://console.volcengine.com/speech/new/setting/apikeys?projectName=default") }
                    }
                }
                SectionLabel("鉴权信息", if (initial.engine == TtsEngine.DOUBAO && runtime.ttsConfigured) "已保存凭据；同一服务与账号下可留空保留" else null)
                FormCard {
                    ChoiceChips(listOf("API Key", "App ID + Token"), if (legacy) 1 else 0) { legacy = it == 1 }
                    if (legacy) {
                        FormField("App ID", settings.appId, { settings = settings.copy(appId = it) })
                        SecretField("Access Token", token, { token = it }, "变更服务地址或 App ID 后需重新填写")
                    } else {
                        SecretField("API Key", apiKey, { apiKey = it }, "使用语音控制台的 API Key，无需 App ID")
                    }
                    FormField("Resource ID", settings.resourceId, { settings = settings.copy(resourceId = it) }, hint = "填写已开通的资源，例如 seed-tts-2.0")
                    TextButton(onClick = { settings = settings.copy(resourceId = "seed-tts-2.0") },
                        modifier = Modifier.testTag("doubao-tts2-preset"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("使用语音合成 2.0", fontSize = 12.sp)
                    }
                    if (settings.resourceId.trim() == "seed-tts-2.0") Text("请在「角色 → 声音」中填写 2.0 音色，如 zh_female_vv_uranus_bigtts。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("doubao-tts2-voice-guide"))
                    val mismatchedRoles = state.characters.filter {
                        DoubaoVoices.validationError(settings.resourceId, it.voice, runtime.doubaoVoices) != null
                    }
                    if (mismatchedRoles.isNotEmpty()) Text("音色与当前资源不匹配：${mismatchedRoles.joinToString("、") { it.name }}。请到角色声音页选择匹配音色。",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("doubao-voice-mismatched-roles"))
                    Text("资源版本需与角色音色一致。音色 ID 在角色设置中填写。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    ConsoleLink("查看官方音色列表") { openLink("https://docs.volcengine.com/docs/DoubaoVoice/Tonelist-1?lang=zh") }
                    ConsoleLink("语音指令与标签文档", Modifier.testTag("doubao-tts2-instructions-doc")) {
                        openLink("https://docs.volcengine.com/docs/DoubaoVoice/DoubaoTextToSpeech20CapabilityIntroduction?lang=zh")
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingsDisclosure("音色列表访问密钥", if (runtime.voiceCatalogConfigured) "已配置 · 留空保留" else "可选 · 用于刷新官方音色列表",
                    voiceCatalogExpanded || !catalogKeysValid, { voiceCatalogExpanded = !voiceCatalogExpanded }) {
                    Text("列表访问使用火山引擎 Access Key / Secret Key，与语音 API Key 不同。不填写也可以选择内置音色或手填音色 ID。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    SecretField("Access Key", voiceAccessKey, { voiceAccessKey = it }, if (runtime.voiceCatalogConfigured) "已保存，留空保留；更新时请同时填写两项" else "填写火山引擎访问密钥 AK",
                        modifier = Modifier.testTag("voice-catalog-access-key"))
                    SecretField("Secret Key", voiceSecretKey, { voiceSecretKey = it }, "与 Access Key 配对填写，不会显示已保存的密钥",
                        modifier = Modifier.testTag("voice-catalog-secret-key"))
                    if (!catalogKeysValid) Text("更新列表访问密钥时，请同时填写 Access Key 和 Secret Key。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                SettingsDisclosure("高级连接设置", "WebSocket 地址 · 默认使用火山引擎官方接口", advanced || !endpointValid, { advanced = !advanced }) {
                    FormField("WebSocket 地址", settings.endpoint, { settings = settings.copy(endpoint = it) }, keyboardType = KeyboardType.Uri)
                    Text("双向流式文字输入、音频输出。更换服务地址后，需要重新填写凭据。", color = Muted, fontSize = 12.sp)
                }
            }
            Text("保存后，在会话设置中开启自动朗读；也可以长按消息选择「朗读消息」。", color = Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 2.dp, vertical = 12.dp))
            linkError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (!valid) Text(if (!catalogKeysValid) "请同时填写音色列表的 Access Key 和 Secret Key，或将两项都留空。"
                else if (!extractionValid) "请选择提取模型服务，并填写模型名称和对白提取提示词；提示词最多 32768 个字符。"
                else if (siliconFlow) "请选择模型服务，并填写语音模型和默认音色。" else "请检查语音资源 ID、连接地址和 App ID。",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (showExtractionModels) ModelPicker(runtime.modelLists[extraction.model.providerId].orEmpty(), { showExtractionModels = false }) { model ->
        updateExtraction { it.copy(model = it.model.copy(model = model)) }
        showExtractionModels = false
    }
}

@Composable private fun SecretField(label: String, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, modifier = modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, supportingText = { Text(hint) }, shape = RoundedCornerShape(10.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密钥" else "显示密钥") } })
}
@Composable private fun SettingsDisclosure(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    FormCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, color = Ink, fontSize = 15.sp)
                Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "收起$title" else "展开$title", tint = Muted)
        }
        if (expanded) {
            HorizontalDivider(color = Line)
            content()
        }
    }
}

@Composable private fun ConsoleLink(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 40.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        Text(label, fontSize = 13.sp)
        Spacer(Modifier.width(5.dp))
        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(15.dp))
    }
}
