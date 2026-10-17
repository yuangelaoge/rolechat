package cn.rolechat.app.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore

/**
 * Reserves playback order when an utterance begins, before its text is complete.
 * Preparation, synthesis and playback each have one worker. Synthesis publishes a
 * stream handle once, so playback can consume it while synthesis is still running.
 *
 * At most [maxPrefetch] utterances enter preparation, and at most two own an audio
 * slot (the playing stream and the next stream). A slot remains occupied until
 * both synthesis and playback finish. All public methods are thread safe.
 *
 * Callbacks must cooperate with cancellation. [disposeAudio] must also stop/wake
 * readers and writers of its stream. [onFinished] runs once for every accepted
 * item, including failures and cancellation; rejected enqueue calls retain their
 * ownership at the caller. No Android API is used here.
 */
class OrderedSpeechPipeline<T : Any, P : Any, A : Any>(
    scope: CoroutineScope,
    private val prepare: suspend (T) -> P?,
    private val synthesize: suspend (T, P, publish: suspend (A) -> Unit) -> Unit,
    private val play: suspend (T, A) -> Unit,
    private val disposeAudio: (A) -> Unit,
    private val onFailure: (T, Throwable) -> Unit,
    private val onFinished: (T) -> Unit,
    maxPrefetch: Int = 3,
    private val maxQueued: Int = 32,
) {
    private inner class Entry(val key: String, val item: T) {
        val job = SupervisorJob(pipelineJob)
        val stopped = CompletableDeferred<Unit>()
        @Volatile var terminal = false
        var windowHeld = false
        var audioSlotHeld = false
        var audio: A? = null
        var published = false
        var synthDone = false
        var playDone = false
    }

    private sealed interface StageResult<out R> {
        data class Value<R>(val value: R) : StageResult<R>
        data object Cancelled : StageResult<Nothing>
        data class Failed(val cause: Throwable) : StageResult<Nothing>
    }

    private val lock = Any()
    private val pipelineJob = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + pipelineJob)
    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false
    private val window = Semaphore(maxPrefetch)
    private val audioSlots = Semaphore(2)
    // Removing cancelled reservations synchronously makes a full queue reusable
    // immediately. A bounded Channel<Entry> would retain cancelled placeholders
    // until a worker gets CPU time and could reject a stop -> replay operation.
    private val pending = ArrayDeque<Entry>()
    private val pendingChanged = Channel<Unit>(Channel.CONFLATED)
    private val prepared = Channel<Pair<Entry, P>>(maxPrefetch)
    private val playable = Channel<Entry>(2)

    init {
        require(maxPrefetch >= 2) { "At least two prefetch positions are required" }
        require(maxQueued >= maxPrefetch) { "The queue must contain the prefetch window" }
        workers.launch { worker { prepareLoop() } }
        workers.launch { worker { synthesisLoop() } }
        workers.launch { worker { playbackLoop() } }
    }

    fun enqueue(key: String, item: T): Boolean = synchronized(lock) {
        if (closed || !pipelineJob.isActive || entries.size >= maxQueued || entries.containsKey(key)) return false
        val entry = Entry(key, item)
        entries[key] = entry
        pending.addLast(entry)
        pendingChanged.trySend(Unit)
        true
    }

    fun cancel(keys: Set<String>) {
        val selected = synchronized(lock) { keys.mapNotNull(entries::get) }
        selected.forEach { finish(it) }
    }

    /** Leaves the workers ready for another queue, such as a manual replay. */
    fun cancelAll() {
        val selected = synchronized(lock) { entries.values.toList() }
        selected.forEach { finish(it) }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        cancelAll()
        pendingChanged.cancel()
        prepared.cancel()
        playable.cancel()
        workers.cancel()
    }

    private suspend fun worker(block: suspend () -> Unit) {
        try { block() } finally { close() }
    }

    private suspend fun prepareLoop() {
        while (true) {
            val entry = synchronized(lock) { pending.removeFirstOrNull() }
            if (entry == null) { pendingChanged.receive(); continue }
            if (entry.terminal) continue
            val result = stage(entry) {
                window.acquire()
                val accepted = synchronized(lock) {
                    if (entry.terminal) false else { entry.windowHeld = true; true }
                }
                if (!accepted) {
                    window.release()
                    throw CancellationException()
                }
                prepare(entry.item)
            }
            when (result) {
                is StageResult.Value -> if (result.value == null) finish(entry)
                    else if (!entry.terminal) prepared.send(entry to result.value)
                is StageResult.Failed -> finish(entry, result.cause)
                StageResult.Cancelled -> finish(entry)
            }
        }
    }

    private suspend fun synthesisLoop() {
        for ((entry, preparation) in prepared) {
            if (entry.terminal) continue
            val result = stage(entry) {
                audioSlots.acquire()
                val accepted = synchronized(lock) {
                    if (entry.terminal) false else { entry.audioSlotHeld = true; true }
                }
                if (!accepted) {
                    audioSlots.release()
                    throw CancellationException()
                }
                synthesize(entry.item, preparation) { audio -> publish(entry, audio) }
            }
            when (result) {
                is StageResult.Value -> {
                    val complete = synchronized(lock) {
                        entry.synthDone = true
                        !entry.published || entry.playDone
                    }
                    if (complete) finish(entry)
                }
                is StageResult.Failed -> finish(entry, result.cause)
                StageResult.Cancelled -> finish(entry)
            }
        }
    }

    private suspend fun publish(entry: Entry, audio: A) {
        var duplicate = false
        val accepted = synchronized(lock) {
            when {
                entry.terminal -> false
                entry.published -> { duplicate = true; false }
                else -> { entry.audio = audio; entry.published = true; true }
            }
        }
        if (!accepted) {
            // A late callback never enters the playback queue. Do not dispose the
            // first owned handle twice if a broken producer publishes it twice.
            if (synchronized(lock) { entry.audio !== audio }) runCatching { disposeAudio(audio) }
            if (duplicate) throw IllegalStateException("A speech stream may only be published once")
            throw CancellationException()
        }
        currentCoroutineContext().ensureActive()
        playable.send(entry)
    }

    private suspend fun playbackLoop() {
        for (entry in playable) {
            val audio = synchronized(lock) { if (entry.terminal) null else entry.audio } ?: continue
            when (val result = stage(entry) { play(entry.item, audio) }) {
                is StageResult.Value -> {
                    val complete = synchronized(lock) { entry.playDone = true; entry.synthDone }
                    if (complete) finish(entry)
                }
                is StageResult.Failed -> finish(entry, result.cause)
                StageResult.Cancelled -> finish(entry)
            }
        }
    }

    private suspend fun <R> stage(entry: Entry, block: suspend () -> R): StageResult<R> {
        if (entry.terminal) return StageResult.Cancelled
        val task = workers.async(entry.job) { block() }
        return try {
            // A cancelled entry must not block later utterances while its network
            // callback performs non-cancellable cleanup. Late results are ignored.
            select {
                entry.stopped.onAwait { StageResult.Cancelled }
                task.onAwait { StageResult.Value(it) }
            }
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (error is TimeoutCancellationException && !entry.terminal) StageResult.Failed(error)
            else StageResult.Cancelled
        } catch (error: Throwable) {
            if (entry.terminal) StageResult.Cancelled else StageResult.Failed(error)
        }
    }

    private fun finish(entry: Entry, error: Throwable? = null) {
        val owned: Triple<A?, Boolean, Boolean> = synchronized(lock) {
            if (entry.terminal) return
            entry.terminal = true
            if (entries[entry.key] === entry) entries.remove(entry.key)
            pending.remove(entry)
            Triple(entry.audio, entry.windowHeld, entry.audioSlotHeld).also {
                entry.windowHeld = false
                entry.audioSlotHeld = false
            }
        }
        entry.job.cancel()
        owned.first?.let { runCatching { disposeAudio(it) } }
        if (owned.second) window.release()
        if (owned.third) audioSlots.release()
        // Wake workers only after cancellation and resource disposal are visible.
        entry.stopped.complete(Unit)
        if (error != null && (error !is CancellationException || error is TimeoutCancellationException)) {
            runCatching { onFailure(entry.item, error) }
        }
        runCatching { onFinished(entry.item) }
    }
}
