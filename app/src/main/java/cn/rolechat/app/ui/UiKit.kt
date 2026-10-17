package cn.rolechat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rolechat.core.Character
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal val Paper = Color(0xFF111111)
internal val Panel = Color(0xFF1E1E1E)
internal val Raised = Color(0xFF2C2C2C)
internal val Ink = Color(0xFFE6E6E6)
internal val AvatarInk = Color(0xFF172019)
internal val Sage = Color(0xFF3EB575)
internal val SoftGreen = Color(0xFF183B2B)
internal val Muted = Color(0xFF989898)
internal val Line = Color(0xFF303030)
internal val AvatarColors = listOf(0xFF99B8A4, 0xFFE6C49A, 0xFFAEBED7, 0xFFD5B5BC, 0xFFC4C2A1, 0xFFB5C9CF)

internal fun <T> serializedSaver(serializer: KSerializer<T>): Saver<T, String> = Saver(
    save = { Json.encodeToString(serializer, it) },
    restore = { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }
)

@Composable internal fun Avatar(character: Character?, modifier: Modifier = Modifier, size: Int = 48, label: String = "我", image: String = character?.avatarImage.orEmpty()) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape((size * .22f).dp)).background(character?.let { Color(it.color) } ?: SoftGreen),
        contentAlignment = Alignment.Center
    ) {
        AvatarPhoto(image, Modifier.fillMaxSize(), contentDescription = "头像照片") {
            Text(character?.avatar?.ifBlank { character.name.take(1) } ?: label, color = if (character != null) AvatarInk else Ink,
                fontSize = (size * .38f).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable internal fun SectionLabel(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Muted)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable internal fun FormCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable internal fun SceneBadge(modifier: Modifier = Modifier) {
    Text("场景", color = Sage, fontSize = 10.sp, maxLines = 1,
        modifier = modifier.background(SoftGreen, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
}

@Composable internal fun FormField(
    label: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, hint: String? = null, multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text, enabled: Boolean = true
) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, supportingText = hint?.let { { Text(it) } },
        minLines = if (multiline) 3 else 1, maxLines = if (multiline) 8 else 1,
        singleLine = !multiline, shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}

@Composable internal fun ToggleRow(title: String, subtitle: String? = null, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable internal fun IntSlider(title: String, value: Int, range: IntRange, suffix: String = "", onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Ink)
            Text("$value$suffix", style = MaterialTheme.typography.bodyMedium, color = Sage, fontWeight = FontWeight.SemiBold)
        }
        Slider(value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()), { onChange(it.toInt().coerceIn(range)) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = if (range.last - range.first <= 20) (range.last - range.first - 1).coerceAtLeast(0) else 0)
    }
}

@Composable internal fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, label ->
            FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(label) },
                leadingIcon = if (index == selected) { { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) } } else null)
        }
    }
}

@Composable internal fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().background(Paper, RoundedCornerShape(8.dp)).padding(10.dp),
        color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EditorScaffold(title: String, onBack: () -> Unit, onSave: () -> Unit, valid: Boolean = true, content: @Composable (PaddingValues) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finish(action: () -> Unit) { focus.clearFocus(force = true); keyboard?.hide(); action() }
    Scaffold(containerColor = Paper, topBar = {
        TopAppBar(title = { Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = { finish(onBack) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { Button(onClick = { finish(onSave) }, enabled = valid, modifier = Modifier.padding(end = 16.dp).height(36.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text("保存", fontWeight = FontWeight.Medium) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel, titleContentColor = Ink,
                navigationIconContentColor = Ink, actionIconContentColor = Ink))
    }, content = content)
}

@Composable internal fun EmptyPanel(title: String, description: String, symbol: String = "✦", action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(SoftGreen, CircleShape), contentAlignment = Alignment.Center) { Text(symbol, fontSize = 28.sp, color = Sage) }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Ink, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted, lineHeight = 23.sp)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}
