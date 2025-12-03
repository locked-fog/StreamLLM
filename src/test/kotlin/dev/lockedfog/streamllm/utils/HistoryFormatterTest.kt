package dev.lockedfog.streamllm.utils

import dev.lockedfog.streamllm.core.ChatMessage
import dev.lockedfog.streamllm.core.ChatRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HistoryFormatterTest {

    @Test
    fun `test default formatter`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "Hi"),
            ChatMessage(ChatRole.ASSISTANT, "Hello")
        )

        val result = HistoryFormatter.DEFAULT.format(history)

        val expected = """
            User: Hi
            Assistant: Hello
        """.trimIndent()

        assertEquals(expected, result)
    }

    @Test
    fun `test custom format string`() {
        // 格式：User 说 {{content}} | AI 说 {{content}} | 分隔符是 " || "
        val formatStr = "user=User 说 {{content}}; assistant=AI 说 {{content}}; sep= || "
        val formatter = HistoryFormatter.fromString(formatStr)

        val history = listOf(
            ChatMessage(ChatRole.USER, "A"),
            ChatMessage(ChatRole.ASSISTANT, "B")
        )

        val result = formatter.format(history)
        assertEquals("User 说 A || AI 说 B", result)
    }
}