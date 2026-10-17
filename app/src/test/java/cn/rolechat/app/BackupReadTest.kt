package cn.rolechat.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BackupReadTest {
    @Test fun `limit is measured in UTF8 bytes including exact boundary`() {
        assertEquals("你", ByteArrayInputStream("你".toByteArray()).readBackupText(3))
        assertThrows(IllegalArgumentException::class.java) { ByteArrayInputStream("你好".toByteArray()).readBackupText(3) }
    }

    @Test fun `reader handles empty files and streams returning zero`() {
        assertEquals("", ByteArrayInputStream(byteArrayOf()).readBackupText(0))
        val input = object : InputStream() {
            var calls = 0
            val bytes = ByteArrayInputStream("{}".toByteArray())
            override fun read(): Int = bytes.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                if (calls++ == 0) 0 else bytes.read(buffer, offset, length)
        }
        assertEquals("{}", input.readBackupText(2))
    }
}
