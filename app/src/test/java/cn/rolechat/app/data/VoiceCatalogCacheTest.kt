package cn.rolechat.app.data

import cn.rolechat.app.speech.DoubaoVoice
import cn.rolechat.app.speech.DoubaoVoices
import java.io.File
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCatalogCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun cacheFile() = File(temporary.root, "public-metadata/voices.json")
    private fun snapshot() = VoiceCatalogSnapshot(listOf(
        DoubaoVoice("custom-one", "音色一", "seed-tts-1.0", "远端目录元数据"),
        DoubaoVoice("custom-two", "音色二", "seed-tts-2.0", bidirectional = false)
    ), updatedAt = 1_234_567L)

    @Test fun `successful refresh survives a new cache instance and atomically replaces the previous catalog`() {
        val file = cacheFile()
        val original = snapshot()
        val cache = VoiceCatalogCache(file)
        assertNull(cache.read())
        cache.write(original)
        assertEquals(original, VoiceCatalogCache(file).read())
        assertFalse(File(file.parentFile, file.name + ".pending").exists())

        val refreshed = original.copy(voices = original.voices.take(1).map { it.copy(name = "刷新后的名字") },
            updatedAt = original.updatedAt + 100)
        cache.write(refreshed)
        assertEquals(refreshed, VoiceCatalogCache(file).read())
        assertFalse(File(file.parentFile, file.name + ".pending").exists())
    }

    @Test fun `malformed invalid and oversized files return null without deleting the last evidence`() {
        val file = cacheFile()
        file.parentFile.mkdirs()
        val invalidValues = listOf(
            "{unfinished JSON",
            AppJson.encodeToString(snapshot().copy(voices = emptyList())),
            AppJson.encodeToString(snapshot().copy(updatedAt = 0)),
            AppJson.encodeToString(snapshot().copy(voices = listOf(snapshot().voices.first().copy(resourceId = "unknown-resource")))),
            "x".repeat(4 * 1024 * 1024 + 1)
        )
        invalidValues.forEach { content ->
            file.writeText(content)
            assertNull(VoiceCatalogCache(file).read())
            assertTrue(file.isFile)
            assertEquals(content, file.readText())
        }
    }

    @Test fun `invalid refresh cannot replace a previously usable cache`() {
        val file = cacheFile()
        val cache = VoiceCatalogCache(file)
        val original = snapshot()
        cache.write(original)
        val before = file.readBytes()
        val invalid = original.copy(voices = listOf(original.voices.first().copy(id = " ")))
        assertThrows(IllegalArgumentException::class.java) { cache.write(invalid) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(original, cache.read())
        assertFalse(File(file.parentFile, file.name + ".pending").exists())
    }

    @Test fun `merging updates public metadata without erasing known transport restrictions or resource variants`() {
        val restricted = DoubaoVoices.builtIn.first { !it.bidirectional }
        val ordinary = DoubaoVoices.builtIn.first { it.bidirectional }
        val fetchedRestricted = restricted.copy(name = "新目录名称", description = "最新描述", bidirectional = true)
        val dynamicRestricted = DoubaoVoice("fresh-restricted", "新单向音色", "seed-tts-2.0", bidirectional = false)
        val acrossVersions = listOf("seed-tts-1.0", "seed-tts-2.0").map {
            DoubaoVoice("same-id", "跨版本音色", it)
        }
        val result = mergedDoubaoVoices(listOf(fetchedRestricted, ordinary.copy(bidirectional = false), dynamicRestricted) + acrossVersions)
        val updated = result.single { it.id == restricted.id && it.resourceId == restricted.resourceId }
        assertEquals("新目录名称", updated.name)
        assertEquals("最新描述", updated.description)
        assertFalse(updated.bidirectional)
        assertFalse(result.single { it.id == ordinary.id && it.resourceId == ordinary.resourceId }.bidirectional)
        assertFalse(result.single { it.id == dynamicRestricted.id }.bidirectional)
        assertEquals(acrossVersions.toSet(), result.filter { it.id == "same-id" }.toSet())
        assertTrue(result.map { it.resourceId to it.id }.containsAll(DoubaoVoices.builtIn.map { it.resourceId to it.id }))
        assertEquals(result.size, result.map { it.resourceId to it.id }.distinct().size)
        assertNotNull(DoubaoVoices.validationError(restricted.resourceId, restricted.id, result))
    }
}
