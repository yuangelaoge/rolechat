package cn.rolechat.app.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** One player belongs to one utterance; play may be called only once. */
interface SpeechPcmPlayer {
    suspend fun play(
        audio: ReceiveChannel<ByteArray>,
        sampleRate: Int,
        onConsumed: (Int) -> Unit = {},
        onStarted: () -> Unit = {}
    )

    /** Immediately pauses and discards audio already buffered by the device. */
    fun stop()
}

class AndroidSpeechPcmPlayer : SpeechPcmPlayer {
    private val lock = Any()
    @Volatile private var cancelled = false
    private var used = false
    private var activeTrack: AudioTrack? = null

    override fun stop() {
        synchronized(lock) {
            cancelled = true
            activeTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
            }
        }
    }

    override suspend fun play(
        audio: ReceiveChannel<ByteArray>,
        sampleRate: Int,
        onConsumed: (Int) -> Unit,
        onStarted: () -> Unit
    ) {
        synchronized(lock) {
            ensureNotStopped()
            check(!used) { "每条语音只能使用一次播放器" }
            used = true
        }
        currentCoroutineContext().ensureActive()
        // A connection error or empty response must not allocate an Android audio track.
        val firstChunk: ByteArray
        while (true) {
            ensureNotStopped()
            val received = audio.receiveCatching()
            received.exceptionOrNull()?.let { throw it }
            val chunk = received.getOrNull() ?: return
            if (chunk.isNotEmpty()) {
                firstChunk = chunk
                break
            }
            onConsumed(0)
        }
        currentCoroutineContext().ensureActive()
        ensureNotStopped()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("设备不支持当前语音采样率")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, sampleRate / 2)).build()
        var bytesWritten = 0L
        var pendingByte: Byte? = null
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IOException("无法初始化语音播放器")
            synchronized(lock) {
                ensureNotStopped()
                activeTrack = track
                track.play()
            }
            var announced = false
            var nextChunk: ByteArray? = firstChunk
            while (nextChunk != null) {
                val chunk = nextChunk
                currentCoroutineContext().ensureActive()
                ensureNotStopped()
                // Network frames may split a 16-bit sample between two byte arrays.
                val data = pendingByte?.let { byteArrayOf(it) + chunk } ?: chunk
                val usable = data.size - data.size % 2
                pendingByte = if (usable < data.size) data.last() else null
                var offset = 0
                while (offset < usable) {
                    currentCoroutineContext().ensureActive()
                    val written = synchronized(lock) {
                        ensureNotStopped()
                        track.write(data, offset, usable - offset, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    if (written < 0) throw IOException("语音播放器写入失败（$written）")
                    if (written == 0) delay(8) else {
                        offset += written
                        bytesWritten += written
                        if (!announced) {
                            ensureNotStopped()
                            onStarted()
                            announced = true
                        }
                    }
                }
                onConsumed(chunk.size)
                val received = audio.receiveCatching()
                received.exceptionOrNull()?.let { throw it }
                nextChunk = received.getOrNull()
            }
            ensureNotStopped()
            if (pendingByte != null) throw IOException("语音 PCM 数据不完整")
            // Finishing the network stream must not cut off the hardware buffer's tail.
            val targetFrames = bytesWritten / 2
            withTimeout(30_000) {
                // MODE_STREAM can wait for its startup buffer even after play(). A
                // complete short utterance (e.g. 150 ms in a 250 ms buffer) cannot
                // reach that threshold itself. Prime the rest with silence at EOF.
                // bufferSizeInFrames is available on API 23; threshold setters need
                // API 31. Silence is not provider PCM and must not affect onConsumed
                // or the original-content target we wait for below.
                var silenceBytes = ((track.bufferSizeInFrames.toLong() - targetFrames).coerceAtLeast(0)) * 2
                if (targetFrames > 0 && silenceBytes > 0) {
                    val silence = ByteArray(minOf(silenceBytes, 8192L).toInt())
                    while (silenceBytes > 0 && (track.playbackHeadPosition.toLong() and 0xffffffffL) < targetFrames) {
                        currentCoroutineContext().ensureActive()
                        val written = synchronized(lock) {
                            ensureNotStopped()
                            track.write(silence, 0, minOf(silenceBytes, silence.size.toLong()).toInt(), AudioTrack.WRITE_NON_BLOCKING)
                        }
                        if (written < 0) throw IOException("语音播放器写入失败（$written）")
                        if (written == 0) delay(8) else silenceBytes -= written
                    }
                }
                while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < targetFrames) {
                    currentCoroutineContext().ensureActive()
                    ensureNotStopped()
                    delay(15)
                }
            }
        } finally {
            synchronized(lock) {
                if (activeTrack === track) activeTrack = null
                runCatching { track.pause() }
                runCatching { track.flush() }
                track.release()
            }
        }
    }

    private fun ensureNotStopped() {
        if (cancelled) throw CancellationException("本条语音已停止")
    }
}
