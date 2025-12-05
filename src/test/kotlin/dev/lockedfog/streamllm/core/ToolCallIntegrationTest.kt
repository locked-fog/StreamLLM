package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.dsl.stream
import dev.lockedfog.streamllm.provider.LlmProvider
import io.mockk.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallIntegrationTest {

    private val mockProvider = mockk<LlmProvider>(relaxed = true)

    @BeforeEach
    fun setup() {
        StreamLLM.init(mockProvider)
    }

    @AfterEach
    fun tearDown() {
        StreamLLM.close()
    }

    @Test
    fun `test non-streaming tool execution loop (Re-Act)`() = runTest {
        // 场景：
        // Round 1: 用户问天气 -> 模型返回 ToolCall
        // Round 2: 客户端执行工具 -> 再次请求模型 -> 模型返回 "Sunny"

        val slotMessages = mutableListOf<List<ChatMessage>>()

        // 模拟 Provider 行为
        coEvery { mockProvider.chat(capture(slotMessages), any()) } answers {
            val history = firstArg<List<ChatMessage>>()
            val lastMsg = history.last()

            when (lastMsg.role) {
                ChatRole.USER -> {
                    // 第 1 轮：返回工具调用请求
                    LlmResponse(
                        content = "",
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_1",
                                function = FunctionCall(name = "get_weather", arguments = "{\"city\": \"Beijing\"}")
                            )
                        )
                    )
                }
                ChatRole.TOOL -> {
                    // 第 2 轮：收到工具结果，返回最终答案
                    LlmResponse(content = "It is sunny in Beijing.")
                }
                else -> {
                    LlmResponse("Error")
                }
            }
        }

        stream {
            // 注册工具
            registerTool("get_weather") { args ->
                // 模拟工具执行逻辑
                if (args.contains("Beijing")) "Sunny" else "Unknown"
            }

            val result = "Weather in Beijing?".ask()

            // 验证最终结果
            assertEquals("It is sunny in Beijing.", result)
        }

        // 验证调用次数：应该有 2 次 chat 请求
        assertEquals(2, slotMessages.size)

        // 验证第 2 次请求的历史记录结构：[User, Assistant(ToolCall), Tool(Result)]
        val secondRoundHistory = slotMessages[1]
        assertEquals(3, secondRoundHistory.size)
        assertEquals(ChatRole.TOOL, secondRoundHistory.last().role)
        assertEquals("Sunny", (secondRoundHistory.last().content as ChatContent.Text).text)
    }

    @Test
    fun `test streaming tool execution with chunk aggregation`() = runTest {
        // 场景：流式返回被切分的工具参数 -> 聚合 -> 执行 -> 再次流式请求

        val slotMessages = mutableListOf<List<ChatMessage>>()

        // 模拟流式响应
        coEvery { mockProvider.stream(capture(slotMessages), any()) } answers {
            val history = firstArg<List<ChatMessage>>()
            val lastMsg = history.last()

            if (lastMsg.role == ChatRole.USER) {
                // Round 1: 模拟切片传输 JSON: {"city": "Beijing"}
                flow {
                    emit(LlmResponse("", toolCalls = listOf(ToolCall(id = "call_1", type = "function", function = FunctionCall(name = "get_weather", arguments = "")))))
                    emit(LlmResponse("", toolCalls = listOf(ToolCall(function = FunctionCall(arguments = "{\"city\": ")))))
                    emit(LlmResponse("", toolCalls = listOf(ToolCall(function = FunctionCall(arguments = "\"Beijing\"}")))))
                }
            } else {
                // Round 2: 返回最终文本
                flow {
                    emit(LlmResponse("It "))
                    emit(LlmResponse("is "))
                    emit(LlmResponse("Sunny"))
                }
            }
        }

        var finalOutput = ""
        stream {
            registerTool("get_weather") { "Sunny" }

            "Check Weather".stream { token ->
                finalOutput += token
            }
        }

        assertEquals("It is Sunny", finalOutput)
        assertEquals(2, slotMessages.size) // 确保发生了两轮交互
    }
}
