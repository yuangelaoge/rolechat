package cn.rolechat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.app.RuntimeUi
import cn.rolechat.app.speech.DoubaoVoice
import cn.rolechat.app.speech.DoubaoVoices
import cn.rolechat.core.Character
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DoubaoVoicePicker(resourceId: String, character: Character, runtime: RuntimeUi, canStopPreview: Boolean,
    onDismiss: () -> Unit, onRefresh: () -> Unit, onPreview: (DoubaoVoice) -> Unit, onStopPreview: () -> Unit,
    selectionLabel: String = "选择", selectionDescription: String? = null,
    onSelect: (DoubaoVoice) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var compatibleOnly by rememberSaveable { mutableStateOf(true) }
    val version = DoubaoVoices.resourceVersion(resourceId)
    val voices = runtime.doubaoVoices.sortedByDescending { version != null && DoubaoVoices.resourceVersion(it.resourceId) == version }.distinctBy { it.id }
    val query = search.trim()
    val visible = voices.filter { voice ->
        (!compatibleOnly || DoubaoVoices.validationError(resourceId, voice.id, runtime.doubaoVoices) == null) &&
            (query.isEmpty() || voice.name.contains(query, true) || voice.id.contains(query, true) || voice.description.contains(query, true))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("doubao-voice-picker").padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择音色", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("当前资源：${version?.let { "语音合成 $it" } ?: resourceId}", color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭音色选择", tint = Muted) }
            }
            if (!selectionDescription.isNullOrBlank()) Text(selectionDescription, color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 6.dp).testTag("doubao-voice-selection-note"))
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().testTag("doubao-voice-search"),
                label = { Text("搜索音色名称或 ID") }, singleLine = true, shape = RoundedCornerShape(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(compatibleOnly, { compatibleOnly = it }, modifier = Modifier.testTag("doubao-compatible-only"))
                Text("仅显示兼容音色", color = Ink, fontSize = 13.sp,
                    modifier = Modifier.weight(1f).clickable { compatibleOnly = !compatibleOnly }.padding(vertical = 10.dp))
                TextButton(onRefresh, enabled = !runtime.loadingDoubaoVoices, modifier = Modifier.testTag("refresh-doubao-voices")) {
                    Text(if (runtime.loadingDoubaoVoices) "刷新中…" else "刷新列表", fontSize = 12.sp)
                }
            }
            Text(if (runtime.doubaoVoiceListUpdatedAt > 0) "已更新 ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(runtime.doubaoVoiceListUpdatedAt))} · 含内置音色"
                else "内置音色列表", color = Muted, fontSize = 11.sp, modifier = Modifier.testTag("doubao-voice-list-source"))
            if (!runtime.voiceCatalogConfigured) Text("配置「语音服务 → 音色列表访问密钥」后可刷新；内置音色仍可选择。",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            if (runtime.doubaoVoiceListError.isNotBlank()) Text(runtime.doubaoVoiceListError, color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp).testTag("doubao-voice-list-error"))
            if (runtime.audioStatus.isNotBlank()) Text(runtime.audioStatus, color = Sage, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 5.dp).testTag("doubao-voice-preview-status"))
            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 10.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("doubao-voice-options")) {
                if (visible.isEmpty()) item {
                    Text(if (query.isBlank()) "当前列表没有匹配的音色。可刷新列表，或返回后填写官方音色 ID。" else "没有找到匹配音色。可修改搜索内容，或返回后手填音色 ID。",
                        color = Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 24.dp))
                }
                items(visible, key = { it.id }) { voice ->
                    val error = DoubaoVoices.validationError(resourceId, voice.id, runtime.doubaoVoices)
                    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(voice.name.ifBlank { voice.id }, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(DoubaoVoices.resourceVersion(voice.resourceId)?.let { "$it 音色" } ?: voice.resourceId, color = Muted, fontSize = 11.sp)
                        }
                        Text(voice.id, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                        if (voice.description.isNotBlank()) Text(voice.description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        if (character.voice.trim() == voice.id) Text("当前音色", color = Sage, fontSize = 11.sp)
                        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, lineHeight = 16.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { onPreview(voice) }, enabled = error == null && character.name.isNotBlank(),
                                modifier = Modifier.weight(1f).testTag("preview-doubao-voice-${voice.id}"), shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Text("试听", fontSize = 13.sp) }
                            Button(onClick = { onSelect(voice) }, enabled = error == null,
                                modifier = Modifier.weight(1f).testTag("select-doubao-voice-${voice.id}"), shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                                Text(selectionLabel, fontSize = 13.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onStopPreview, enabled = canStopPreview, modifier = Modifier.testTag("stop-doubao-voice-preview")) { Text("停止试听") }
                TextButton(onDismiss) { Text("关闭") }
            }
        }
    }
}
