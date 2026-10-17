package cn.rolechat.app.speech

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedSpeechPipelineTest {
    private class Audio(val id: Int) {
        val chunks = Channel<Int>(1)
        var disposals = 0
        fun dispose() { disposals++; chunks.cancel() }
    }

    @Test fun `playback synthesis and extraction overlap while keeping begin order`() = runTest {
        val preparation = mutableListOf<Int>()
        val synthesis = mutableListOf<Int>()
        val playback = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val releasePlay = CompletableDeferred<Unit>()
        val releaseSynthesis = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = {
                preparation += it
                if (it == 3) releasePreparation.await()
                it
            },
            synthesize = { item, _, publish ->
                synthesis += item
                publish(Audio(item))
                if (item == 2) releaseSynthesis.await()
            },
            play = { item, _ -> playback += item; if (item == 1) releasePlay.await() },
            disposeAudio = Audio::dispose,
            onFailure = { _, failure -> failures += failure }, onFinished = { finished += it })
        try {
            (1..4).forEach { assertTrue(pipeline.enqueue("$it", it)) }
            runCurrent()
            assertEquals(listOf(1, 2, 3), preparation)
            assertEquals(listOf(1, 2), synthesis)
            assertEquals(listOf(1), playback)
            assertTrue(finished.isEmpty())
            // Item 2 can start playing while its synthesis callback is still open.
            releasePlay.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2), playback)
            assertEquals(listOf(1), finished)
            releasePreparation.complete(Unit)
            releaseSynthesis.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2, 3, 4), preparation)
            assertEquals(listOf(1, 2, 3, 4), playback)
            assertEquals(listOf(1, 2, 3, 4), finished)
            assertTrue(failures.isEmpty())
        } finally { pipeline.close() }
    }

    @Test fun `slow playback allows only three prepared items and two audio handles`() = runTest {
        val prepared = mutableListOf<Int>()
        val synthesized = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        val handles = mutableListOf<Audio>()
        val release = CompletableDeferred<Unit>()
        var peakLiveAudio = 0
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { prepared += it; it },
            synthesize = { item, _, publish ->
                synthesized += item
                val audio = Audio(item).also(handles::add)
                peakLiveAudio = maxOf(peakLiveAudio, handles.count { it.disposals == 0 })
                publish(audio)
            },
            play = { item, _ -> played += item; if (item == 1) release.await() },
            disposeAudio = Audio::dispose, onFailure = { _, _ -> }, onFinished = {})
        try {
            (1..20).forEach { assertTrue(pipeline.enqueue("$it", it)) }
            runCurrent()
            assertEquals(listOf(1, 2, 3), prepared)
            assertEquals(listOf(1, 2), synthesized)
            assertEquals(listOf(1), played)
            assertEquals(2, handles.size)
            release.complete(Unit)
            runCurrent()
            assertEquals((1..20).toList(), played)
            assertEquals(2, peakLiveAudio)
            assertTrue(handles.all { it.disposals == 1 })
        } finally { pipeline.close() }
    }

    @Test fun `an unfinished earlier message cannot be overtaken by a later ready message`() = runTest {
        val firstComplete = CompletableDeferred<Unit>()
        val prepared = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { item -> if (item == 1) firstComplete.await(); prepared += item; item },
            synthesize = { item, _, publish -> publish(Audio(item)) },
            play = { item, _ -> played += item },
            disposeAudio = Audio::dispose, onFailure = { _, _ -> }, onFinished = {})
        try {
            assertTrue(pipeline.enqueue("first", 1))
            assertTrue(pipeline.enqueue("second", 2))
            runCurrent()
            assertTrue(prepared.isEmpty())
            assertTrue(played.isEmpty())
            firstComplete.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2), played)
        } finally { pipeline.close() }
    }

    @Test fun `errors in each stage and empty text skip only that item`() = runTest {
        val failures = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        val handles = mutableListOf<Audio>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { item -> when (item) { 1 -> throw IOException("extract"); 4 -> null; else -> item } },
            synthesize = { item, _, publish ->
                if (item == 2) throw IOException("synthesis")
                if (item != 5) publish(Audio(item).also(handles::add))
            },
            play = { item, _ -> played += item; if (item == 3) throw IOException("playback") },
            disposeAudio = Audio::dispose, onFailure = { item, _ -> failures += item },
            onFinished = { finished += it })
        try {
            (1..6).forEach { pipeline.enqueue("$it", it) }
            runCurrent()
            assertEquals(listOf(1, 2, 3), failures.sorted())
            assertEquals((1..6).toList(), finished.sorted())
            assertEquals(listOf(3, 6), played)
            assertTrue(handles.all { it.disposals == 1 })
        } finally { pipeline.close() }
    }

    @Test fun `network failure after publishing cancels current playback before reporting and advances`() = runTest {
        val failNetwork = CompletableDeferred<Unit>()
        val currentAudio = Audio(1)
        val played = mutableListOf<Int>()
        val failed = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { it },
            synthesize = { item, _, publish ->
                publish(if (item == 1) currentAudio else Audio(item))
                if (item == 1) { failNetwork.await(); throw IOException("connection lost") }
            },
            play = { item, _ -> played += item; if (item == 1) awaitCancellation() },
            disposeAudio = Audio::dispose,
            onFailure = { item, _ -> assertEquals(1, currentAudio.disposals); failed += item },
            onFinished = { finished += it })
        try {
            pipeline.enqueue("first", 1)
            pipeline.enqueue("second", 2)
            runCurrent()
            assertEquals(listOf(1), played)
            failNetwork.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2), played)
            assertEquals(listOf(1), failed)
            assertEquals(listOf(1, 2), finished)
        } finally { pipeline.close() }
    }

    @Test fun `stop all cancels three stages and late audio cannot enter a restarted queue`() = runTest {
        val releaseLate = CompletableDeferred<Unit>()
        val lateFinished = CompletableDeferred<Unit>()
        val handles = mutableListOf<Audio>()
        val played = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val failures = mutableListOf<Int>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { item -> if (item == 3) awaitCancellation(); item },
            synthesize = { item, _, publish ->
                publish(Audio(item).also(handles::add))
                if (item == 2) {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            releaseLate.await()
                            runCatching { publish(Audio(99).also(handles::add)) }
                            lateFinished.complete(Unit)
                        }
                    }
                }
            },
            play = { item, _ -> played += item; if (item == 1) awaitCancellation() },
            disposeAudio = Audio::dispose, onFailure = { item, _ -> failures += item },
            onFinished = { finished += it })
        try {
            (1..3).forEach { pipeline.enqueue("$it", it) }
            runCurrent()
            assertEquals(listOf(1), played)
            pipeline.cancelAll()
            assertEquals(listOf(1, 2, 3), finished)
            assertTrue(handles.all { it.disposals == 1 })
            // Reuse the first message ID before the obsolete network cleanup ends.
            assertTrue(pipeline.enqueue("1", 4))
            runCurrent()
            assertEquals(listOf(1, 4), played)
            releaseLate.complete(Unit)
            runCurrent()
            assertTrue(lateFinished.isCompleted)
            assertEquals(listOf(1, 4), played)
            assertEquals(listOf(1, 2, 3, 4), finished)
            assertTrue(handles.all { it.disposals == 1 })
            assertTrue(failures.isEmpty())
        } finally { releaseLate.complete(Unit); pipeline.close() }
    }

    @Test fun `stopping a pending item preserves the surrounding playback order`() = runTest {
        val release = CompletableDeferred<Unit>()
        val prepared = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { prepared += it; it },
            synthesize = { item, _, publish -> publish(Audio(item)) },
            play = { item, _ -> played += item; if (item == 1) release.await() },
            disposeAudio = Audio::dispose, onFailure = { _, _ -> }, onFinished = { finished += it })
        try {
            (1..5).forEach { pipeline.enqueue("$it", it) }
            runCurrent()
            pipeline.cancel(setOf("4"))
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2, 3, 5), prepared)
            assertEquals(listOf(1, 2, 3, 5), played)
            assertEquals((1..5).toList(), finished.sorted())
        } finally { pipeline.close() }
    }

    @Test fun `queue limit duplicate reservation and close are explicit rejections`() = runTest {
        val finished = mutableListOf<Int>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { awaitCancellation() },
            synthesize = { _, _, _ -> }, play = { _, _ -> }, disposeAudio = Audio::dispose,
            onFailure = { _, _ -> }, onFinished = { finished += it }, maxQueued = 3)
        assertTrue(pipeline.enqueue("1", 1))
        assertFalse(pipeline.enqueue("1", 9))
        assertTrue(pipeline.enqueue("2", 2))
        assertTrue(pipeline.enqueue("3", 3))
        assertFalse(pipeline.enqueue("4", 4))
        // No worker has run yet. Cancelling a full reservation queue must make
        // room for immediate replay without waiting for asynchronous draining.
        pipeline.cancelAll()
        assertTrue(pipeline.enqueue("4", 4))
        runCurrent()
        pipeline.close()
        pipeline.close()
        runCurrent()
        assertEquals(listOf(1, 2, 3, 4), finished)
        assertFalse(pipeline.enqueue("1", 10))
    }

    @Test fun `duplicate publication disposes both distinct handles once and advances`() = runTest {
        val handles = mutableListOf<Audio>()
        val failures = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { it },
            synthesize = { item, _, publish ->
                publish(Audio(item).also(handles::add))
                if (item == 1) publish(Audio(99).also(handles::add))
            },
            play = { item, _ -> played += item }, disposeAudio = Audio::dispose,
            onFailure = { item, _ -> failures += item }, onFinished = {})
        try {
            pipeline.enqueue("1", 1)
            pipeline.enqueue("2", 2)
            runCurrent()
            assertEquals(listOf(1), failures)
            assertEquals(2, played.last())
            assertTrue(handles.all { it.disposals == 1 })
        } finally { pipeline.close() }
    }

    @Test fun `stage timeout reports a failure rather than a user cancellation`() = runTest {
        val failures = mutableListOf<Throwable>()
        val finished = mutableListOf<Int>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { withTimeout(100) { delay(1_000); it } },
            synthesize = { _, _, _ -> }, play = { _, _ -> }, disposeAudio = Audio::dispose,
            onFailure = { _, failure -> failures += failure }, onFinished = { finished += it })
        try {
            pipeline.enqueue("1", 1)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(failures.single() is TimeoutCancellationException)
            assertEquals(listOf(1), finished)
        } finally { pipeline.close() }
    }

    @Test fun `bounded audio streams produce and consume before synthesis completes`() = runTest {
        val firstChunkPlayed = CompletableDeferred<Unit>()
        val networkDone = CompletableDeferred<Unit>()
        val consumed = mutableListOf<Int>()
        val audio = Audio(1)
        val pipeline = OrderedSpeechPipeline<Int, Int, Audio>(this,
            prepare = { it },
            synthesize = { _, _, publish ->
                publish(audio)
                audio.chunks.send(1)
                firstChunkPlayed.await()
                audio.chunks.send(2)
                audio.chunks.close()
                networkDone.complete(Unit)
            },
            play = { _, stream ->
                for (chunk in stream.chunks) {
                    consumed += chunk
                    if (chunk == 1) { assertFalse(networkDone.isCompleted); firstChunkPlayed.complete(Unit) }
                }
            },
            disposeAudio = Audio::dispose, onFailure = { _, _ -> }, onFinished = {})
        try {
            pipeline.enqueue("1", 1)
            runCurrent()
            assertEquals(listOf(1, 2), consumed)
            assertTrue(networkDone.isCompleted)
            assertEquals(1, audio.disposals)
        } finally { pipeline.close() }
    }

    @Test fun `concurrent enqueue stop and close finish each accepted item exactly once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accepted = ConcurrentHashMap.newKeySet<Int>()
        val completions = ConcurrentHashMap<Int, AtomicInteger>()
        val disposals = ConcurrentHashMap<Int, AtomicInteger>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val pipeline = OrderedSpeechPipeline<Int, Int, Int>(scope,
            prepare = { delay(1); it },
            synthesize = { item, _, publish -> publish(item); delay(1) },
            play = { _, _ -> delay(1) },
            disposeAudio = { disposals.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() },
            onFailure = { _, error -> failures.add(error) },
            onFinished = { completions.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() })
        try {
            (0 until 8).map { producer ->
                launch(Dispatchers.Default) {
                    repeat(40) { position ->
                        val id = producer * 40 + position
                        if (pipeline.enqueue("$id", id)) accepted += id
                        if (position % 2 == 0) pipeline.cancel(setOf("$id"))
                        if (position % 13 == 0) pipeline.cancelAll()
                    }
                }
            }.joinAll()
            pipeline.close()
            withTimeout(5_000) {
                while (completions.keys != accepted) delay(1)
            }
            assertTrue(accepted.isNotEmpty())
            assertTrue(completions.values.all { it.get() == 1 })
            assertTrue(disposals.values.all { it.get() == 1 })
            assertTrue(failures.isEmpty())
            assertFalse(pipeline.enqueue("new", 500))
        } finally { pipeline.close(); scope.cancel() }
    }
}
