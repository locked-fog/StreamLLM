package dev.lockedfog.streamllm.integration

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.dsl.stream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertTrue

/**
 * 真实环境集成测试。
 *
 * 安全机制：
 * 1. 不会在代码中硬编码 API Key。
 * 2. 只有当环境变量中有 `STREAM_LLM_API_KEY` 时才会运行。
 * 3. 提交到 Git 安全。
 */
// 只有当系统环境变量存在 STREAM_LLM_API_KEY 时，才执行此类中的测试
@EnabledIfEnvironmentVariable(named = "STREAM_LLM_API_KEY", matches = ".+")
class RealApiIntegrationTest {

    // 从环境变量获取，如果没有则为空字符串（由于上面的注解，实际上为空时不会执行测试）
    private val apiKey = System.getenv("STREAM_LLM_API_KEY") ?: ""

    // 如果需要支持自定义 BaseUrl，也可以读环境变量，或者给一个默认的公共地址
    private val baseUrl = System.getenv("STREAM_LLM_BASE_URL") ?: "https://api.siliconflow.cn/v1"
    private val model = System.getenv("STREAM_LLM_MODEL") ?: "Qwen/Qwen2.5-7B-Instruct"

    @Test
    fun `test real chat completion`() = runTest {
        // 初始化
        StreamLLM.init(baseUrl, apiKey, model)

        println("=== Start Real Chat (Target: $baseUrl) ===")
        stream {
            val question = "Hello, are you online?"
            println("User: $question")

            val answer = StringBuilder()
            question.stream { token ->
                print(token)
                answer.append(token)
            }
            println("\n\nFull Answer: $answer")

            assertTrue(answer.isNotEmpty())
        }
        println("=== End Real Chat ===")
    }
}