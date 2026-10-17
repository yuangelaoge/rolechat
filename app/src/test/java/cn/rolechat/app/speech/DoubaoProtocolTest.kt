package cn.rolechat.app.speech

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DoubaoProtocolTest {
    @Test fun `connection request has no session ID field`() {
        val fixture = hex("11 14 10 00 00 00 00 01 00 00 00 02 7b 7d")
        assertArrayEquals(fixture, DoubaoProtocol.request(DoubaoProtocol.START_CONNECTION))
    }

    @Test fun `task request counts UTF8 bytes rather than characters`() {
        // Session 's'; payload contains the Chinese character '你' (e4 bd a0).
        val fixture = hex("11 14 10 00 00 00 00 c8 00 00 00 01 73 00 00 00 0e 7b 22 74 65 78 74 22 3a 22 e4 bd a0 22 7d")
        assertArrayEquals(fixture, DoubaoProtocol.request(DoubaoProtocol.TASK_REQUEST, "s", "{\"text\":\"你\"}"))
    }

    @Test fun `server audio keeps raw PCM bytes and session identity`() {
        // 352 = TTSResponse; 0xb = audio-only; event flag = 4; raw serialization.
        val frame = DoubaoProtocol.parse(hex("11 b4 00 00 00 00 01 60 00 00 00 01 73 00 00 00 04 00 00 ff 7f"))
        assertEquals(0xb, frame.type)
        assertEquals(352, frame.event)
        assertEquals("s", frame.id)
        assertArrayEquals(byteArrayOf(0, 0, -1, 127), frame.payload)
        assertNull(frame.errorCode)
    }

    @Test fun `connection response ID is distinct from payload`() {
        val frame = DoubaoProtocol.parse(hex("11 94 10 00 00 00 00 32 00 00 00 07 62 78 6e 77 65 69 75 00 00 00 02 7b 7d"))
        assertEquals(DoubaoProtocol.CONNECTION_STARTED, frame.event)
        assertEquals("bxnweiu", frame.id)
        assertEquals("{}", frame.payload.toString(Charsets.UTF_8))
    }

    @Test fun `error frame has error code and no event or ID`() {
        val frame = DoubaoProtocol.parse(hex("11 f0 10 00 02 ae a5 40 00 00 00 02 7b 7d"))
        assertEquals(45_000_000, frame.errorCode)
        assertNull(frame.event)
        assertNull(frame.id)
        assertEquals("{}", frame.payload.toString(Charsets.UTF_8))
    }

    @Test fun `gzip response is decoded after reading wire length`() {
        val payload = "{\"status_code\":20000000}".toByteArray()
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(payload) } }.toByteArray()
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.write(hex("11 94 11 00 00 00 00 98 00 00 00 01 73"))
                it.writeInt(gzip.size)
                it.write(gzip)
            }
        }.toByteArray()
        val frame = DoubaoProtocol.parse(bytes)
        assertEquals(152, frame.event)
        assertArrayEquals(payload, frame.payload)
    }

    @Test fun `truncated oversized and trailing fields are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DoubaoProtocol.parse(hex("11 94")) }
        assertThrows(IllegalArgumentException::class.java) {
            DoubaoProtocol.parse(hex("11 94 10 00 00 00 00 32 7f ff ff ff"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DoubaoProtocol.parse(hex("11 f0 10 00 02 ae a5 40 00 00 00 02 7b 7d ff"))
        }
    }

    @Test fun `session requests reject missing identity`() {
        assertThrows(IllegalArgumentException::class.java) { DoubaoProtocol.request(DoubaoProtocol.FINISH_SESSION) }
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
