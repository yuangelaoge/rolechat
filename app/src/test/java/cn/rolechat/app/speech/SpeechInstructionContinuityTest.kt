package cn.rolechat.app.speech

import cn.rolechat.core.AppState
import cn.rolechat.core.Character
import cn.rolechat.core.ChatMessage
import cn.rolechat.core.ChatRoom
import cn.rolechat.core.SpeechPreparationRecord
import cn.rolechat.core.SpeechPreparationStatus
import cn.rolechat.core.SpeechVoiceSnapshot
import cn.rolechat.core.TtsEngine
import org.junit.Assert.*
import org.junit.Test

class SpeechInstructionContinuityTest {
    private val voice = SpeechVoiceSnapshot(TtsEngine.DOUBAO, "seed-tts-2.0", "voice-a")
    private val current = ChatMessage(id = "current", roomId = "room", characterId = "actor", text = "本次的对白")

    private fun previous(id: String = "previous", instruction: String = "轻声，平稳地说，语速稍慢。") =
        ChatMessage(id = id, roomId = current.roomId, characterId = current.characterId,
            text = "以前的完整原文，不应传给小模型", speechPreparation = SpeechPreparationRecord(
                text = "以前提取的对白，不应传给小模型", instruction = instruction,
                status = SpeechPreparationStatus.COMPLETE, voice = voice, modelInvoked = true))

    private fun state(vararg messages: ChatMessage) = AppState(
        characters = listOf(Character(id = "actor"), Character(id = "other-actor")),
        rooms = listOf(ChatRoom(id = "room"), ChatRoom(id = "other-room")), messages = messages.toList())

    @Test fun `the nearest preceding successful instruction is available before playback ends`() {
        val first = previous("first", "平静自然")
        val second = previous("second", "  轻声安慰，稍慢。  ")
        val result = previousSpeechInstruction(state(first, second, current), current.id, voice)
        assertEquals(PreviousSpeechInstruction(second.id, "轻声安慰，稍慢。"), result)
    }

    @Test fun `room actor engine resource and voice are independently isolated`() {
        val valid = previous()
        val otherRoom = previous("other-room-message", "跨群不应读取").copy(roomId = "other-room")
        val otherActor = previous("other-actor-message", "跨角色不应读取").copy(characterId = "other-actor")
        val differentEngine = previous("other-engine").let { it.copy(speechPreparation =
            it.speechPreparation!!.copy(voice = voice.copy(engine = TtsEngine.SILICONFLOW))) }
        val differentResource = previous("other-resource").let { it.copy(speechPreparation =
            it.speechPreparation!!.copy(voice = voice.copy(resourceId = "seed-tts-1.0"))) }
        val differentVoice = previous("other-voice").let { it.copy(speechPreparation =
            it.speechPreparation!!.copy(voice = voice.copy(voiceId = "voice-b"))) }
        val all = state(valid, otherRoom, otherActor, differentEngine, differentResource, differentVoice, current)
        assertEquals(valid.id, previousSpeechInstruction(all, current.id, voice)?.messageId)
        assertNull(previousSpeechInstruction(all.copy(messages = all.messages.drop(1)), current.id, voice))
    }

    @Test fun `reading an old reply again never reads its own or a future preparation`() {
        val own = previous("own")
        val future = previous("future", "未来的语气")
        assertNull(previousSpeechInstruction(state(own, future), own.id, voice))
        assertNull(previousSpeechInstruction(state(current, future), current.id, voice))
        val valid = previous()
        // Timestamps can tie or be imported; array order is the conversation boundary.
        val all = state(valid.copy(createdAt = 9000), current.copy(createdAt = 1000), future.copy(createdAt = 0))
        assertEquals(valid.id, previousSpeechInstruction(all, current.id, voice)?.messageId)
    }

    @Test fun `inflight failed cancelled empty or legacy unconfirmed preparations are skipped`() {
        val valid = previous()
        val invalid = SpeechPreparationStatus.entries.filter { it != SpeechPreparationStatus.COMPLETE }.map {
            previous(it.name).let { reply -> reply.copy(speechPreparation = reply.speechPreparation!!.copy(status = it)) }
        } + listOf(
            previous("blank-text").let { it.copy(speechPreparation = it.speechPreparation!!.copy(text = " \n")) },
            previous("blank-instruction", " \n"),
            previous("long-instruction", "轻".repeat(1001)),
            previous("not-invoked").let { it.copy(speechPreparation = it.speechPreparation!!.copy(modelInvoked = false)) },
            previous("unknown-invocation").let { it.copy(speechPreparation = it.speechPreparation!!.copy(modelInvoked = null)) },
            previous("unknown-voice").let { it.copy(speechPreparation = it.speechPreparation!!.copy(voice = null)) },
            previous("edited").copy(speechPreparation = null)
        )
        val all = state(*(listOf(valid) + invalid + current).toTypedArray())
        assertEquals(valid.id, previousSpeechInstruction(all, current.id, voice)?.messageId)
        assertNull(previousSpeechInstruction(all.copy(messages = invalid + current), current.id, voice))
    }

    @Test fun `deleted rooms roles missing messages and own messages do not inherit an instruction`() {
        val all = state(previous(), current)
        assertNull(previousSpeechInstruction(all, "missing", voice))
        assertNull(previousSpeechInstruction(all.copy(rooms = emptyList()), current.id, voice))
        assertNull(previousSpeechInstruction(all.copy(characters = emptyList()), current.id, voice))
        assertNull(previousSpeechInstruction(all.copy(messages = listOf(previous(), current.copy(characterId = null))), current.id, voice))
        assertNull(previousSpeechInstruction(all.copy(messages = listOf(current)), current.id, voice))
        assertNull(previousSpeechInstruction(all, current.id, voice.copy(voiceId = "")))
    }

    @Test fun `a successful new preparation becomes the continuity source but an edited one disappears`() {
        val older = previous("older", "原来的语气")
        val before = state(older, current, previous("next").copy(speechPreparation = null))
        assertEquals(older.id, previousSpeechInstruction(before, "next", voice)?.messageId)
        val completed = current.copy(speechPreparation = older.speechPreparation!!.copy(instruction = "本条明确生气，语气严肃。"))
        val after = before.copy(messages = listOf(older, completed, before.messages.last()))
        assertEquals(PreviousSpeechInstruction(current.id, "本条明确生气，语气严肃。"),
            previousSpeechInstruction(after, "next", voice))
        assertEquals(older.id, previousSpeechInstruction(after.copy(messages =
            after.messages.map { if (it.id == current.id) it.copy(speechPreparation = null) else it }), "next", voice)?.messageId)
    }

    @Test fun `prepared but cancelled before playback is ignored while its diagnostics remain complete`() {
        val played = previous("played", "先前真正播放过的稳定语气")
        val unheard = previous("unheard", "取消了的突然愤怒语气").let {
            it.copy(speechPreparation = it.speechPreparation!!.copy(continuityEligible = false))
        }
        val all = state(played, unheard, current)
        assertEquals(PreviousSpeechInstruction(played.id, "先前真正播放过的稳定语气"),
            previousSpeechInstruction(all, current.id, voice))
        assertEquals(SpeechPreparationStatus.COMPLETE, all.messages[1].speechPreparation!!.status)
        assertEquals("取消了的突然愤怒语气", all.messages[1].speechPreparation!!.instruction)
        assertNull(previousSpeechInstruction(state(unheard, current), current.id, voice))
    }
}
