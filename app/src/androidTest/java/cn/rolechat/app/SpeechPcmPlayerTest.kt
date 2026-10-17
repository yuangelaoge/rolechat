package cn.rolechat.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.rolechat.app.speech.AndroidSpeechPcmPlayer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline AudioTrack smoke tests; they verify playback lifecycle, not perceived audio quality. */
@RunWith(AndroidJUnit4::class)
class SpeechPcmPlayerTest {
    @Test fun shortPcmWithOddNetworkFramesPlaysOnceAndDrainsNormally() = runBlocking {
        val pcm = tone(durationMillis = 150)
        val audio = fragmented(pcm, chunkBytes = 97)
        val player = AndroidSpeechPcmPlayer()
        val starts = AtomicInteger()
        val consumed = AtomicInteger()
        try {
            withTimeout(5_000) {
                withContext(Dispatchers.IO) {
                    player.play(audio, SAMPLE_RATE,
                        onConsumed = { consumed.addAndGet(it) }, onStarted = { starts.incrementAndGet() })
                }
            }
            assertEquals("One utterance must announce playback only once", 1, starts.get())
            assertEquals("Odd frame boundaries must not drop or duplicate PCM bytes", pcm.size, consumed.get())
        } finally {
            player.stop()
            audio.cancel()
        }
    }

    @Test fun stopFlushesActivePlaybackAndCancellationLeavesNoWriterRunning() = runBlocking {
        val pcm = tone(durationMillis = 5_000)
        val audio = fragmented(pcm, chunkBytes = 2047)
        val player = AndroidSpeechPcmPlayer()
        val started = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val consumed = AtomicInteger()
        val playback = launch(Dispatchers.IO) {
            player.play(audio, SAMPLE_RATE, onConsumed = { consumed.addAndGet(it) }, onStarted = {
                starts.incrementAndGet()
                started.complete(Unit)
            })
        }
        try {
            withTimeout(5_000) { started.await() }
            assertFalse("The long utterance should still be playing", playback.isCompleted)
            // stop() must synchronously flush device audio before cancellation tears down the job.
            player.stop()
            withTimeout(2_000) { playback.cancelAndJoin() }
            assertTrue(playback.isCompleted)
            assertTrue(playback.isCancelled)
            assertEquals(1, starts.get())
            assertTrue("Stopping must not write the rest of the queued utterance", consumed.get() < pcm.size)
            val bytesAtStop = consumed.get()
            delay(100)
            assertEquals("No background writer may consume more PCM after cancellation completes", bytesAtStop, consumed.get())
        } finally {
            player.stop()
            playback.cancelAndJoin()
            audio.cancel()
        }
    }

    private fun fragmented(pcm: ByteArray, chunkBytes: Int): Channel<ByteArray> =
        Channel<ByteArray>(Channel.UNLIMITED).apply {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + chunkBytes, pcm.size)
                check(trySend(pcm.copyOfRange(offset, end)).isSuccess)
                offset = end
            }
            close()
        }

    private fun tone(durationMillis: Int): ByteArray {
        val frames = SAMPLE_RATE * durationMillis / 1000
        return ByteArray(frames * 2).also { bytes ->
            repeat(frames) { frame ->
                val sample = (sin(2 * PI * 220 * frame / SAMPLE_RATE) * 256).toInt()
                bytes[frame * 2] = sample.toByte()
                bytes[frame * 2 + 1] = (sample shr 8).toByte()
            }
        }
    }

    companion object { private const val SAMPLE_RATE = 24_000 }
}
