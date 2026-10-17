package cn.rolechat.app.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Volcengine V3 bidirectional wire format; all integers are big-endian.
 * https://docs.volcengine.com/docs/DoubaoVoice/WebSocketBidirectionalStreaming-V3?lang=zh
 */
internal object DoubaoProtocol {
    const val START_CONNECTION = 1
    const val FINISH_CONNECTION = 2
    const val CONNECTION_STARTED = 50
    const val CONNECTION_FAILED = 51
    const val CONNECTION_FINISHED = 52
    const val START_SESSION = 100
    const val CANCEL_SESSION = 101
    const val FINISH_SESSION = 102
    const val SESSION_STARTED = 150
    const val SESSION_CANCELLED = 151
    const val SESSION_FINISHED = 152
    const val SESSION_FAILED = 153
    const val TASK_REQUEST = 200
    const val AUDIO = 352
    private const val MAX_PAYLOAD = 16 * 1024 * 1024

    data class Frame(
        val type: Int, val event: Int?, val id: String?, val payload: ByteArray,
        val errorCode: Int? = null
    )

    fun request(event: Int, sessionId: String? = null, json: String = "{}"): ByteArray {
        require(event < 100 || !sessionId.isNullOrBlank()) { "Session ID required" }
        val payload = json.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { writer ->
                writer.write(byteArrayOf(0x11, 0x14, 0x10, 0x00))
                writer.writeInt(event)
                if (event >= 100) {
                    val id = sessionId!!.toByteArray(Charsets.UTF_8)
                    writer.writeInt(id.size)
                    writer.write(id)
                }
                writer.writeInt(payload.size)
                writer.write(payload)
            }
        }.toByteArray()
    }

    fun parse(bytes: ByteArray): Frame {
        require(bytes.size >= 4) { "Incomplete TTS header" }
        val header = bytes[0].toInt() and 0xff
        require(header ushr 4 == 1) { "Unsupported TTS protocol version" }
        val headerSize = (header and 0xf) * 4
        require(headerSize in 4..bytes.size) { "Invalid TTS header size" }
        val type = (bytes[1].toInt() and 0xff) ushr 4
        val flags = bytes[1].toInt() and 0xf
        val compression = bytes[2].toInt() and 0xf
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply { position(headerSize) }
        fun int(): Int {
            require(buffer.remaining() >= 4) { "Truncated TTS field" }
            return buffer.int
        }
        fun field(): ByteArray {
            val length = int()
            require(length in 0..MAX_PAYLOAD && length <= buffer.remaining()) { "Invalid TTS field length" }
            return ByteArray(length).also(buffer::get)
        }
        val error = if (type == 0xf) int() else null
        if (type != 0xf && flags and 1 != 0) int() // Optional sequence number.
        val event = if (type != 0xf && flags and 4 != 0) int() else null
        val id = if (event != null && (event in 50..52 || event >= 100)) {
            field().toString(Charsets.UTF_8)
        } else null
        val raw = field()
        require(!buffer.hasRemaining()) { "Unexpected trailing TTS bytes" }
        val payload = when (compression) {
            0 -> raw
            1 -> GZIPInputStream(ByteArrayInputStream(raw)).use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(out.size() + count <= MAX_PAYLOAD) { "TTS decompressed payload too large" }
                    out.write(chunk, 0, count)
                }
                out.toByteArray()
            }
            else -> error("Unsupported TTS compression")
        }
        return Frame(type, event, id, payload, error)
    }
}
