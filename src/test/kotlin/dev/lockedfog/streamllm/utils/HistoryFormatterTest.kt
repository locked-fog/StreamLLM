package dev.lockedfog.streamllm.utils

import dev.lockedfog.streamllm.core.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HistoryFormatterTest {

    @Test
    fun `test formatter extracts text from multimodal content`() {
        val mixedContent = ChatContent.Parts(listOf(
            ContentPart.TextPart(text = "Here is an image:"),
            ContentPart.ImagePart(imageUrl = ImageUrl("url")),
            ContentPart.TextPart(text = "What is it?")
        ))

        val history = listOf(
            ChatMessage(ChatRole.USER, mixedContent)
        )

        // 默认格式化器只关注 content 占位符
        val result = HistoryFormatter.DEFAULT.format(history)

        // 预期：只提取文本部分，并用换行符连接
        // User: Here is an image:
        // What is it?
        val expected = """
            User: Here is an image:
            What is it?
        """.trimIndent()

        assertEquals(expected, result)
    }

    @Test
    fun `test legacy text content`() {
        val history = listOf(ChatMessage(ChatRole.USER, "Hi"))
        val result = HistoryFormatter.DEFAULT.format(history)
        assertEquals("User: Hi", result)
    }
}