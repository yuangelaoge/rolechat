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
import cn.rolechat.core.SpeechPreparationStatus
import cn.rolechat.core.StateStore
import cn.rolechat.core.StreamEvent
import cn.rolechat.core.TtsEngine
import cn.rolechat.core.TtsSettings
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

/** The real V3 WebSocket request path, with a loopback server and no Android audio hardware. */
class DoubaoSpeechPipelineIntegrationTest {
    @Test fun nextSessionIsSynthesizedAndThirdReplyExtractedBeforeFirstPlaybackFinishes() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.enqueue(1)
            fixture.awaitStarted(1)
            fixture.enqueue(2)
            fixture.enqueue(3)
            fixture.awaitRequest(2)
            fixture.awaitExtractionComplete(3)

            assertEquals(listOf(1), fixture.started.toList())
            assertFalse(fixture.completed[1].isCompleted)
            assertEquals(2, fixture.sessionBodies.size)
            assertEquals(listOf("对白1。", "对白2。"), fixture.taskBodies.map { it.params().getValue("text").jsonPrimitive.content })
            assertTrue(fixture.events.indexOf("session-2") > fixture.events.indexOf("play-1"))
            assertTrue(fixture.events.indexOf("task-2") > fixture.events.indexOf("play-1"))
            assertTrue(fixture.events.indexOf("extract-3") > fixture.events.indexOf("play-1"))

            // Natural-language instructions belong to StartSession additions,
            // never to the spoken TaskRequest text or inline speech tags.
            fixture.sessionBodies.forEachIndexed { position, body ->
                val params = body.params()
                val additions = Json.parseToJsonElement(params.getValue("additions").jsonPrimitive.content).jsonObject
                assertEquals(listOf("语气${position + 1}，轻声自然。"),
                    additions.getValue("context_texts").jsonArray.map { it.jsonPrimitive.content })
                assertEquals("", params.getValue("text").jsonPrimitive.content)
                assertEquals("zh_female_vv_uranus_bigtts", params.getValue("speaker").jsonPrimitive.content)
                assertEquals("pcm", params.getValue("audio_params").jsonObject.getValue("format").jsonPrimitive.content)
            }
            assertTrue(fixture.taskBodies.none { it.params().containsKey("additions") })
            assertTrue(fixture.resourceHeaders.all { it == "seed-tts-2.0" })

            fixture.release[1].complete(Unit)
            fixture.awaitStarted(2)
            assertTrue(fixture.completed[1].isCompleted)
            fixture.release[2].complete(Unit)
            fixture.awaitStarted(3)
            assertTrue(fixture.completed[2].isCompleted)
            fixture.release[3].complete(Unit)
            withTimeout(5_000) { fixture.completed[3].await() }
            assertEquals(listOf(1, 2, 3), fixture.started.toList())
            assertEquals(listOf("对白1。", "对白2。", "对白3。"), fixture.taskBodies.map { it.params().getValue("text").jsonPrimitive.content })
            val records = fixture.store.snapshot().messages.associateBy { it.id }
            assertEquals("语气1，轻声自然。", records.getValue("reply-2").speechPreparation?.previousInstruction)
            assertEquals("语气2，轻声自然。", records.getValue("reply-3").speechPreparation?.previousInstruction)
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun stopDiscardsThePrefetchedWebSocketAudioAndDoesNotStartTheThirdSession() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.enqueue(1)
            fixture.awaitStarted(1)
            fixture.enqueue(2)
            fixture.enqueue(3)
            fixture.awaitRequest(2)
            fixture.awaitExtractionComplete(3)
            assertEquals(2, fixture.sessionBodies.size)

