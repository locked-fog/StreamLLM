package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.provider.openai.OpenAiChatRequest
import dev.lockedfog.streamllm.provider.openai.OpenAiMessage
import dev.lockedfog.streamllm.provider.openai.OpenAiStreamChunk
import dev.lockedfog.streamllm.provider.openai.OpenAiUsage
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SerializationTest {

    private val json = StreamLLM.json

    @Test
    fun `test ChatRole serialization`() {
        assertEquals("\"user\"", json.encodeToString(ChatRole.USER))
        assertEquals("\"tool\"", json.encodeToString(ChatRole.TOOL))
    }

    @Test
    fun `test ChatContent text serialization`() {
        val content = ChatContent.Text("Hello")
        // [Fix] 显式指定 <ChatContent> 以使用接口上的自定义序列化器
        val jsonStr = json.encodeToString<ChatContent>(content)
        assertEquals("\"Hello\"", jsonStr)
    }

    @Test
    fun `test ChatContent multimodal serialization`() {
        val content = ChatContent.Parts(
            listOf(
                ContentPart.TextPart(text = "Look at this"),
                ContentPart.ImagePart(imageUrl = ImageUrl("http://img.com/1.png"))
            )
        )
        // [Fix] 显式指定 <ChatContent>
        val jsonStr = json.encodeToString<ChatContent>(content)

        assertTrue(jsonStr.contains("\"type\":\"text\""))
        assertTrue(jsonStr.contains("\"type\":\"image_url\""))
        assertTrue(jsonStr.contains("\"http://img.com/1.png\""))
        assertTrue(jsonStr.startsWith("[") && jsonStr.endsWith("]"))
    }

    @Test
    fun `test ChatContent deserialization (String)`() {
        // 模拟 API 返回纯字符串 content
        val jsonInput = "\"Just text\""
        val content = json.decodeFromString<ChatContent>(jsonInput)

        assertIs<ChatContent.Text>(content)
        assertEquals("Just text", content.text)
    }

    @Test
    fun `test ChatContent deserialization (Array)`() {
        // 模拟 API 返回多模态数组
        val jsonInput = """
            [
                {"type": "text", "text": "What is in this image?"},
                {"type": "image_url", "image_url": {"url": "http://test.com/img.jpg"}}
            ]
        """.trimIndent()

        val content = json.decodeFromString<ChatContent>(jsonInput)

        assertIs<ChatContent.Parts>(content)
        assertEquals(2, content.parts.size)
        assertIs<ContentPart.TextPart>(content.parts[0])
        assertIs<ContentPart.ImagePart>(content.parts[1])
    }

    @Test
    fun `test OpenAiMessage with ToolCalls serialization`() {
        val toolCall = ToolCall(
            id = "call_123",
            function = FunctionCall(name = "get_weather", arguments = "{\"city\":\"Beijing\"}")
        )

        val message = OpenAiMessage(
            role = ChatRole.ASSISTANT,
            content = ChatContent.Text(""), // Assistant 发起调用时 content 通常为空或 null
            toolCalls = listOf(toolCall)
        )

        val jsonStr = json.encodeToString(message)

        assertTrue(jsonStr.contains("\"tool_calls\":"))
        assertTrue(jsonStr.contains("\"get_weather\""))
        assertTrue(jsonStr.contains("\"call_123\""))
    }

    @Test
    fun `test StreamChunk deserialization with Reasoning Content`() {
        // DeepSeek R1 格式
        val chunkJson = """
            {
                "id": "1",
                "choices": [{
                    "delta": {
                        "content": "Final Answer",
                        "reasoning_content": "Thinking Process..."
                    }
                }]
            }
        """.trimIndent()

        val chunk = json.decodeFromString<OpenAiStreamChunk>(chunkJson)
        val delta = chunk.choices?.first()?.delta

        assertEquals("Final Answer", delta?.content)
        assertEquals("Thinking Process...", delta?.reasoningContent)
    }
}