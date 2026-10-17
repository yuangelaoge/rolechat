package cn.rolechat.app.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DoubaoVoiceClientTest {
    private val accessKey = "AKLTUNITTEST"
    private val secretKey = "unit-test-secret"
    private val fixedNow = Instant.parse("2026-10-17T12:00:00Z")
    private fun MockWebServer.startLoopback() = start(InetAddress.getByName("127.0.0.1"), 0)
    private fun endpoint(server: MockWebServer) = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    private fun client(server: MockWebServer, transport: OkHttpClient = OkHttpClient()) =
        DoubaoVoiceClient(transport, endpoint(server)) { fixedNow }
    private fun voice(id: String, resource: String, name: String = "测试音色 $id", description: String = "官方描述") = buildJsonObject {
        put("VoiceType", id); put("Name", name); put("ResourceID", resource); put("Description", description)
        put("Languages", JsonArray(listOf(buildJsonObject { put("Language", "zh-cn") })))
    }
    private fun page(total: Int, vararg voices: kotlinx.serialization.json.JsonObject) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject {
            put("ResponseMetadata", buildJsonObject { put("RequestId", "synthetic-request") })
            put("Result", buildJsonObject { put("Total", total); put("Speakers", JsonArray(voices.toList())) })
        }.toString())

    @Test fun signedRequestsFetchBothResourcesAndAllPagesWithExplicitVersionMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page(31, *(0 until 30).map { voice("voice-$it", "seed-tts-1.0") }.toTypedArray()))
            server.enqueue(page(31, voice("last-page", "seed-tts-1.0")))
            server.enqueue(page(3,
                voice("voice-0", "seed-tts-2.0"), // The same ID can legitimately support both versions.
                voice("new_mars_voice", "seed-tts-2.0", "新音色", "语".repeat(2_500)), // Never infer version from a suffix.
                voice("de_male_sven_uranus_bigtts", "seed-tts-2.0", "Sven")))
            server.startLoopback()
            val result = client(server).listVoices(accessKey, secretKey)
            assertEquals(34, result.size)
            assertEquals("last-page", result[30].id)
            assertEquals(setOf("seed-tts-1.0", "seed-tts-2.0"), result.filter { it.id == "voice-0" }.map { it.resourceId }.toSet())
            assertEquals("seed-tts-2.0", result.single { it.id == "new_mars_voice" }.resourceId)
            assertEquals("语".repeat(2_000), result.single { it.id == "new_mars_voice" }.description)
            assertFalse(result.single { it.id == "de_male_sven_uranus_bigtts" }.bidirectional)
            assertTrue(result.single { it.id == "new_mars_voice" }.bidirectional)
            for ((index, expectedPage) in listOf(1, 2, 1).withIndex()) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/?Action=ListSpeakers&Version=2025-05-20", request.path)
                assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
                assertEquals("20261017T120000Z", request.getHeader("X-Date"))
                assertTrue(request.getHeader("Authorization")!!.startsWith("HMAC-SHA256 Credential=$accessKey/20261017/cn-beijing/speech_saas_prod/request,"))
                assertFalse(request.headers.toString().contains(secretKey))
                assertNull(request.getHeader("X-Api-Key"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(setOf("ResourceIDs", "Page", "Limit"), body.keys)
                assertEquals(if (index == 2) "seed-tts-2.0" else "seed-tts-1.0", body["ResourceIDs"]!!.jsonArray.single().jsonPrimitive.content)
                assertEquals(expectedPage, body["Page"]!!.jsonPrimitive.int)
                assertEquals(30, body["Limit"]!!.jsonPrimitive.int)
                assertFalse(body["Page"]!!.jsonPrimitive.isString)
                assertFalse(body["Limit"]!!.jsonPrimitive.isString)
                if (index == 0) assertEquals("b9ab9ca2547fa7d76644c92a037497bdac28e0c5965d756ca0383d218a7ea3bc", request.getHeader("X-Content-Sha256"))
            }
        }
    }

    @Test fun oneEmptyResourceStillRequestsAndReturnsTheOtherResource() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page(0)); server.enqueue(page(1, voice("second", "seed-tts-2.0"))); server.startLoopback()
            assertEquals(listOf("second"), client(server).listVoices(accessKey, secretKey).map { it.id })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun bothEmptyResourcesFailSoAnExistingCatalogueIsNotReplacedWithNothing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page(0)); server.enqueue(page(0)); server.startLoopback()
            val failure = runCatching { client(server).listVoices(accessKey, secretKey) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("列表为空"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun secondResourceFailureDoesNotReturnPartialResultsOrEchoErrorCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page(1, voice("first", "seed-tts-1.0")))
            server.enqueue(MockResponse().setResponseCode(403).setBody("$accessKey $secretKey"))
            server.startLoopback()
            val failure = runCatching { client(server).listVoices(accessKey, secretKey) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("403"))
            assertNoCredentials(failure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun apiErrorInHttpSuccessIsRejectedAndDoesNotExposeServerMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ResponseMetadata":{"Error":{"Code":"$accessKey","Message":"$secretKey"}},"Result":{"Total":0,"Speakers":[]}}"""))
            server.startLoopback()
            val failure = runCatching { client(server).listVoices(accessKey, secretKey) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("接口报告错误"))
            assertNoCredentials(failure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun repeatedOrMissingPagesAndChangingTotalsFailBeforeAnyResultIsReturned() = runBlocking {
        val scenarios = listOf(
            page(2, voice("first", "seed-tts-1.0")) to page(2, voice("first", "seed-tts-1.0")),
            page(2, voice("first", "seed-tts-1.0")) to page(2),
            page(2, voice("first", "seed-tts-1.0")) to page(3, voice("second", "seed-tts-1.0"))
        )
        for ((first, second) in scenarios) MockWebServer().use { server ->
            server.enqueue(first); server.enqueue(second); server.startLoopback()
            val failure = runCatching { client(server).listVoices(accessKey, secretKey) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(2, server.requestCount)
            assertNoCredentials(failure!!)
        }
    }

    @Test fun malformedFieldsMissingExplicitResourceAndOversizedTotalsAreRejected() = runBlocking {
        val responses = listOf(
            "not-json $accessKey $secretKey",
            """{"Result":{"Total":"1","Speakers":[]}}""",
            """{"Result":{"Total":5001,"Speakers":[]}}""",
            """{"Result":{"Total":1,"Speakers":[{"VoiceType":"id","Name":"voice"}]}}""",
            """{"Result":{"Total":1,"Speakers":[{"VoiceType":1,"Name":"voice","ResourceID":"seed-tts-1.0"}]}}"""
        )
        for (response in responses) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response)); server.startLoopback()
            val failure = runCatching { client(server).listVoices(accessKey, secretKey) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertNoCredentials(failure!!)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun wrongResourceAndTooManyRowsAreRejected() = runBlocking {
        val responses = listOf(page(1, voice("wrong-version", "seed-tts-2.0")),
            page(31, *(0 until 31).map { voice("voice-$it", "seed-tts-1.0") }.toTypedArray()))
        for (response in responses) MockWebServer().use { server ->
            server.enqueue(response); server.startLoopback()
            assertTrue(runCatching { client(server).listVoices(accessKey, secretKey) }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedBodyFailsBoundedlyEvenWithoutContentLength() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(1_048_577), 8_192)); server.startLoopback()
            val failure = runCatching { client(server).listVoices(accessKey, secretKey) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("大小超过限制"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectsRemainDisabledForInjectedClients() = runBlocking {
        MockWebServer().use { server ->
            server.startLoopback()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/unexpected")))
            server.enqueue(page(0))
            val failure = runCatching { client(server, OkHttpClient.Builder().followRedirects(true).build())
                .listVoices(accessKey, secretKey) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("307"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationClosesBlockedBodyAndCancelsCallImmediately() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{").setHeader("Content-Length", 8_192)); server.startLoopback()
            val headersArrived = CompletableDeferred<Unit>()
            val activeCall = AtomicReference<Call>()
            val transport = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call) }
                override fun responseHeadersEnd(call: Call, response: Response) { headersArrived.complete(Unit) }
            }).build()
            var returned = false
            val job = launch { client(server, transport).listVoices(accessKey, secretKey); returned = true }
            withTimeout(2_000) { headersArrived.await() }
            withTimeout(1_000) { job.cancelAndJoin() }
            assertTrue(activeCall.get().isCanceled())
            assertFalse(returned)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun shorterReadTimeoutIsPreservedAndNoPartialPageIsReturned() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{").setHeader("Content-Length", 8_192)); server.startLoopback()
            val transport = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            val failure = withTimeout(2_000) { runCatching { client(server, transport).listVoices(accessKey, secretKey) }.exceptionOrNull() }
            assertTrue(failure is IOException)
            assertNoCredentials(failure!!)
        }
    }

    @Test fun missingOrInvalidCredentialsFailWithoutSendingRequest() = runBlocking {
        MockWebServer().use { server ->
            server.startLoopback()
            for ((ak, sk) in listOf("" to secretKey, accessKey to "", "invalid\nkey" to secretKey)) {
                assertTrue(runCatching { client(server).listVoices(ak, sk) }.exceptionOrNull() is IOException)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun arbitraryRemoteEndpointsCannotReceiveIamCredentials() {
        assertTrue(runCatching { DoubaoVoiceClient(endpoint = "https://example.com/") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DoubaoVoiceClient(endpoint = "http://open.volcengineapi.com/") }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun assertNoCredentials(failure: Throwable) {
        assertFalse(failure.stackTraceToString().contains(accessKey))
        assertFalse(failure.stackTraceToString().contains(secretKey))
    }
}