            fixture.sink.stopAll()
            assertTrue(fixture.players.single { it.playing == 1 }.stopped)
            withTimeout(5_000) { fixture.completed[1].await() }
            fixture.release.forEach { it.complete(Unit) }
            // Give already-delivered WebSocket frames and close callbacks time to run.
            delay(250)
            assertEquals(listOf(1), fixture.started.toList())
            assertEquals("The extracted third reply cannot open a session after stop", 2, fixture.sessionBodies.size)
            val pending = fixture.store.snapshot().messages.filter { it.id != "reply-1" }
            assertTrue(pending.all { it.speechPreparation?.continuityEligible == false })
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        } finally { fixture.close() }
    }

    private fun JsonObject.params() = getValue("req_params").jsonObject

    private class Fixture {
        private val server = MockWebServer()
        private val owner = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(owner + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
        val events = CopyOnWriteArrayList<String>()
        val started = CopyOnWriteArrayList<Int>()
        val players = CopyOnWriteArrayList<ControlledPlayer>()
        val sessionBodies = CopyOnWriteArrayList<JsonObject>()
        val taskBodies = CopyOnWriteArrayList<JsonObject>()
        val resourceHeaders = CopyOnWriteArrayList<String>()
        val release = List(4) { CompletableDeferred<Unit>() }
        val completed = List(4) { CompletableDeferred<Unit>() }
        private val startedSignals = List(4) { CompletableDeferred<Unit>() }
        private val requestSignals = List(4) { CompletableDeferred<Unit>() }
        private val actor = Character(id = "speaker", name = "小萱", voice = "zh_female_vv_uranus_bigtts")
        private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        private val websocketClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val store: StateStore
        val sink: AndroidSpeechSink

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/tts") return MockResponse().setResponseCode(404)
                    resourceHeaders += request.getHeader("X-Api-Resource-Id").orEmpty()
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            try {
                                val frame = DoubaoProtocol.parse(bytes.toByteArray())
                                when (frame.event) {
                                    DoubaoProtocol.START_CONNECTION -> webSocket.send(serverFrame(DoubaoProtocol.CONNECTION_STARTED, "connection"))
                                    DoubaoProtocol.START_SESSION -> {
                                        val body = Json.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject
                                        sessionBodies += body
                                        events += "session-${sessionBodies.size}"
                                        webSocket.send(serverFrame(DoubaoProtocol.SESSION_STARTED, requireNotNull(frame.id)))
                                    }
                                    DoubaoProtocol.TASK_REQUEST -> {
                                        val body = Json.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject
                                        val text = body.getValue("req_params").jsonObject.getValue("text").jsonPrimitive.content
                                        val index = (1..3).single { text == "对白$it。" }
                                        taskBodies += body
                                        events += "task-$index"
                                        // A real binary TTSResponse carries PCM, not a fake player signal.
                                        webSocket.send(serverFrame(DoubaoProtocol.AUDIO, requireNotNull(frame.id),
                                            byteArrayOf(index.toByte(), 0, 17, 0), audio = true))
                                        requestSignals[index].complete(Unit)
                                    }
                                    DoubaoProtocol.FINISH_SESSION -> webSocket.send(serverFrame(DoubaoProtocol.SESSION_FINISHED, requireNotNull(frame.id)))
                                    DoubaoProtocol.FINISH_CONNECTION -> webSocket.send(serverFrame(DoubaoProtocol.CONNECTION_FINISHED, "connection"))
                                    else -> error("Unexpected client event ${frame.event}")
                                }
                            } catch (error: Throwable) { failures += error; webSocket.cancel() }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, null)
                        }
                    })
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val provider = Provider(id = "extract-test", baseUrl = "https://never-used.invalid/v1")
            val extraction = DialogueExtractionSettings(enabled = true, model = ModelConfig(providerId = provider.id, model = "test/extract"))
            store = StateStore(AppState(providers = listOf(provider), characters = listOf(actor),
                rooms = listOf(ChatRoom(id = "room", members = listOf(Member(actor.id, 0, true)))),
                messages = (1..3).map { index -> ChatMessage(id = "reply-$index", roomId = "room", characterId = actor.id,
                    senderName = actor.name, text = "原文$index。", createdAt = index.toLong()) },
                tts = TtsSettings(enabled = true, engine = TtsEngine.DOUBAO, resourceId = "seed-tts-2.0",
                    endpoint = server.url("/tts").toString().replaceFirst("http://", "ws://"), dialogueExtraction = extraction)))
            val gateway = object : ChatGateway {
                override fun stream(request: GenerationRequest) = flow {
                    val user = request.messages.last().content
                    val original = if (user.startsWith("{")) Json.parseToJsonElement(user).jsonObject.getValue("current_reply").jsonPrimitive.content else user
                    val index = (1..3).single { original == "原文$it。" }
                    events += "extract-$index"
                    emit(StreamEvent.Text(buildJsonObject {
                        put("text", "对白$index。")
                        put("instruction", "语气$index，轻声自然。")
                    }.toString()))
                    emit(StreamEvent.Finished("stop"))
                }
            }
            sink = AndroidSpeechSink(store, scope, credentials = {
                TtsCredentials(apiKey = "offline-test-credential", dialogueExtraction = DialogueExtractionSnapshot(provider, gateway))
            }, onStatus = {}, client = websocketClient, siliconHttpClient = http,
                pcmPlayerFactory = { ControlledPlayer().also { players += it } })
        }

        fun enqueue(index: Int) {
            sink.begin("reply-$index", actor)
            sink.append("reply-$index", "原文$index。")
            sink.finish("reply-$index")
        }

        suspend fun awaitStarted(index: Int) = withTimeout(5_000) { startedSignals[index].await() }
        suspend fun awaitRequest(index: Int) = withTimeout(5_000) { requestSignals[index].await() }
        suspend fun awaitExtractionComplete(index: Int) = withTimeout(5_000) {
            store.state.first { state -> state.messages.single { it.id == "reply-$index" }.speechPreparation?.status == SpeechPreparationStatus.COMPLETE }
        }

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
                        if (index == null) {
                            val current = chunk.first().toInt()
                            index = current
                            playing = current
                            started += current
                            events += "play-$current"
                            onStarted()
                            startedSignals[current].complete(Unit)
                            release[current].await()
                        }
                    }
                } finally { index?.let { completed[it].complete(Unit) } }
            }
        }
    }

    companion object {
        private fun serverFrame(event: Int, id: String, payload: ByteArray = "{}".toByteArray(), audio: Boolean = false): ByteString {
            val bytes = ByteArrayOutputStream().also { output ->
                DataOutputStream(output).use { writer ->
                    writer.write(byteArrayOf(0x11, if (audio) 0xb4.toByte() else 0x94.toByte(), if (audio) 0x00 else 0x10, 0x00))
                    writer.writeInt(event)
                    val identity = id.toByteArray(Charsets.UTF_8)
                    writer.writeInt(identity.size)
                    writer.write(identity)
                    writer.writeInt(payload.size)
                    writer.write(payload)
                }
            }.toByteArray()
            return bytes.toByteString()
        }
    }
}
