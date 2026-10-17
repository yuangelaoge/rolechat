package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.Character
import cn.rolechat.core.ChatGateway
import cn.rolechat.core.ChatMessage
import cn.rolechat.core.ChatRoom
import cn.rolechat.core.DialogueExtractionSettings
import cn.rolechat.core.GenerationRequest
import cn.rolechat.core.Member
import cn.rolechat.core.ModelConfig
import cn.rolechat.core.Provider
import cn.rolechat.core.StateStore
import cn.rolechat.core.StreamEvent
import cn.rolechat.core.TtsEngine
import cn.rolechat.core.TtsSettings
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real sink and HTTP speech client without Android's audio hardware. */
class AndroidSpeechPipelineIntegrationTest {
    @Test fun extractionAndSynthesisRunAheadOfPlaybackWhilePlaybackRemainsFifo() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.enqueue(1)
            fixture.awaitStarted(1)
            fixture.enqueue(2)
            fixture.enqueue(3)
            fixture.awaitRequest(2)
            fixture.awaitExtraction(3)

            assertEquals("Playing 1 must not block the HTTP request for 2 or extraction of 3", listOf(1), fixture.started.toList())
            assertFalse(fixture.completed[1].isCompleted)
            assertTrue(fixture.events.indexOf("tts-2") > fixture.events.indexOf("play-1"))
            assertTrue(fixture.events.indexOf("extract-3") > fixture.events.indexOf("play-1"))

            fixture.release[1].complete(Unit)
            fixture.awaitStarted(2)
            assertEquals(listOf(1, 2), fixture.started.toList())
            assertTrue(fixture.completed[1].isCompleted)
            assertFalse(fixture.completed[2].isCompleted)
            fixture.release[2].complete(Unit)
            fixture.awaitStarted(3)
            assertEquals(listOf(1, 2, 3), fixture.started.toList())
            assertTrue(fixture.completed[2].isCompleted)
            fixture.release[3].complete(Unit)
            withTimeout(5_000) { fixture.completed[3].await() }

            val records = fixture.store.snapshot().messages.associateBy { it.id }
            assertEquals("语气1，轻声自然。", records.getValue("reply-2").speechPreparation?.previousInstruction)
            assertEquals("reply-1", records.getValue("reply-2").speechPreparation?.previousMessageId)
            assertEquals("语气2，轻声自然。", records.getValue("reply-3").speechPreparation?.previousInstruction)
            assertEquals(listOf("对白1。", "对白2。", "对白3。"), fixture.requestTexts.toList())
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun stopDiscardsAlreadyPrefetchedAudioAndPreventsLaterPlayback() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.enqueue(1)
            fixture.awaitStarted(1)
            fixture.enqueue(2)
            fixture.enqueue(3)
            fixture.awaitRequest(2)
            fixture.awaitExtraction(3)

