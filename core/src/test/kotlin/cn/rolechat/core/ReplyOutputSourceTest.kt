package cn.rolechat.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyOutputSourceTest {
    private fun actor() = Character(id = "actor", name = "角色",
        primary = ModelConfig(model = "model-a", thinking = ThinkingMode.OFF),
        secondary = ModelConfig(model = "model-b", thinking = ThinkingMode.OFF),
        dualModel = true, cutMin = 2, cutMax = 2, firstTextTimeoutSec = 2, stallTimeoutSec = 1,
        secondaryOutputSource = ReplyOutputSource.REASONING)

    private fun state(character: Character = actor(), echoPrefix: Boolean = false) = AppState(
        providers = listOf(Provider(id = "siliconflow", echoPrefix = echoPrefix)),
        characters = listOf(character),
        rooms = listOf(ChatRoom(id = "room", members = listOf(Member(character.id, 0, true)), autoSpeak = true)),
        tts = TtsSettings(enabled = true))

    @Test fun compatibilityBUsesOnlyReasoningAsBodyWithPrefixDeduplicationSpeechAndFutureHistory() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway { request ->
            if (request.model.model == "model-a") {
                emit(StreamEvent.Reasoning("A 的独立思考"))
                emit(StreamEvent.Text("😀你不会显示的A尾巴"))
            } else {
                emit(StreamEvent.Thinking)
                emit(StreamEvent.Text("不采用的 content"))
                gate.await()
                emit(StreamEvent.Reasoning("\uD83D"))
                emit(StreamEvent.Text("不能干扰前缀匹配"))
                emit(StreamEvent.Reasoning("\uDE00你"))
                emit(StreamEvent.Reasoning("好\uD83D"))
                emit(StreamEvent.Thinking)
                emit(StreamEvent.Reasoning("\uDE0A"))
                emit(StreamEvent.Text("好😊"))
            }
        }
        val store = StateStore(state(echoPrefix = true))
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "开始")
        runCurrent()
        val streaming = store.snapshot().messages.last()
        assertEquals("😀你", streaming.text)
        assertEquals(MessageStatus.GENERATING_B, streaming.status)
        assertEquals(listOf(ReplyOutputSource.CONTENT, ReplyOutputSource.REASONING), streaming.segments.map { it.outputSource })
        assertEquals(listOf("A 的独立思考", ""), streaming.segments.map { it.reasoning })
        assertEquals(ThinkingMode.OFF, gateway.requests[0].model.thinking)
        assertEquals(ThinkingMode.ON, gateway.requests[1].model.thinking)
        assertEquals(ThinkingMode.OFF, store.snapshot().characters.single().secondary.thinking)
        assertEquals("😀你", gateway.requests[1].prefix)
        gate.complete(Unit)
        advanceUntilIdle()
        val reply = store.snapshot().messages.last()
        assertEquals("😀你好😊", reply.text)
        assertEquals(listOf("😀你", "好😊"), reply.segments.map { it.text })
        assertEquals(listOf("A 的独立思考", ""), reply.segments.map { it.reasoning })
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals(reply.text, speech.text[reply.id])
        assertEquals(listOf(reply.id), speech.starts)
        assertEquals(listOf(reply.id), speech.finishes)

        engine.send("room", "继续")
        advanceUntilIdle()
        assertTrue(gateway.requests[2].messages.any { it == PromptMessage("assistant", "【${reply.senderName}】${reply.text}") })
        assertTrue(gateway.requests.all { request -> request.messages.none {
            it.content.contains("A 的独立思考") || it.content.contains("content") || it.content.contains("干扰前缀")
        } })
    }

    @Test fun defaultBAndSingleModelAStillSeparateReasoningAndRespectTheirThinkingConfiguration() = runTest {
        val scenarios = listOf(actor().copy(secondaryOutputSource = ReplyOutputSource.CONTENT), actor().copy(dualModel = false))
        for (character in scenarios) {
            val gateway = FakeGateway { request ->
                emit(StreamEvent.Reasoning("${request.model.model}的思考"))
                emit(StreamEvent.Text(if (request.model.model == "model-a") "前缀后文" else "接续正文"))
            }
            val store = StateStore(state(character))
            val speech = RecordingSpeech()
            ChatEngine(store, gateway, this, speech).send("room", "开始")
            advanceUntilIdle()
            val reply = store.snapshot().messages.last()
            assertEquals(if (character.dualModel) "前缀接续正文" else "前缀后文", reply.text)
            assertEquals(if (character.dualModel) 2 else 1, gateway.requests.size)
            assertTrue(gateway.requests.all { it.model.thinking == ThinkingMode.OFF })
            assertTrue(reply.segments.all { it.outputSource == ReplyOutputSource.CONTENT })
            assertTrue(reply.segments.all { it.reasoning.endsWith("的思考") })
            assertEquals(reply.text, speech.text[reply.id])
        }
    }

    @Test fun reasoningBodyAdvancesStallDeadlineAndCompletesAfterTheOriginalFirstTextDeadline() = runTest {
        val gateway = FakeGateway { request ->
            if (request.model.model == "model-a") emit(StreamEvent.Text("前缀"))
            else {
                delay(1_500)
                emit(StreamEvent.Reasoning("正文"))
                repeat(3) { delay(600); emit(StreamEvent.Reasoning("续")) }
            }
        }
        val store = StateStore(state())
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "开始")
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(MessageStatus.GENERATING_B, store.snapshot().messages.last().status)
        assertEquals(setOf("room"), engine.busyRooms.value)
        advanceUntilIdle()
        val reply = store.snapshot().messages.last()
        assertEquals("前缀正文续续续", reply.text)
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals("", reply.segments.last().reasoning)
        assertEquals(reply.text, speech.text[reply.id])
    }

    @Test fun ignoredContentAndThinkingCannotExtendCompatibilityBFirstTextOrStallDeadline() = runTest {
        for (bodyStarted in listOf(false, true)) {
            val began = testScheduler.currentTime
            var bCancelledAt = -1L
            val gateway = FakeGateway { request ->
                if (request.model.model == "model-a") emit(StreamEvent.Text("前缀"))
                else try {
                    if (bodyStarted) emit(StreamEvent.Reasoning("已开始"))
                    while (true) {
                        emit(StreamEvent.Text("这个通道不算正文"))
                        emit(StreamEvent.Thinking)
                        delay(100)
                    }
                } finally { bCancelledAt = testScheduler.currentTime }
            }
            val store = StateStore(state())
            val engine = ChatEngine(store, gateway, this)
            engine.send("room", "开始")
            runCurrent()
            assertEquals(MessageStatus.GENERATING_B, store.snapshot().messages.last().status)
            val timeout = if (bodyStarted) 1_000L else 2_000L
            advanceTimeBy(timeout + 1)
            runCurrent()
            val reply = store.snapshot().messages.last()
            assertEquals(timeout, bCancelledAt - began)
            assertEquals(if (bodyStarted) "前缀已开始" else "前缀", reply.text)
            assertEquals(MessageStatus.ERROR, reply.status)
            assertEquals(if (bodyStarted) "正文输出停滞超时" else "等待正文超时", reply.error)
            assertEquals("", reply.segments.last().reasoning)
            assertEquals(SegmentStatus.ERROR, reply.segments.last().status)
            assertTrue(engine.busyRooms.value.isEmpty())
        }
    }

    @Test fun retryPreservesAOnceAndFullRegenerationBuildsANewCompatibilityBPrefixFromOriginalHistory() = runTest {
        var aCalls = 0
        var bCalls = 0
        val gateway = FakeGateway { request ->
            if (request.model.model == "model-a") {
                aCalls++
                emit(StreamEvent.Reasoning("A思考$aCalls"))
                emit(StreamEvent.Text(if (aCalls == 1) "旧前缀" else "新回答"))
            } else {
                bCalls++
                emit(StreamEvent.Text("忽略的B通道$bCalls"))
                emit(StreamEvent.Reasoning(request.prefix + when (bCalls) {
                    1 -> "失败前正文"; 2 -> "重试正文"; else -> "重生正文"
                }))
                if (bCalls == 1) throw java.io.IOException("模拟B连接中断")
            }
        }
        val store = StateStore(state(echoPrefix = true))
        val speech = RecordingSpeech()
        val engine = ChatEngine(store, gateway, this, speech)
        engine.send("room", "原来的提问")
        advanceUntilIdle()
        val first = store.snapshot().messages.last()
        val originalA = first.segments.first()
        val history = gateway.requests.first().messages
        assertEquals("旧前失败前正文", first.text)
        assertEquals(MessageStatus.ERROR, first.status)
        val later = ChatMessage(id = "later", roomId = "room", text = "不得混入重试的后续消息")
        store.update { it.copy(messages = it.messages + later) }

        engine.retry(first.id)
        advanceUntilIdle()
        val retry = store.snapshot().messages.single { it.id == first.id }
        assertEquals(listOf("model-a", "model-b", "model-b"), gateway.requests.map { it.model.model })
        assertEquals(originalA, retry.segments.first())
        assertEquals("旧前重试正文", retry.text)
        assertEquals("", retry.segments.last().reasoning)
        assertEquals(ReplyOutputSource.REASONING, retry.segments.last().outputSource)
        assertEquals(MessageStatus.COMPLETE, retry.status)
        assertEquals(retry.text, speech.text[first.id])
        assertEquals("旧前", gateway.requests.last().prefix)

        engine.regenerate(first.id)
        advanceUntilIdle()
        val regenerated = store.snapshot().messages.single { it.id == first.id }
        assertEquals(listOf("model-a", "model-b", "model-b", "model-a", "model-b"), gateway.requests.map { it.model.model })
        assertEquals("", gateway.requests[3].prefix)
        assertEquals("新回", gateway.requests[4].prefix)
        assertEquals("新回重生正文", regenerated.text)
        assertEquals(listOf("A思考2", ""), regenerated.segments.map { it.reasoning })
        assertEquals(listOf(ReplyOutputSource.CONTENT, ReplyOutputSource.REASONING), regenerated.segments.map { it.outputSource })
        assertEquals(MessageStatus.COMPLETE, regenerated.status)
        assertEquals(regenerated.text, speech.text[first.id])
        assertEquals(first.id, regenerated.id)
        assertEquals(first.createdAt, regenerated.createdAt)
        assertEquals(first.contextMessageIds, regenerated.contextMessageIds)
        assertEquals(later, store.snapshot().messages.last())
        assertTrue(gateway.requests.all { it.messages == history })
        assertTrue(gateway.requests.filter { it.model.model == "model-b" }.all { it.model.thinking == ThinkingMode.ON })
    }

    private class FakeGateway(val respond: suspend FlowCollector<StreamEvent>.(GenerationRequest) -> Unit) : ChatGateway {
        val requests = mutableListOf<GenerationRequest>()
        override fun stream(request: GenerationRequest): Flow<StreamEvent> = flow {
            requests += request
            respond(request)
        }
    }

    private class RecordingSpeech : SpeechSink {
        val starts = mutableListOf<String>()
        val finishes = mutableListOf<String>()
        val text = mutableMapOf<String, String>()
        override fun begin(messageId: String, character: Character) { starts += messageId; text[messageId] = "" }
        override fun append(messageId: String, text: String) { this.text[messageId] = this.text[messageId].orEmpty() + text }
        override fun finish(messageId: String) { finishes += messageId }
        override fun stop(roomMessageIds: Set<String>) = Unit
    }
}
