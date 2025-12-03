package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.dsl.stream
import dev.lockedfog.streamllm.provider.LlmProvider
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamScopeTest {

    private val mockProvider = mockk<LlmProvider>(relaxed = true)

    @BeforeEach
    fun setup() {
        // 初始化单例，注入 Mock Provider
        StreamLLM.init(mockProvider)
        StreamLLM.memory.clear()
    }

    @AfterEach
    fun tearDown() {
        StreamLLM.close()
    }

    @Test
    fun `test basic streaming flow`() = runTest {
        // 1. 模拟 Provider 返回流：Hello, World
        every { mockProvider.stream(any(), any()) } returns flow {
            emit(LlmResponse("Hello"))
            emit(LlmResponse(" "))
            emit(LlmResponse("World"))
        }

        val receivedBuffer = StringBuilder()

        // 2. 执行 Stream DSL
        stream {
            "test".stream { token ->
                receivedBuffer.append(token)
            }
        }

        // 3. 验证接收完整
        assertEquals("Hello World", receivedBuffer.toString())
    }

    @Test
    fun `test adaptive batching with slow consumer`() = runTest {
        // 场景：网络极快（生产 100 个包），UI 极慢（消费需 10ms）
        // 预期：消费者调用次数远少于 100 次（发生合并），但最终数据完整（Flush 生效）

        val totalChunks = 100
        val inputTokens = (1..totalChunks).map { "$it," } // "1,2,3...100,"

        every { mockProvider.stream(any(), any()) } returns flow {
            inputTokens.forEach { token -> emit(LlmResponse(token)) }
        }

        val finalOutput = StringBuilder()
        var callCount = 0

        stream {
            "start".stream { token ->
                callCount++
                finalOutput.append(token)
                delay(10) // 模拟 UI 耗时
            }
        }

        val expectedString = inputTokens.joinToString("")

        // 验证数据完整性 (Flush 机制)
        assertEquals(expectedString, finalOutput.toString(), "所有 Token 必须完整接收，不能丢失")

        // 验证批处理效果 (Skip 机制)
        println("Consumer called $callCount times for $totalChunks chunks")
        assertTrue(callCount < totalChunks, "应当发生合并调用，实际调用了 $callCount 次")
    }

    @Test
    fun `test memory interaction in stream`() = runTest {
        // 验证 stream 方法是否正确写入历史记忆
        every { mockProvider.stream(any(), any()) } returns flow {
            emit(LlmResponse("Response"))
        }

        stream {
            "Question".stream()
        }

        val history = StreamLLM.memory.getCurrentHistory()
        assertEquals(2, history.size)
        assertEquals("Question", history[0].content)
        assertEquals("Response", history[1].content)
    }
}