            fixture.sink.stopAll()
            assertTrue("stopAll must synchronously stop the active player", fixture.players.first { it.playing == 1 }.stopped)
            withTimeout(5_000) { fixture.completed[1].await() }
            fixture.release.forEach { it.complete(Unit) }
            // Permit late HTTP callbacks and completed prefetches to be delivered.
            delay(250)
            assertEquals("Stopped prefetches cannot advance to the speaker", listOf(1), fixture.started.toList())
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun longPrefetchedPcmBackpressuresHttpAndIsPlayedCompletelyAfterItsTurn() = runBlocking {
        val longPcmSize = 17 * 1024 * 1024
        val fixture = Fixture(responseBytes = mapOf(2 to longPcmSize), timeoutSeconds = 30)
        try {
            fixture.enqueue(1)
            fixture.awaitStarted(1)
            fixture.enqueue(2)
            fixture.awaitRequest(2)
            fixture.awaitBodyRead(2)
            delay(250)
            assertEquals(listOf(1), fixture.started.toList())
            assertTrue("An utterance waiting for playback must suspend HTTP reads instead of buffering 17 MiB",
                fixture.networkBytes[2].get() in 1..(256 * 1024))

            fixture.release[1].complete(Unit)
            fixture.awaitStarted(2)
            fixture.release[2].complete(Unit)
            withTimeout(20_000) { fixture.completed[2].await() }
            assertEquals(listOf(1, 2), fixture.started.toList())
            assertEquals("A long stream must not be truncated at the prefetch buffer limit", longPcmSize, fixture.consumedBytes[2].get())
            assertEquals(longPcmSize, fixture.networkBytes[2].get())
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            assertFalse("Long prefetch must not report a buffer/network error: ${fixture.statuses}",
                fixture.statuses.any { it.contains("失败") || it.contains("过大") || it.contains("超时") || it.contains("缓冲已满") })
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val responseBytes: Map<Int, Int> = emptyMap(), timeoutSeconds: Long = 5) {
        private val server = MockWebServer()
        private val owner = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(owner + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
        val events = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<String>()
        val requestTexts = CopyOnWriteArrayList<String>()
        val started = CopyOnWriteArrayList<Int>()
        val players = CopyOnWriteArrayList<ControlledPlayer>()
        val release = List(4) { CompletableDeferred<Unit>() }
        val completed = List(4) { CompletableDeferred<Unit>() }
        val consumedBytes = List(4) { AtomicInteger() }
        val networkBytes = List(4) { AtomicInteger() }
        private val bodyReadSignals = List(4) { CompletableDeferred<Unit>() }
        private val startedSignals = List(4) { CompletableDeferred<Unit>() }
        private val requests = List(4) { CompletableDeferred<Unit>() }
        private val extractions = List(4) { CompletableDeferred<Unit>() }
        private val actor = Character(id = "speaker", name = "小萱", siliconModel = "test/speech", siliconVoice = "alex")
        private val http = OkHttpClient.Builder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addNetworkInterceptor response
                val index = response.header("X-Test-Utterance")?.toIntOrNull() ?: return@addNetworkInterceptor response
                response.newBuilder().body(object : ResponseBody() {
                    private val countedSource = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            val size = super.read(sink, byteCount)
                            if (size > 0) {
                                networkBytes[index].addAndGet(size.toInt())
                                bodyReadSignals[index].complete(Unit)
                            }
                            return size
                        }
                    }.buffer()
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = countedSource
                }).build()
            }.build()
        private val ws = OkHttpClient.Builder().build()
        private val provider: Provider
        val store: StateStore
        val sink: AndroidSpeechSink

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/v1/audio/speech") return MockResponse().setResponseCode(404)
                    val text = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("input").jsonPrimitive.content
                    val index = (1..3).singleOrNull { text == "对白$it。" }
                        ?: return MockResponse().setResponseCode(400)
                    requestTexts += text
                    events += "tts-$index"
                    requests[index].complete(Unit)
                    val pcm = ByteArray(responseBytes[index] ?: 4).also {
                        it[0] = index.toByte()
                        it[2] = 17
                    }
                    return MockResponse().setHeader("Content-Type", "audio/pcm").setHeader("X-Test-Utterance", index)
                        .setBody(Buffer().write(pcm))
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            provider = Provider(id = "speech-test", baseUrl = server.url("/v1/").newBuilder().host("127.0.0.1").build().toString())
            val extraction = DialogueExtractionSettings(enabled = true, model = ModelConfig(providerId = provider.id, model = "test/extract"))
            store = StateStore(AppState(providers = listOf(provider), characters = listOf(actor),
                rooms = listOf(ChatRoom(id = "room", members = listOf(Member(actor.id, 0, true)))),
                messages = (1..3).map { index -> ChatMessage(id = "reply-$index", roomId = "room", characterId = actor.id,
                    senderName = actor.name, text = "原文$index。", createdAt = index.toLong()) },
                tts = TtsSettings(enabled = true, engine = TtsEngine.SILICONFLOW, siliconProviderId = provider.id,
                    siliconModel = "test/speech", siliconVoice = "alex", dialogueExtraction = extraction)))
            val gateway = object : ChatGateway {
                override fun stream(request: GenerationRequest) = flow {
                    val user = request.messages.last().content
                    val original = if (user.startsWith("{")) Json.parseToJsonElement(user).jsonObject.getValue("current_reply").jsonPrimitive.content else user
                    val index = (1..3).single { original == "原文$it。" }
                    events += "extract-$index"
                    extractions[index].complete(Unit)
                    emit(StreamEvent.Text(buildJsonObject {
                        put("text", "对白$index。")
                        put("instruction", "语气$index，轻声自然。")
                    }.toString()))
                    emit(StreamEvent.Finished("stop"))
                }
            }
            sink = AndroidSpeechSink(store, scope, credentials = {
                TtsCredentials(siliconProvider = provider, siliconApiKey = "unit-test-credential",
                    dialogueExtraction = DialogueExtractionSnapshot(provider, gateway))
            }, onStatus = { statuses += it }, client = ws, siliconHttpClient = http,
                pcmPlayerFactory = { ControlledPlayer().also { players += it } })
        }

        fun enqueue(index: Int) {
            sink.begin("reply-$index", actor)
            sink.append("reply-$index", "原文$index。")
            sink.finish("reply-$index")
        }

        suspend fun awaitStarted(index: Int) = withTimeout(5_000) { startedSignals[index].await() }
        suspend fun awaitRequest(index: Int) = withTimeout(5_000) { requests[index].await() }
        suspend fun awaitExtraction(index: Int) = withTimeout(5_000) { extractions[index].await() }
        suspend fun awaitBodyRead(index: Int) = withTimeout(5_000) { bodyReadSignals[index].await() }

        suspend fun close() {
            sink.close()
            owner.cancelAndJoin()
            server.shutdown()
        }

        inner class ControlledPlayer : SpeechPcmPlayer {
            @Volatile var stopped = false
            @Volatile var playing: Int? = null

            override fun stop() { stopped = true }

            override suspend fun play(audio: ReceiveChannel<ByteArray>, sampleRate: Int, onConsumed: (Int) -> Unit, onStarted: () -> Unit) {
                if (stopped) throw CancellationException()
                assertEquals(24_000, sampleRate)
                var index: Int? = null
                try {
                    for (chunk in audio) {
                        if (stopped) throw CancellationException()
                        if (chunk.isEmpty()) continue
                        onConsumed(chunk.size)
                        val current = index ?: chunk.first().toInt()
                        consumedBytes[current].addAndGet(chunk.size)
                        if (index == null) {
                            index = current
                            playing = current
                            started += current
                            events += "play-$current"
                            onStarted()
                            startedSignals[current].complete(Unit)
                            release[current].await()
                        }
                    }
                } finally {
                    index?.let { completed[it].complete(Unit) }
                }
            }
        }
    }
}
