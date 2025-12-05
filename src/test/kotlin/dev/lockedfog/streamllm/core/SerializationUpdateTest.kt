package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.provider.openai.OpenAiChatRequest
import dev.lockedfog.streamllm.provider.openai.OpenAiMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class SerializationUpdateTest {

    private val json = StreamLLM.json

    @Test
    fun `test tool definition serialization`() {
        // 构建一个 Tool 对象
        val tool = Tool(
            function = FunctionDefinition(
                name = "search",
                description = "Google Search",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                }
            )
        )

        val jsonStr = json.encodeToString(tool)

        // 验证 OpenAI 格式字段
        assertTrue(jsonStr.contains("\"type\":\"function\""))
        assertTrue(jsonStr.contains("\"name\":\"search\""))
        assertTrue(jsonStr.contains("\"description\":\"Google Search\""))
    }

    @Test
    fun `test request with tools`() {
        val request = OpenAiChatRequest(
            model = "gpt-4",
            messages = listOf(OpenAiMessage(ChatRole.USER, ChatContent.Text("Hi"))),
            tools = listOf(
                Tool(function = FunctionDefinition(name = "test_func", parameters = buildJsonObject {}))
            ),
            toolChoice = JsonPrimitive("auto")
        )

        val jsonStr = json.encodeToString(request)

        // 验证 tools 数组存在
        assertTrue(jsonStr.contains("\"tools\":[{"))
        // 验证 tool_choice 字段
        assertTrue(jsonStr.contains("\"tool_choice\":\"auto\""))
        // 验证函数名
        assertTrue(jsonStr.contains("test_func"))
    }
}
