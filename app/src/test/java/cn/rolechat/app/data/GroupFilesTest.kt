package cn.rolechat.app.data

import cn.rolechat.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class GroupFilesTest {
    private fun configuration() = exportGroupPackage(AppState(), "lobby")

    @Test fun dedicatedFileRoundTripsSettingsWithoutHistoryOrGlobalCredentials() {
        val state = AppState(messages = listOf(ChatMessage(roomId = "lobby", text = "private chat marker",
            segments = listOf(GenerationSegment(reasoning = "private cot marker")))))
        val packageData = exportGroupPackage(state, "lobby")
        val text = encodeGroupConfiguration(packageData)
        assertEquals(packageData, decodeGroupConfiguration(text))
        assertEquals(setOf("format", "version", "room", "characters", "worlds", "providers"), Json.parseToJsonElement(text).jsonObject.keys)
        assertFalse(text.contains("private chat marker"))
        assertFalse(text.contains("private cot marker"))
        assertFalse(text.contains("contextMessageIds"))
        assertFalse(text.contains("apiKey"))
        assertFalse(text.contains("accessToken"))
    }

    @Test fun importRejectsFullBackupsAndInjectedMessageOrCredentialFields() {
        val clean = encodeGroupConfiguration(configuration())
        val invalid = listOf(
            AppJson.encodeToString(AppState()),
            clean.replaceFirst("{", "{\"messages\": [],"),
            clean.replaceFirst("{", "{\"apiKey\": \"not-a-real-key\","),
            clean.replaceFirst("\"members\":", "\"messages\": [], \"members\":"),
            "not JSON"
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { decodeGroupConfiguration(it) } }
    }

    @Test fun importRejectsInsecureCredentialBearingOrMissingProviderDependencies() {
        val original = configuration()
        val json = Json { encodeDefaults = true }
        listOf("http://api.example/v1", "https://name:secret@api.example/v1", "https://api.example/v1?key=secret",
            "https://api.example/v1#fragment").forEach { address ->
            val changed = original.copy(providers = original.providers.map { it.copy(baseUrl = address) })
            assertThrows(IllegalArgumentException::class.java) { decodeGroupConfiguration(json.encodeToString(changed)) }
            assertThrows(IllegalArgumentException::class.java) { encodeGroupConfiguration(changed) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeGroupConfiguration(json.encodeToString(original.copy(providers = emptyList())))
        }
    }

    @Test fun importNeverSilentlyAcceptsAnUnknownFormatOrVersion() {
        val json = Json { encodeDefaults = true }
        listOf(configuration().copy(format = "full-backup"), configuration().copy(version = 99)).forEach {
            assertThrows(IllegalArgumentException::class.java) { decodeGroupConfiguration(json.encodeToString(it)) }
        }
    }

    @Test fun suggestedFileNameCannotEscapeTheChosenDirectory() {
        val name = groupConfigurationFileName("../雨夜\\小屋\n:测试?")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('\n'))
        assertFalse(name.contains(':'))
        assertTrue(name.endsWith("-群聊配置.json"))
        assertEquals("片刻-群聊-群聊配置.json", groupConfigurationFileName("  "))
    }
}
