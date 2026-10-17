package cn.rolechat.app.speech

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechTextSegmenterTest {
    @Test fun `sentences emit immediately and final fragment emits once`() {
        val splitter = SpeechTextSegmenter()
        assertEquals(listOf("你好。", "明天见！"), splitter.append("你好。明天见！剩余尾段"))
        assertEquals(listOf("剩余尾段"), splitter.finish())
        assertTrue(splitter.finish().isEmpty())
    }

    @Test fun `A and B share one buffer without repeating the prefix`() {
        val splitter = SpeechTextSegmenter()
        val output = mutableListOf<String>()
        output += splitter.append("我觉得你现在最需要的是先把") // A's retained prefix.
        assertTrue(output.isEmpty())
        output += splitter.append("事情分开来看。然后我们再慢慢讨论") // B's new continuation.
        output += splitter.finish()
        assertEquals("我觉得你现在最需要的是先把事情分开来看。然后我们再慢慢讨论", output.joinToString(""))
        assertEquals(2, output.size)
    }

    @Test fun `length limit counts code points and does not split emoji surrogate pairs`() {
        val splitter = SpeechTextSegmenter(maxCodePoints = 4, softBoundaryMinCodePoints = 2)
        val text = "甲🙂乙🌍丙🚀丁😀戊"
        val output = splitter.append(text) + splitter.finish()
        assertEquals(text, output.joinToString(""))
        assertTrue(output.all { it.codePointCount(0, it.length) <= 4 })
        assertTrue(output.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }

    @Test fun `split surrogate is retained across network chunks and idle flush`() {
        val splitter = SpeechTextSegmenter()
        assertTrue(splitter.append("\uD83D").isEmpty())
        assertFalse(splitter.hasFlushableText)
        assertTrue(splitter.flush().isEmpty())
        assertEquals(listOf("😃。"), splitter.append("\uDE03。"))
        assertTrue(splitter.finish().isEmpty())
    }

    @Test fun `decimal split across chunks is not mistaken for a sentence end`() {
        val splitter = SpeechTextSegmenter()
        assertTrue(splitter.append("价格是3.").isEmpty())
        assertEquals(listOf("价格是3.14元。"), splitter.append("14元。"))
    }

    @Test fun `long punctuation-free text stays within the HTTP segment limit`() {
        val splitter = SpeechTextSegmenter()
        val text = "天地玄黄🙂".repeat(300)
        val output = splitter.append(text) + splitter.finish()
        assertEquals(text, output.joinToString(""))
        assertTrue(output.all { it.codePointCount(0, it.length) in 1..96 })
    }

    @Test fun `idle flush preserves an unfinished Unicode tail for the next model`() {
        val splitter = SpeechTextSegmenter()
        assertTrue(splitter.append("第一段\uD83D").isEmpty())
        assertEquals(listOf("第一段"), splitter.flush())
        assertEquals(listOf("🙂来了。"), splitter.append("\uDE42来了。"))
        assertTrue(splitter.finish().isEmpty())
    }

    @Test fun `malformed terminal surrogate becomes replacement instead of invalid JSON input`() {
        val splitter = SpeechTextSegmenter()
        splitter.append("内容\uD83D")
        assertEquals(listOf("内容\uFFFD"), splitter.finish())
    }

    @Test fun `idle deadline flushes a short A prefix and B follows without duplication`() = runTest {
        val input = Channel<String>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        val job = launch { segmentedSpeechText(input, idleMillis = 600).collect { output += it } }
        input.send("前半句")
        runCurrent()
        advanceTimeBy(599)
        assertTrue(output.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("前半句"), output)
        input.send("接着说完。")
        input.close()
        job.join()
        assertEquals("前半句接着说完。", output.joinToString(""))
    }

    @Test fun `empty and whitespace-only messages produce no synthesis segments`() = runTest {
        val empty = Channel<String>(Channel.UNLIMITED).apply { close() }
        assertTrue(segmentedSpeechText(empty).toList().isEmpty())
        val whitespace = Channel<String>(Channel.UNLIMITED).apply { trySend(" \n\t "); close() }
        assertTrue(segmentedSpeechText(whitespace).toList().isEmpty())
    }

    @Test fun `cancellation discards pending text rather than synthesizing a stale tail`() = runTest {
        val input = Channel<String>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        val job = launch { segmentedSpeechText(input).collect { output += it } }
        input.send("尚未发送的尾段")
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(1000)
        assertTrue(output.isEmpty())
        input.cancel()
    }

    @Test fun `slow synthesis applies backpressure and segments remain sequential`() = runTest {
        val input = Channel<String>(Channel.UNLIMITED).apply { trySend("第一句。第二句。第三句。"); close() }
        val output = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            segmentedSpeechText(input).collect {
                output += it
                if (output.size == 1) release.await()
            }
        }
        runCurrent()
        assertEquals(listOf("第一句。"), output)
        release.complete(Unit)
        job.join()
        assertEquals(listOf("第一句。", "第二句。", "第三句。"), output)
    }

    @Test fun `failed source does not flush its unfinished tail`() = runTest {
        val input = Channel<String>(Channel.UNLIMITED).apply { close(IOException("cancelled source")) }
        val output = mutableListOf<String>()
        val error = runCatching { segmentedSpeechText(input, firstText = "未完成").collect { output += it } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(output.isEmpty())
    }
}
