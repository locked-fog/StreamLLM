package dev.lockedfog.streamllm

import dev.lockedfog.streamllm.provider.LlmProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StreamLLMTest {

    @Test
    fun `test initialization and close`() {
        val mockProvider = mockk<LlmProvider>(relaxed = true)

        // 1. 初始化
        StreamLLM.init(mockProvider)
        assertNotNull(StreamLLM.defaultProvider)

        // 2. 关闭
        StreamLLM.close()
        assertNull(StreamLLM.defaultProvider)

        // 验证 Provider 的 close 方法被调用
        verify { mockProvider.close() }
    }

    @Test
    fun `test re-initialization closes previous provider`() {
        val provider1 = mockk<LlmProvider>(relaxed = true)
        val provider2 = mockk<LlmProvider>(relaxed = true)

        StreamLLM.init(provider1)
        StreamLLM.init(provider2) // 重新初始化

        // provider1 应该被关闭
        verify { provider1.close() }
        // provider2 应该是当前的
        assert(StreamLLM.defaultProvider == provider2)
    }
}