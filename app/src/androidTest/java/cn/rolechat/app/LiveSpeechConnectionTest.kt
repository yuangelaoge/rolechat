package cn.rolechat.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.rolechat.app.speech.AndroidSpeechSink
import cn.rolechat.app.speech.TtsCredentials
import cn.rolechat.core.*
import cn.rolechat.core.Character
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Optional real API + Android PCM playback test. The private seed is never packaged. */
@RunWith(AndroidJUnit4::class)
class LiveSpeechConnectionTest {
    @Test fun siliconFlowAudioStreamsAndDrainsOnAndroidWhenCredentialIsProvided() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = File(context.filesDir, "live-speech-key")
        assumeTrue("Optional live speech credential was not provided", seed.isFile)
        val key = try { seed.readText().trim() } finally { seed.delete() }
        assumeTrue("Optional live speech credential was empty", key.isNotEmpty())
        val provider = Provider(id = "siliconflow")
        val settings = TtsSettings(enabled = true, engine = TtsEngine.SILICONFLOW)
        val actor = Character(id = "speech-live", name = "语音测试")
        val store = StateStore(AppState(providers = listOf(provider), characters = listOf(actor), tts = settings))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val statuses = CopyOnWriteArrayList<String>()
        val terminal = CompletableDeferred<Boolean>()
        val start = System.nanoTime()
        val sink = AndroidSpeechSink(store, scope,
            credentials = { TtsCredentials(siliconProvider = provider, siliconApiKey = key) },
            onStatus = { status ->
                statuses.add(status)
                if (status.endsWith("朗读完成")) terminal.complete(true)
                else if (listOf("失败", "超时", "错误", "请先", "无效", "不完整").any(status::contains)) terminal.complete(false)
            })
        try {
            sink.begin("speech-live-message", actor)
            // Feed an A prefix and its B continuation through one utterance.
            sink.append("speech-live-message", "你好，我是")
            sink.append("speech-live-message", "片刻的语音助手。")
            sink.finish("speech-live-message")
            val success = runBlocking { withTimeout(90_000) { terminal.await() } }
            assertTrue("Real speech did not finish: ${statuses.joinToString()}", success)
            assertTrue("No PCM playback was observed", statuses.any { it.contains("正在朗读") })
            val elapsed = (System.nanoTime() - start) / 1_000_000
            File(context.filesDir, "live-speech-result.txt").writeText(
                "SiliconFlow PCM 24000 Hz playback completed in ${elapsed}ms.\n" + statuses.joinToString("\n"))
        } finally {
            sink.close()
            scope.cancel()
            seed.delete()
        }
    }
}
