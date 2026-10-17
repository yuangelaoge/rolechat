package cn.rolechat.app.speech

import cn.rolechat.core.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SiliconFlowSpeechClientTest {
    private val fakeKey = "unit-test-temporary-credential"
    private fun provider(server: MockWebServer) = Provider(id = "speech", baseUrl = server.url("/v1/").newBuilder().host("127.0.0.1").build().toString())
    private fun MockWebServer.startLoopback() = start(InetAddress.getByName("127.0.0.1"), 0)
    private fun pcm(vararg bytes: Int) = bytes.map(Int::toByte).toByteArray()
    private fun response(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(bytes))
    private fun List<ByteArray>.joined() = ByteArrayOutputStream().also { stream -> forEach { stream.write(it) } }.toByteArray()

    @Test fun requestUsesPcmStreamingAndAllowsAnyModelName() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(pcm(0, 0, 12, 1)).setHeader("Content-Type", "application/audio"))
            server.startLoopback()
            val chunks = SiliconFlowSpeechClient().stream(provider(server), fakeKey, "unlisted/new-model", "你好，世界。", "anna", 1.25).toList()
            assertArrayEquals(pcm(0, 0, 12, 1), chunks.joined())
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/audio/speech", request.path)
            assertEquals("Bearer $fakeKey", request.getHeader("Authorization"))
            val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("unlisted/new-model", json["model"]!!.jsonPrimitive.content)
            assertEquals("你好，世界。", json["input"]!!.jsonPrimitive.content)
            assertEquals("unlisted/new-model:anna", json["voice"]!!.jsonPrimitive.content)
            assertEquals("pcm", json["response_format"]!!.jsonPrimitive.content)
            assertEquals(24_000, json["sample_rate"]!!.jsonPrimitive.int)
            assertEquals(1.25, json["speed"]!!.jsonPrimitive.double, 0.0)
            assertTrue(json["stream"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun shortVoicesAreQualifiedAndFullOrCustomVoicesArePreserved() {
        assertEquals("model/name:alex", SiliconFlowSpeechClient.resolvedVoice("model/name", " alex "))
        assertEquals("other/model:anna", SiliconFlowSpeechClient.resolvedVoice("model/name", "other/model:anna"))
        assertEquals("speech:my-voice:some-id", SiliconFlowSpeechClient.resolvedVoice("model/name", "speech:my-voice:some-id"))
        assertEquals("model/name:any-new-voice", SiliconFlowSpeechClient.resolvedVoice("model/name", "any-new-voice"))
    }

    @Test fun fragmentedPcmIsEmittedBeforeEofAndEveryChunkContainsWholeSamples() = runBlocking {
        MockWebServer().use { server ->
            val expected = ByteArray(100) { (it % 31).toByte() }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setChunkedBody(Buffer().write(expected), 1).throttleBody(32, 100, TimeUnit.MILLISECONDS))
            server.startLoopback()
            val first = CompletableDeferred<Unit>()
            val chunks = mutableListOf<ByteArray>()
            val collection = async {
                SiliconFlowSpeechClient().stream(provider(server), fakeKey, "model", "正文", "alex").collect {
                    chunks += it
                    first.complete(Unit)
                }
            }
            try {
                withTimeout(3_000) { first.await() }
                assertFalse("Do not buffer the whole HTTP body before emitting", collection.isCompleted)
                withTimeout(10_000) { collection.await() }
                assertTrue(chunks.size > 1)
                assertTrue(chunks.all { it.isNotEmpty() && it.size % 2 == 0 })
                assertArrayEquals(expected, chunks.joined())
            } finally {
                collection.cancelAndJoin()
            }
        }
    }

    @Test fun cancellationAbortsBlockedBodyReadInsteadOfWaitingForTimeout() = runBlocking {
        MockWebServer().use { server ->
            // Send one sample then leave the connection open while claiming a longer body.
            server.enqueue(response(pcm(0, 0)).setHeader("Content-Length", 8_192))
            server.startLoopback()
            val activeCall = AtomicReference<Call>()
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call) }
            }).build()
            val first = CompletableDeferred<Unit>()
            val job = launch {
                SiliconFlowSpeechClient(client).stream(provider(server), fakeKey, "model", "正文", "alex")
                    .collect { first.complete(Unit) }
            }
            withTimeout(2_000) { first.await() }
            delay(50)
            withTimeout(1_000) { job.cancelAndJoin() }
            assertTrue(activeCall.get().isCanceled())
        }
    }

    @Test fun cancellationAlsoAbortsAnUnfinishedSinglePcmByte() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(pcm(0)).setHeader("Content-Length", 8_192))
            server.startLoopback()
            val receivedHeaders = CompletableDeferred<Unit>()
            val activeCall = AtomicReference<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call) }
                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) { receivedHeaders.complete(Unit) }
            }).build()
            var emitted = false
            val job = launch {
                SiliconFlowSpeechClient(client).stream(provider(server), fakeKey, "model", "正文", "alex")
                    .collect { emitted = true }
            }
            withTimeout(2_000) { receivedHeaders.await() }
            delay(50)
            withTimeout(1_000) { job.cancelAndJoin() }
            assertFalse("Half samples must never reach the player", emitted)
            assertTrue(activeCall.get().isCanceled())
        }
    }

    @Test fun httpErrorsKeepTheirStatusButRedactCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"message":"expired $fakeKey; Bearer another-secret; sk-test-other-key"}"""))
            server.startLoopback()
            val error = runCatching {
                SiliconFlowSpeechClient().stream(provider(server), fakeKey, "model", "正文", "alex").toList()
            }.exceptionOrNull()
            assertTrue(error is IOException)
            val message = error!!.message.orEmpty()
            assertTrue(message.contains("HTTP 401"))
            assertTrue(message.contains("expired"))
            assertFalse(message.contains(fakeKey))
            assertFalse(message.contains("another-secret"))
            assertFalse(message.contains("sk-test-other-key"))
            // Coroutine stack recovery may attach a copy as the cause. Every level must be safe.
            generateSequence(error) { it.cause }.take(10).forEach { cause ->
                assertFalse(cause.message.orEmpty().contains(fakeKey))
                assertFalse(cause.message.orEmpty().contains("another-secret"))
                assertFalse(cause.message.orEmpty().contains("sk-test-other-key"))
            }
        }
    }

    @Test fun redirectIsNeverFollowedEvenWhenInjectedClientAllowsIt() = runBlocking {
        MockWebServer().use { server ->
            server.startLoopback()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/credential-leak")))
            server.enqueue(response(pcm(0, 0)))
            val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
            val error = runCatching {
                SiliconFlowSpeechClient(client).stream(provider(server), fakeKey, "model", "正文", "alex").toList()
            }.exceptionOrNull()
            assertTrue(error!!.message.orEmpty().contains("HTTP 307"))
            assertEquals(1, server.requestCount)
            assertEquals("/v1/audio/speech", server.takeRequest().path)
            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun jsonOrHtmlCannotBeEmittedAsAudioEvenWithIncorrectContentType() = runBlocking {
        val cases = listOf(
            "application/json" to """{"error":"not audio"}""",
            "text/html" to "<html>gateway error</html>",
            "application/octet-stream" to "\n {\"error\":\"wrong type\"}",
            "audio/pcm" to " \n<!doctype html><html>error</html>",
            "audio/pcm" to "[\"error\"]",
            "application/octet-stream" to "\"plain JSON error\""
        )
        for ((type, payload) in cases) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", type).setChunkedBody(payload, 1))
                server.startLoopback()
                val chunks = mutableListOf<ByteArray>()
                val error = runCatching {
                    SiliconFlowSpeechClient().stream(provider(server), fakeKey, "model", "正文", "alex").collect { chunks += it }
                }.exceptionOrNull()
                assertNotNull("Expected rejection for $type", error)
                assertTrue("Text must be rejected before playback", chunks.isEmpty())
                assertTrue(error!!.message.orEmpty().contains("HTTP 200"))
            }
        }
    }

    @Test fun legitimatePcmMayStartWithAJsonOpeningByte() = runBlocking {
        MockWebServer().use { server ->
            val expected = pcm(123, 0, 0, 0, 43, 8)
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setChunkedBody(Buffer().write(expected), 1))
            server.startLoopback()
            val chunks = SiliconFlowSpeechClient().stream(provider(server), fakeKey, "model", "正文", "alex").toList()
            assertArrayEquals(expected, chunks.joined())
        }
    }

    @Test fun legitimatePcmMayStartWithObjectQuoteBeforeBinaryBytes() = runBlocking {
        MockWebServer().use { server ->
            val expected = pcm(123, 34, 0, 255, 0, 0)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/audio")
                .setChunkedBody(Buffer().write(expected), 1))
            server.startLoopback()
            val chunks = SiliconFlowSpeechClient().stream(provider(server), fakeKey, "model", "正文", "alex").toList()
            assertArrayEquals(expected, chunks.joined())
        }
    }

    @Test fun emptyOrTruncatedSampleFailsInsteadOfReportingSuccessfulSpeech() = runBlocking {
        val invalid = listOf(response(byteArrayOf()), response(pcm(0, 0, 1)),
            MockResponse().setHeader("Content-Type", "audio/pcm").setChunkedBody(Buffer().write(pcm(0, 0, 1)), 1))
        for (reply in invalid) {
            MockWebServer().use { server ->
                server.enqueue(reply)
                server.startLoopback()
                val error = runCatching {
                    SiliconFlowSpeechClient().stream(provider(server), fakeKey, "model", "正文", "alex").toList()
                }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error!!.message.orEmpty().contains("PCM"))
            }
        }
    }

    @Test fun encodedWaveBodyIsNotTreatedAsRawPcm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setChunkedBody("RIFF1234WAVE", 1))
            server.startLoopback()
            val chunks = mutableListOf<ByteArray>()
            val error = runCatching {
                SiliconFlowSpeechClient().stream(provider(server), fakeKey, "model", "正文", "alex").collect { chunks += it }
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(chunks.isEmpty())
        }
    }
}
