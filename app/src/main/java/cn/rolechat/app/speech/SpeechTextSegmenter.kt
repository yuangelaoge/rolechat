package cn.rolechat.app.speech

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Incremental committed-text buffer, shared across the A -> B handoff of one role message. */
internal class SpeechTextSegmenter(
    private val maxCodePoints: Int = 96,
    private val softBoundaryMinCodePoints: Int = 24
) {
    private val pending = StringBuilder()

    init {
        require(maxCodePoints > 0)
        require(softBoundaryMinCodePoints in 1..maxCodePoints)
    }

    val hasFlushableText: Boolean
        get() = pending.length > if (pending.lastOrNull()?.let(Character::isHighSurrogate) == true) 1 else 0

    fun append(text: String): List<String> {
        pending.append(text)
        val ready = mutableListOf<String>()
        while (true) {
            var cursor = 0
            var points = 0
            var boundary = -1
            while (cursor < pending.length) {
                val first = pending[cursor]
                // SSE implementations occasionally split a surrogate pair across chunks.
                if (Character.isHighSurrogate(first) && cursor == pending.lastIndex) break
                val cp = Character.codePointAt(pending, cursor)
                cursor += Character.charCount(cp)
                points++
                val sentenceEnd = cp in STRONG_BOUNDARIES ||
                    (cp == '.'.code && cursor < pending.length && pending[cursor].isWhitespace())
                val phraseEnd = points >= softBoundaryMinCodePoints && cp in SOFT_BOUNDARIES
                if (sentenceEnd || phraseEnd || points >= maxCodePoints) {
                    boundary = cursor
                    break
                }
            }
            if (boundary < 0) break
            ready += takePrefix(boundary)
        }
        return ready
    }

    /** Idle flush does not discard or split an unfinished Unicode surrogate pair. */
    fun flush(): List<String> {
        val end = pending.length - if (pending.lastOrNull()?.let(Character::isHighSurrogate) == true) 1 else 0
        return if (end == 0) emptyList() else listOf(takePrefix(end))
    }

    /** Called only after the role message finishes, never at the A/B model boundary. */
    fun finish(): List<String> {
        if (pending.isEmpty()) return emptyList()
        return listOf(takePrefix(pending.length))
    }

    private fun takePrefix(end: Int): String {
        val result = pending.substring(0, end)
        pending.delete(0, end)
        // Keep valid Unicode intact; replace only malformed, unmatched surrogates.
        return buildString {
            var offset = 0
            while (offset < result.length) {
                val ch = result[offset]
                if (Character.isHighSurrogate(ch) && offset + 1 < result.length && Character.isLowSurrogate(result[offset + 1])) {
                    append(ch).append(result[offset + 1]); offset += 2
                } else {
                    append(if (Character.isSurrogate(ch)) '\uFFFD' else ch); offset++
                }
            }
        }
    }

    companion object {
        private val STRONG_BOUNDARIES = setOf('。', '！', '？', '!', '?', '\n', '；', ';').map(Char::code).toSet()
        private val SOFT_BOUNDARIES = setOf('，', ',', '、', '：', ':').map(Char::code).toSet()
    }
}

/**
 * One segment at a time: the caller's HTTP synthesis/PCM backpressure also throttles text draining.
 * Source closure flushes the final fragment; cancellation and source failure never synthesize a tail.
 */
internal fun segmentedSpeechText(
    input: ReceiveChannel<String>,
    firstText: String = "",
    idleMillis: Long = 600,
    inputTimeoutMillis: Long = 180_000,
    segmenter: SpeechTextSegmenter = SpeechTextSegmenter(),
    onConsumed: (Int) -> Unit = {}
): Flow<String> = flow {
    require(idleMillis > 0 && inputTimeoutMillis > 0)
    suspend fun output(parts: List<String>) {
        for (part in parts) if (part.isNotBlank()) emit(part)
    }
    output(segmenter.append(firstText))
    while (true) {
        val next = if (segmenter.hasFlushableText) {
            withTimeoutOrNull(idleMillis) { input.receiveCatching() }
        } else {
            withTimeout(inputTimeoutMillis) { input.receiveCatching() }
        }
        if (next == null) {
            output(segmenter.flush())
            continue
        }
        next.exceptionOrNull()?.let { throw it }
        val text = next.getOrNull() ?: break
        onConsumed(text.length)
        output(segmenter.append(text))
    }
    output(segmenter.finish())
}
