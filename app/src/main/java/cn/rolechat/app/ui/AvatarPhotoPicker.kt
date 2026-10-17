package cn.rolechat.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.rolechat.app.data.avatarBitmap
import cn.rolechat.app.data.importAvatarImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decode outside composition; the caller owns the avatar frame and its existing fallback. */
@Composable internal fun AvatarPhoto(
    image: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallback: @Composable () -> Unit = {}
) {
    key(image) {
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(image) {
            bitmap = if (image.isBlank()) null else withContext(Dispatchers.Default) { avatarBitmap(image) }
        }
        val ready = bitmap
        if (ready == null) fallback()
        else Image(ready.asImageBitmap(), contentDescription, modifier, contentScale = ContentScale.Crop)
    }
}

/** System photo picker grants access to only the selected image; no media-library permission. */
@Composable internal fun AvatarPhotoPicker(
    image: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "avatar",
    onLoadingChange: (Boolean) -> Unit = {}
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnLoadingChange by rememberUpdatedState(onLoadingChange)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var requestVersion by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        onDispose {
            requestVersion++
            importJob?.cancel()
            currentOnLoadingChange(false)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            importJob?.cancel()
            val attempt = ++requestVersion
            loading = true
            error = null
            currentOnLoadingChange(true)
            importJob = scope.launch {
                try {
                    val normalized = importAvatarImage(resolver, uri)
                    currentOnChange(normalized)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = if (failure is IllegalArgumentException) failure.message else "无法读取这张照片，请重新选择"
                } finally {
                    if (attempt == requestVersion) {
                        loading = false
                        currentOnLoadingChange(false)
                    }
                }
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                error = null
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    error = "无法打开系统相册，请检查设备是否有可用的图片选择器"
                }
            }, enabled = !loading, modifier = Modifier.testTag("${testTagPrefix}_choose")) {
                Text(if (image.isBlank()) "从相册选择" else "更换照片")
            }
            if (image.isNotBlank()) {
                TextButton(onClick = {
                    importJob?.cancel()
                    error = null
                    currentOnChange("")
                }, enabled = !loading, modifier = Modifier.testTag("${testTagPrefix}_remove")) { Text("移除照片") }
            }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(if (loading) "正在处理照片…" else "照片会居中裁成正方形，保存后生效。",
            style = MaterialTheme.typography.bodySmall, color = Muted)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("${testTagPrefix}_error")) }
    }
}
