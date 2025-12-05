package dev.lockedfog.streamllm.integration

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.core.*
import dev.lockedfog.streamllm.core.memory.InMemoryStorage
import dev.lockedfog.streamllm.dsl.stream
import dev.lockedfog.streamllm.provider.openai.OpenAiChatRequest
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.4.0 新特性的虚拟环境集成测试。
 *
 * 涵盖：
 * 1. 工具调用 (Tool Calling) 的完整 Re-Act 循环模拟。
 * 2. 流式工具调用 (Streaming Tool Calls) 的碎片聚合模拟。
 * 3. 多模态 (Multimodal) 请求的协议格式验证。
 */
class SimulationV4IntegrationTest {

    private val jsonParser = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterEach
    fun tearDown() {
        StreamLLM.close()
    }

    private fun initStreamLLM(handler: MockRequestHandler) {
        val mockEngine = MockEngine(handler)
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(jsonParser)
            }
        }
        StreamLLM.init(
            baseUrl = "https://api.fake.com",
            apiKey = "fake-key",
            modelName = "gpt-v4-sim",
            httpClient = httpClient,
            storage = InMemoryStorage(),
            maxMemoryCount = 10
        )
    }

    @Test
    fun `test tool calling re-act loop`() = runTest {
        // 场景模拟：
        // 1. User: "查询北京天气"
        // 2. Server: 返回 tool_calls: get_weather({"city": "Beijing"})
        // 3. Client: 执行工具 -> 得到 "Sunny" -> 发送 Tool Message
        // 4. Server: 看到 Tool 结果 -> 返回 "北京今天是晴天"

        initStreamLLM { request ->
            val bodyString = request.body.toByteReadPacket().readText()
            val chatReq = jsonParser.decodeFromString<OpenAiChatRequest>(bodyString)
            val messages = chatReq.messages
            val lastMsg = messages.last()

            val responseJson = when (lastMsg.role) {
                ChatRole.USER -> {
                    // 第 1 步：收到用户提问，返回工具调用
                    """
                    {
                        "choices": [{
                            "message": {
                                "role": "assistant",
                                "tool_calls": [{
                                    "id": "call_123",
                                    "type": "function",
                                    "function": {
                                        "name": "get_weather",
                                        "arguments": "{\"city\": \"Beijing\"}"
                                    }
                                }]
                            }
                        }]
                    }
                    """.trimIndent()
                }
                ChatRole.TOOL -> {
                    // 第 3 步：收到工具结果，验证结果内容
                    val toolResult = (lastMsg.content as ChatContent.Text).text
                    if (toolResult == "Sunny") {
                        """
                        {
                            "choices": [{
                                "message": { "role": "assistant", "content": "北京今天是晴天" }
                            }]
                        }
                        """.trimIndent()
                    } else {
                        """{"choices": [{"message": {"role": "assistant", "content": "Error"}}]}"""
                    }
                }
                else -> """{"choices": []}"""
            }

            respond(responseJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        stream {
            // 注册工具
            registerTool("get_weather") { args ->
                if (args.contains("Beijing")) "Sunny" else "Unknown"
            }

            val answer = "查询北京天气".ask()
            assertEquals("北京今天是晴天", answer)
        }
    }

    @Test
    fun `test streaming tool call aggregation`() = runTest {
        // 场景模拟：服务端通过 SSE 流式返回被切碎的 JSON 参数
        // chunk1: {"function": {"name": "search", "arguments": ""}}
        // chunk2: {"function": {"arguments": "{\"q\": "}}
        // chunk3: {"function": {"arguments": "\"Kotlin\"}"}}

        val sseStream = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_stream","type":"function","function":{"name":"search","arguments":""}}]}}]}
            
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\": "}}]}}]}
            
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Kotlin\"}"}}]}}]}
            
            data: [DONE]
        """.trimIndent()

        // 第二轮：最终回复
        val finalResponseSSE = """
            data: {"choices": [{"delta": { "content": "Found" }}]}
            
            data: {"choices": [{"delta": { "content": " Kotlin" }}]}
            
            data: [DONE]
        """.trimIndent()

        initStreamLLM { request ->
            val body = request.body.toByteReadPacket().readText()
            if (body.contains("\"role\":\"tool\"")) {
                // [Fix] 返回 SSE 格式
                respond(finalResponseSSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond(ByteReadChannel(sseStream), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        }

        stream {
            registerTool("search") { args ->
                // 验证聚合后的参数是否完整
                if (args == """{"q": "Kotlin"}""") "OK" else "Fail"
            }

            // 使用流式请求
            val result = StringBuilder()
            "Search Kotlin".stream { token ->
                result.append(token)
            }

            assertEquals("Found Kotlin", result.toString())
        }
    }

    @Test
    fun `test multimodal request protocol`() = runTest {
        // 验证 ChatContent.Parts 是否被正确序列化为符合 OpenAI/SiliconFlow 规范的 JSON

        initStreamLLM { request ->
            val body = request.body.toByteReadPacket().readText()

            // 验证 JSON 结构
            // 1. 包含 image_url 类型
            val hasImage = body.contains("\"type\":\"image_url\"")
            // 2. 包含具体的 URL
            val hasUrl = body.contains("https://img.com/1.png")
            // 3. 包含文本部分
            val hasText = body.contains("Look at this")

            if (hasImage && hasUrl && hasText) {
                respond("""{"choices":[{"message":{"role":"assistant","content":"I see"}}]}""",
                    HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("Bad Request", HttpStatusCode.BadRequest)
            }
        }

        stream {
            val content = ChatContent.Parts(listOf(
                ContentPart.TextPart("Look at this"),
                ContentPart.ImagePart(ImageUrl("https://img.com/1.png"))
            ))

            val resp = content.ask()
            assertEquals("I see", resp)
        }
    }
}