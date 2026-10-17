package cn.rolechat.app.ui

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.rolechat.core.ChatRoom
import cn.rolechat.core.MAX_DIRECTOR_INSTRUCTION

@Composable internal fun DirectorInstructionDialog(room: ChatRoom, canContinue: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onApply: (String, Boolean) -> Unit) {
    var draft by rememberSaveable(room.id) { mutableStateOf(room.directorInstruction) }
    val tooLong = draft.trim().length > MAX_DIRECTOR_INSTRUCTION
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Read the host window's IME before entering the separate dialog window. The
    // latter can briefly report zero insets when a keyboard opens after the dialog.
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
    val bottomInset = with(density) {
        maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density)).toDp()
    }
    fun finish(action: () -> Unit) { focus.clearFocus(force = true); keyboard?.hide(); action() }
    Dialog(onDismissRequest = { finish(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            onDispose { }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(bottom = bottomInset + if (keyboardVisible) 32.dp else 0.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 560.dp).testTag("director-dialog"),
                color = Panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("System · 剧情指令", Modifier.weight(1f), fontSize = 18.sp)
                        TextButton(onClick = { finish { onApply("", false) } }, enabled = room.directorInstruction.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("director-clear")) { Text("清空指令", fontSize = 12.sp) }
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().testTag("director-input"),
                            label = { Text("剧情指令") },
                            placeholder = { Text("例如：刚才的争吵没有发生。直接转到第二天早晨，由林间先来敲门。") },
                            minLines = 3, maxLines = 8, isError = tooLong)
                        if (tooLong) Text("最多 $MAX_DIRECTOR_INSTRUCTION 个字符", color = MaterialTheme.colorScheme.error)
                        Text("在故事外纠正剧情、指定转场或调整人物行为。仅对当前会话生效，持续保留到你修改或清空。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("保存只更新指令；应用并继续会按当前回复方式开启新一轮。清空不会撤销已经生成的剧情。",
                            style = MaterialTheme.typography.bodySmall)
                        if (busy) Text("保存或应用会停止当前回复和本会话的朗读。", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { finish(onDismiss) }, contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.weight(.8f).heightIn(min = 48.dp).testTag("director-cancel")) { Text("取消", maxLines = 1) }
                        OutlinedButton(onClick = { finish { onApply(draft, false) } }, enabled = !tooLong,
                            contentPadding = PaddingValues(horizontal = 4.dp), shape = RoundedCornerShape(9.dp),
                            modifier = Modifier.weight(.8f).heightIn(min = 48.dp).testTag("director-save")) { Text("保存", maxLines = 1) }
                        Button(onClick = { finish { onApply(draft, true) } }, enabled = !tooLong && draft.isNotBlank() && canContinue,
                            contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(9.dp),
                            modifier = Modifier.weight(1.6f).heightIn(min = 48.dp).testTag("director-apply")) { Text("应用并继续", maxLines = 1) }
                    }
                }
            }
        }
    }
}
