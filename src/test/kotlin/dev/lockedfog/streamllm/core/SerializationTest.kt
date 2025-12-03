package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.provider.openai.OpenAiChatRequest
import dev.lockedfog.streamllm.provider.openai.OpenAiMessage
import dev.lockedfog.streamllm.provider.openai.OpenAiUsage
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerializationTest {

    private val json = StreamLLM.json

    @Test
    fun `test ChatRole serialization`() {
        // 验证枚举序列化为小写字符串
        val userRole = ChatRole.USER
        val jsonStr = json.encodeToString(userRole)
        assertEquals("\"user\"", jsonStr)
    }

    @Test
    fun `test OpenAiChatRequest serialization`() {
        val request = OpenAiChatRequest(
            model = "gpt-4",
            messages = listOf(OpenAiMessage(ChatRole.USER, "Hello")),
            maxTokens = 100,
            temperature = 0.7
        )

        val jsonStr = json.encodeToString(request)

        // 验证关键字段是否使用 snake_case (由 @SerialName 决定)
        assertTrue(jsonStr.contains(""""max_tokens":100"""), "maxTokens 应序列化为 max_tokens")
        assertTrue(jsonStr.contains(""""model":"gpt-4""""))
    }

    @Test
    fun `test OpenAiUsage deserialization`() {
        // [修正] 测试重点转移到 OpenAiUsage (DTO)
        // 验证它能正确解析 API 返回的 standard snake_case JSON
        val jsonStr = """
            {
                "prompt_tokens": 10,
                "completion_tokens": 20,
                "total_tokens": 30
            }
        """.trimIndent()

        // 这里必须用 OpenAiUsage 来解析，而不是 Usage
        val openAiUsage = json.decodeFromString<OpenAiUsage>(jsonStr)

        assertEquals(10, openAiUsage.promptTokens)
        assertEquals(20, openAiUsage.completionTokens)
        assertEquals(30, openAiUsage.totalTokens)
    }
}