package dev.lockedfog.streamllm.integration

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.core.ChatContent
import dev.lockedfog.streamllm.core.memory.InMemoryStorage
import dev.lockedfog.streamllm.dsl.stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 真实环境集成测试。
 *
 * 安全机制：
 * 1. 不会在代码中硬编码 API Key。
 * 2. 只有当环境变量中有 `STREAM_LLM_API_KEY` 时才会运行。
 * 3. 提交到 Git 安全。
 */
@EnabledIfEnvironmentVariable(named = "STREAM_LLM_API_KEY", matches = ".+")
class RealApiIntegrationTest {

    private val apiKey = System.getenv("STREAM_LLM_API_KEY") ?: ""
    private val baseUrl = System.getenv("STREAM_LLM_BASE_URL") ?: "https://api.siliconflow.cn/v1"
    private val model = System.getenv("STREAM_LLM_MODEL") ?: "Qwen/Qwen2.5-7B-Instruct"

    @Test
    fun `test real chat completion with storage verification`() = runTest {
        val storage = InMemoryStorage()

        // 初始化：注入内存存储，方便验证数据是否落地
        StreamLLM.init(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = model,
            storage = storage,
            maxMemoryCount = 5
        )

        println("=== Start Real Chat (Target: $baseUrl) ===")
        stream {
            val question = "Hello, are you online? Answer YES or NO."
            println("User: $question")

            val answer = StringBuilder()
            question.stream { token ->
                print(token)
                answer.append(token)
            }
            println("\n\nFull Answer: $answer")

            assertTrue(answer.isNotEmpty())
        }

        // 验证异步持久化
        // 由于是 Fire-and-Forget，我们稍作延时确保协程执行完毕
        // 在真实 Unit Test 中可以用 runTest 的 advanceUntilIdle，但这里涉及真实网络 IO，简单的 delay 更稳妥
        delay(200)

        // 验证数据是否写入了 Storage
        val messages = storage.getMessages("default")
        println("Stored Messages Count: ${messages.size}")

        // 应该有 2 条消息：User 提问 + AI 回复
        assertEquals(2, messages.size, "Storage should contain 2 messages (User + AI)")

        // [Fix] 验证内容时需适配 ChatContent
        val userMsgContent = messages[0].content
        assertIs<ChatContent.Text>(userMsgContent)
        assertEquals("Hello, are you online? Answer YES or NO.", userMsgContent.text)

        println("=== End Real Chat ===")
    }
}