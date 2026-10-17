package cn.rolechat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cn.rolechat.app.ui.RoleChatApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var pendingGroupExportId: String? = null
    private val groupExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val roomId = pendingGroupExportId
        pendingGroupExportId = null
        if (uri != null && roomId != null) lifecycleScope.launch {
            try {
                val text = vm.groupExportText(roomId)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法打开文件")
                }
                vm.notice("群聊配置已导出，包含成员配置，不含聊天记录或密钥")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { vm.notice("导出群聊失败：${error.message.orEmpty()}") }
        } else if (uri != null) vm.notice("导出会话已失效，请重新选择群聊导出")
    }
    private val groupImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBackupText() } ?: error("无法打开文件")
                }
                vm.restoreGroup(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { vm.notice("导入群聊失败：${error.message.orEmpty()}") }
        }
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = vm.backupText()
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("无法打开文件") }
                vm.notice("备份已导出，不包含 API 密钥")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { vm.notice("导出失败：${e.message}") }
        }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBackupText() } ?: error("无法打开文件")
                }
                vm.restoreBackup(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { vm.notice("导入失败：${e.message}") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingGroupExportId = savedInstanceState?.getString("pending-group-export")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF111111.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF111111.toInt()),
        )
        vm.requestExport = { exportLauncher.launch("片刻-聊天备份.json") }
        vm.requestImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
        vm.requestGroupExport = { roomId, fileName ->
            pendingGroupExportId = roomId
            groupExportLauncher.launch(fileName)
        }
        vm.requestGroupImport = { groupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val runtime by vm.runtime.collectAsStateWithLifecycle()
            val ready by vm.ready.collectAsStateWithLifecycle()
            if (ready) RoleChatApp(state, runtime, vm)
            else MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF3EB575),
                background = Color(0xFF111111),
                surface = Color(0xFF111111),
                onBackground = Color(0xFFE6E6E6),
                onSurface = Color(0xFFE6E6E6),
            )) {
                Surface {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending-group-export", pendingGroupExportId)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { vm.flush(); super.onStop() }
}

/** InputStream.readNBytes is not available on Android 8; enforce the limit while reading. */
internal fun InputStream.readBackupText(limit: Int = 20 * 1024 * 1024): String {
    require(limit in 0..20 * 1024 * 1024)
    val result = ByteArrayOutputStream(minOf(8192, limit))
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - result.size() + 1))
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            result.write(next)
        } else result.write(buffer, 0, count)
        require(result.size() <= limit) { "备份不能超过 20 MB" }
    }
    return result.toByteArray().toString(Charsets.UTF_8)
}
