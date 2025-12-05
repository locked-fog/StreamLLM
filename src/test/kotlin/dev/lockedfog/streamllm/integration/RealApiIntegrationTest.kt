package dev.lockedfog.streamllm.integration

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.core.ChatContent
import dev.lockedfog.streamllm.core.ContentPart
import dev.lockedfog.streamllm.core.ImageUrl
import dev.lockedfog.streamllm.core.memory.InMemoryStorage
import dev.lockedfog.streamllm.dsl.stream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertTrue

/**
 * v0.4.0 真实 API 集成测试。
 *
 * 只有在环境变量中配置了 `STREAM_LLM_API_KEY` 时才会运行。
 * 建议使用 SiliconFlow 或 OpenAI 官方 Key。
 */
@EnabledIfEnvironmentVariable(named = "STREAM_LLM_API_KEY", matches = ".+")
class RealApiIntegrationTest {

    private val apiKey = System.getenv("STREAM_LLM_API_KEY") ?: ""
    private val baseUrl = System.getenv("STREAM_LLM_BASE_URL") ?: "https://api.siliconflow.cn/v1"
    // 选择一个支持 Function Calling 的强力模型
    private val model = System.getenv("STREAM_LLM_MODEL") ?: "Qwen/Qwen3-235B-A22B-Instruct-2507"

    @Test
    fun `test real tool calling execution`() = runTest {
        // 初始化
        StreamLLM.init(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = model,
            storage = InMemoryStorage(),
            maxMemoryCount = 5
        )

        println("=== Start Real Tool Call Test (Model: $model) ===")

        stream {
            // 1. 注册一个“获取幸运数字”的工具
            // 这是一个确定的逻辑，模型无法通过自己的知识回答，必须调用工具
            registerTool(
                name = "get_lucky_number",
                description = "获取用户的今日幸运数字",
                parametersJson = """
                    {
                        "type": "object",
                        "properties": {
                            "user_name": { "type": "string", "description": "用户的名字" }
                        },
                        "required": ["user_name"]
                    }
                """.trimIndent()
            ) { args ->
                val name = StreamLLM.json.parseToJsonElement(args).jsonObject["user_name"]?.jsonPrimitive?.content
                println(">>> [Tool] Tool invoked for user: $name")

                // 模拟返回结果
                if (name?.contains("Test") == true) "888" else "0"
            }

            // 2. 提问
            // 预期：模型先调用 get_lucky_number(user_name="TestUser")，得到 "888"，然后回答。
            val prompt = "TestUser 的幸运数字是多少？请直接回答数字。"
            println("User: $prompt")

            val response = StringBuilder()
            prompt.stream { token ->
                print(token)
                response.append(token)
            }
            println("\n\nFinal Response: $response")

            // 验证结果包含工具返回的数据
            assertTrue(response.toString().contains("888"), "Response should contain the tool result '888'")
        }
        println("=== End Real Tool Call Test ===")
    }

    @Test
    fun `test real multimodal vision request`() = runTest {
        // 使用支持视觉的模型 (如 Qwen-VL, GPT-4o)
        // 如果默认模型不支持视觉，这里需要覆盖
        val visionModel = System.getenv("STREAM_LLM_VISION_MODEL") ?: "Qwen/Qwen2-VL-72B-Instruct" // 或 "gpt-4o"

        StreamLLM.init(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = visionModel,
            storage = InMemoryStorage(),
            maxMemoryCount = 5
        )

        println("=== Start Real Vision Test (Model: $visionModel) ===")

        stream {
            val content = ChatContent.Parts(listOf(
                ContentPart.TextPart("图中是什么动物？请用简短的中文回答。"),
                ContentPart.ImagePart(
                    // 一张猫的图片 (Wikimedia Public Domain)
                    ImageUrl("https://ww3.sinaimg.cn/mw690/8bdd53c0gy1i18hparinaj20wi17cte5.jpg")
                )
            ))

            val response = StringBuilder()
            println("User: [Image] 图中是什么动物？")

            content.stream { token ->
                print(token)
                response.append(token)
            }
            println("\n\nFinal Response: $response")

            assertTrue(response.toString().contains("猫"), "Response should recognize the cat")
        }
        println("=== End Real Vision Test ===")
    }
}