package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.ChatMessage
import cn.rolechat.core.GenerationSegment
import cn.rolechat.core.SpeechPreparationRecord
import cn.rolechat.core.SpeechPreparationStatus
import cn.rolechat.core.SpeechVoiceSnapshot
import cn.rolechat.core.StateStore
import cn.rolechat.core.TtsEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpeechPreparationTrackerTest {
    private val original = ChatMessage(id = "reply", roomId = "room", characterId = "actor", text = "完整 A+B 正文",
        segments = listOf(GenerationSegment(text = "完整 A+B 正文", reasoning = "主模型思考")))

    private fun store() = StateStore(AppState(messages = listOf(original)))
    private fun record(store: StateStore) = requireNotNull(store.snapshot().messages.single().speechPreparation)

    @Test fun `success stores cumulative raw output and separate reasoning without changing chat content`() {
        val store = store()
        val voice = SpeechVoiceSnapshot(TtsEngine.DOUBAO, "seed-tts-2.0", "zh_female_vv_uranus_bigtts")
        val tracker = SpeechPreparationTracker(store, original.id, "small-model", voice)
        val started = record(store)
        assertEquals(SpeechPreparationStatus.EXTRACTING, started.status)
        assertEquals("small-model", started.model)
        assertTrue(started.id.isNotBlank())
        assertTrue(started.createdAt > 0)
        assertEquals(voice, started.voice)
        assertEquals(false, started.modelInvoked)
        tracker.modelStarted()
        tracker.output("{\"text\":", "先分析")
        val raw = "{\"text\":\"你好。\",\"instruction\":\"轻声说\"}"
        tracker.output(raw, "先分析，再提取对白")
        tracker.complete(PreparedDialogue("你好。", "轻声说"))

        val completed = record(store)
        assertEquals(started.id, completed.id)
        assertEquals(started.createdAt, completed.createdAt)
        assertEquals(SpeechPreparationStatus.COMPLETE, completed.status)
        assertEquals(voice, completed.voice)
        assertEquals(true, completed.modelInvoked)
        assertEquals(raw, completed.rawOutput)
        assertEquals("先分析，再提取对白", completed.reasoning)
        assertEquals("你好。", completed.text)
        assertEquals("轻声说", completed.instruction)
        assertEquals(original, store.snapshot().messages.single().copy(speechPreparation = null))

        tracker.output("迟到输出", "迟到思考")
        tracker.fail("播放失败不能把成功提取变成失败")
        tracker.cancel()
        tracker.modelStarted()
        tracker.complete(PreparedDialogue("重复完成", ""))
        assertEquals(completed, record(store))
    }

    @Test fun `empty dialogue keeps its model output and records skipped speech`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "small-model")
        tracker.output("{\"text\":\"\",\"instruction\":\"\"}", "只有场景描写")
        tracker.complete(PreparedDialogue("", ""))
        assertEquals(SpeechPreparationStatus.EMPTY, record(store).status)
        assertEquals("", record(store).text)
        assertEquals("只有场景描写", record(store).reasoning)
        tracker.cancel()
        assertEquals(SpeechPreparationStatus.EMPTY, record(store).status)
    }

    @Test fun `failure preserves partial output for inspection and rejects later completion`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "small-model")
        tracker.output("{\"text\":\"未完成", "原始提取思考")
        tracker.fail("错误".repeat(300))
        val failed = record(store)
        assertEquals(SpeechPreparationStatus.ERROR, failed.status)
        assertEquals(500, failed.error.length)
        assertEquals("{\"text\":\"未完成", failed.rawOutput)
        assertEquals("原始提取思考", failed.reasoning)
        assertEquals("", failed.text)
        tracker.output("晚到 JSON", "晚到思考")
        tracker.complete(PreparedDialogue("不应朗读的晚到对白", ""))
        tracker.cancel()
        assertEquals(failed, record(store))
    }

    @Test fun `stop keeps partial diagnostics but late events cannot revive the stopped attempt`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "small-model")
        tracker.output("半个 JSON", "正在提取")
        tracker.cancel()
        val stopped = record(store)
        assertEquals(SpeechPreparationStatus.CANCELLED, stopped.status)
        assertEquals("半个 JSON", stopped.rawOutput)
        assertEquals("正在提取", stopped.reasoning)
        tracker.output("迟到完整 JSON", "迟到思考")
        tracker.complete(PreparedDialogue("迟到对白", "轻声"))
        tracker.fail("取消后的网络异常")
        assertEquals(stopped, record(store))
    }

    @Test fun `a new attempt owns the same message and rejects all events from the previous one`() {
        val store = store()
        val old = SpeechPreparationTracker(store, original.id, "old-model")
        old.output("旧 JSON", "旧思考")
        val oldId = record(store).id
        val fresh = SpeechPreparationTracker(store, original.id, "new-model")
        val replacement = record(store)
        assertNotEquals(oldId, replacement.id)
        assertEquals("", replacement.rawOutput)
        assertEquals("", replacement.reasoning)
        old.output("旧的迟到 JSON", "旧的迟到思考")
        old.complete(PreparedDialogue("旧对白", "旧指令"))
        old.fail("旧错误")
        old.cancel()
        old.modelStarted()
        assertEquals(replacement, record(store))
        assertEquals(false, replacement.modelInvoked)
        fresh.modelStarted()
        fresh.output("新 JSON", "新思考")
        fresh.complete(PreparedDialogue("新对白", "新指令"))
        assertEquals(SpeechPreparationStatus.COMPLETE, record(store).status)
        assertEquals("new-model", record(store).model)
        assertEquals("新对白", record(store).text)
        assertEquals(true, record(store).modelInvoked)
    }

    @Test fun `a preflight failure remains explicitly uninvoked even if a late start signal arrives`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "model")
        tracker.fail("音色与资源不匹配")
        tracker.modelStarted()
        assertEquals(SpeechPreparationStatus.ERROR, record(store).status)
        assertEquals(false, record(store).modelInvoked)
        assertNull(record(store).voice)
    }

    @Test fun `missing cleared or replaced messages are never recreated by tracker callbacks`() {
        val emptyStore = StateStore(AppState(messages = emptyList()))
        val missing = SpeechPreparationTracker(emptyStore, "missing", "model")
        missing.output("JSON", "思考")
        missing.complete(PreparedDialogue("对白", ""))
        missing.fail("错误")
        missing.cancel()
        assertTrue(emptyStore.snapshot().messages.isEmpty())

        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "model")
        store.updateMessage(original.id) { it.copy(text = "手动编辑", speechPreparation = null) }
        tracker.output("晚到 JSON", "晚到思考")
        tracker.complete(PreparedDialogue("晚到对白", ""))
        assertNull(store.snapshot().messages.single().speechPreparation)
        assertEquals("手动编辑", store.snapshot().messages.single().text)

        store.update { it.copy(messages = emptyList()) }
        tracker.fail("删除后的错误")
        tracker.cancel()
        assertTrue(store.snapshot().messages.isEmpty())
        store.update { it.copy(messages = listOf(original)) }
        tracker.output("相同消息 ID 也不能复活旧记录", "思考")
        assertEquals(listOf(original), store.snapshot().messages)
    }

    @Test fun `diagnostic buffers are bounded independently and remain separate from speech text`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "model")
        tracker.output("x".repeat(40 * 1024), "y".repeat(20 * 1024))
        val extracting = record(store)
        assertEquals("x".repeat(32 * 1024), extracting.rawOutput)
        assertEquals("y".repeat(16 * 1024), extracting.reasoning)
        assertEquals("", extracting.text)
        assertEquals(original.text, store.snapshot().messages.single().text)
    }

    @Test fun `the actual prior instruction is recorded separately and survives successful completion`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "small-model")
        assertEquals("", record(store).previousInstruction)
        assertNull(record(store).previousMessageId)
        tracker.previousInstruction("  轻声安慰，稍慢。  ", "previous-message")
        tracker.modelStarted()
        tracker.complete(PreparedDialogue("我在这里。", "轻声安慰，自然延续。"))
        val completed = record(store)
        assertEquals("轻声安慰，稍慢。", completed.previousInstruction)
        assertEquals("previous-message", completed.previousMessageId)
        assertEquals("轻声安慰，自然延续。", completed.instruction)
        assertEquals("我在这里。", completed.text)
        tracker.previousInstruction("迟到的不同语气", "other-message")
        assertEquals(completed, record(store))
    }

    @Test fun `cancelled or replaced extraction cannot acquire late continuity diagnostics`() {
        val store = store()
        val old = SpeechPreparationTracker(store, original.id, "old-model")
        old.previousInstruction("旧指令", "old-source")
        old.cancel()
        val cancelled = record(store)
        old.previousInstruction("取消后的指令", "late-source")
        assertEquals(cancelled, record(store))
        SpeechPreparationTracker(store, original.id, "new-model")
        old.previousInstruction("上一尝试迟到的指令", "late-source")
        assertEquals("", record(store).previousInstruction)
        assertNull(record(store).previousMessageId)
    }

    @Test fun `continuity diagnostics remain bounded for display and backup`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "model")
        tracker.previousInstruction("轻".repeat(2000), "x".repeat(1025))
        assertEquals(1000, record(store).previousInstruction.length)
        assertNull(record(store).previousMessageId)
        tracker.previousInstruction("轻声", " ")
        assertNull(record(store).previousMessageId)
    }

    @Test fun `older preparation records decode without a fabricated previous instruction`() {
        val previousVersion = """{"id":"old-record","model":"small-model","text":"你好","instruction":"轻声","status":"COMPLETE"}"""
        val restored = Json.decodeFromString<SpeechPreparationRecord>(previousVersion)
        assertEquals("你好", restored.text)
        assertEquals("轻声", restored.instruction)
        assertEquals("", restored.previousInstruction)
        assertNull(restored.previousMessageId)
    }

    @Test fun `backup round trip preserves the instruction actually supplied for continuity`() {
        val record = SpeechPreparationRecord(text = "我在这里", instruction = "轻声安慰，自然延续。",
            status = SpeechPreparationStatus.COMPLETE, previousInstruction = "轻声安慰，稍慢。",
            previousMessageId = "previous-message")
        val backupJson = Json { encodeDefaults = true }
        assertEquals(record, backupJson.decodeFromString<SpeechPreparationRecord>(backupJson.encodeToString(record)))
    }

    @Test fun `an unheard completed preparation can be excluded from continuity without losing its COT`() {
        val store = store()
        val tracker = SpeechPreparationTracker(store, original.id, "small-model")
        tracker.modelStarted()
        tracker.output("原始 JSON", "提取时的思考")
        tracker.complete(PreparedDialogue("不用着急。", "温柔、平稳，稍慢。"))
        val completed = record(store)
        assertTrue(completed.continuityEligible)
        tracker.discardContinuity()
        assertEquals(completed.copy(continuityEligible = false), record(store))
        assertEquals(SpeechPreparationStatus.COMPLETE, record(store).status)
        tracker.discardContinuity()
        assertEquals(completed.copy(continuityEligible = false), record(store))
    }

    @Test fun `old cancellation or late discard never invalidates a replacement attempt`() {
        val store = store()
        val old = SpeechPreparationTracker(store, original.id, "old-model")
        old.cancel()
        assertFalse(record(store).continuityEligible)
        val fresh = SpeechPreparationTracker(store, original.id, "new-model")
        fresh.modelStarted()
        fresh.complete(PreparedDialogue("现在可以了。", "平静自然。"))
        val replacement = record(store)
        old.cancel()
        old.discardContinuity()
        assertEquals(replacement, record(store))
        assertTrue(record(store).continuityEligible)
    }

    @Test fun `discarded continuity eligibility survives backup and older records default to eligible`() {
        val current = SpeechPreparationRecord(status = SpeechPreparationStatus.COMPLETE, continuityEligible = false)
        assertEquals(current, Json.decodeFromString<SpeechPreparationRecord>(Json.encodeToString(current)))
        val legacy = Json.decodeFromString<SpeechPreparationRecord>("""{"status":"COMPLETE","instruction":"轻声"}""")
        assertTrue(legacy.continuityEligible)
    }
}
