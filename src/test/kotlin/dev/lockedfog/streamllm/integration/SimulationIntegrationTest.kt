package dev.lockedfog.streamllm.integration

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.core.ServerException
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
import kotlin.test.assertFailsWith

class SimulationIntegrationTest {

    private val jsonParser = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterEach
    fun tearDown() {
        StreamLLM.close()
    }

    /**
     * 创建一个模拟的 StreamLLM 环境。
     * handler 用于定义服务端的行为（模拟 OpenAI API）。
     */
    private fun initStreamLLM(handler: MockRequestHandler) {
        val mockEngine = MockEngine(handler)
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(jsonParser)
            }
        }

        // 初始化 StreamLLM，注入带有 MockEngine 的 HttpClient
        // 显式使用 InMemoryStorage，确保每次测试状态隔离
        StreamLLM.init(
            baseUrl = "https://api.fake.com",
            apiKey = "fake-key",
            modelName = "gpt-fake",
            httpClient = httpClient,
            storage = InMemoryStorage(), // 使用内存存储
            maxMemoryCount = 10
        )
    }

    @Test
    fun `test multi-turn conversation (Memory Integration)`() = runTest {
        // 场景：
        // Round 1: User "Hi" -> Server 检查无历史 -> Resp "Hello"
        // Round 2: User "My name is Key" -> Server 检查有 "Hi" -> Resp "OK"
        // Round 3: User "Who am I?" -> Server 检查有 "My name is Key" -> Resp "Key"

        initStreamLLM { request ->
            val bodyString = request.body.toByteReadPacket().readText()
            val chatReq = jsonParser.decodeFromString<OpenAiChatRequest>(bodyString)
            val messages = chatReq.messages
            val lastMsg = messages.last().content

            val responseContent = when {
                lastMsg == "Hi" -> {
                    // Round 1 验证：应该只有 1 条消息 (User) + System(可选)
                    if (messages.count { it.role.name == "USER" } != 1) error("Round 1 history error")
                    "Hello"
                }
                lastMsg == "My name is Key" -> {
                    // Round 2 验证：应该有 3 条消息 (User, Asst, User)
                    if (messages.size < 3) error("Round 2 history missing")
                    "OK"
                }
                lastMsg == "Who am I?" -> {
                    // Round 3 验证：历史中应包含名字
                    val historyStr = messages.joinToString { it.content }
                    if (!historyStr.contains("My name is Key")) error("Context lost")
                    "Key"
                }
                else -> "Unknown"
            }

            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"$responseContent"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        stream {
            // Round 1
            val resp1 = "Hi".ask()
            assertEquals("Hello", resp1)

            // Round 2
            val resp2 = "My name is Key".ask()
            assertEquals("OK", resp2)

            // Round 3
            val resp3 = "Who am I?".ask()
            assertEquals("Key", resp3)
        }
    }

    @Test
    fun `test streaming buffer integration`() = runTest {
        // 场景：服务端发送 SSE 流，验证 StreamScope 正确缓冲并输出
        val sseData = """
            data: {"choices":[{"delta":{"content":"P"}}]}
            
            data: {"choices":[{"delta":{"content":"art1"}}]}
            
            data: {"choices":[{"delta":{"content":" "}}]}
            
            data: {"choices":[{"delta":{"content":"Part2"}}]}
            
            data: [DONE]
        """.trimIndent()

        initStreamLLM {
            respond(
                content = ByteReadChannel(sseData),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val received = StringBuilder()
        stream {
            "Start Stream".stream { token ->
                received.append(token)
            }
        }

        assertEquals("Part1 Part2", received.toString())
    }

    @Test
    fun `test error propagation`() = runTest {
        // 场景：服务端 500 错误，验证客户端抛出 ServerException
        initStreamLLM {
            respond("Internal Error", HttpStatusCode.InternalServerError)
        }

        assertFailsWith<ServerException> {
            stream {
                "Boom".ask()
            }
        }
    }

    @Test
    fun `test json auto-fix integration`() = runTest {
        // 场景：ask<T> 请求结构化数据
        // 第一次：服务端返回非法 JSON
        // 第二次：客户端自动重试，服务端检测到这是重试请求（包含 "Previous JSON invalid"），返回正确 JSON

        initStreamLLM { request ->
            val body = request.body.toByteReadPacket().readText()

            if (body.contains("Previous JSON invalid")) {
                // 这是重试请求，返回正确 JSON
                respond(
                    """{"choices":[{"message":{"role":"assistant","content":"{\"result\": 100}"}}]}""",
                    HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                // 这是首次请求，返回错误数据
                respond(
                    """{"choices":[{"message":{"role":"assistant","content":"I am not JSON"}}]}""",
                    HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }

        @kotlinx.serialization.Serializable
        data class Result(val result: Int)

        stream {
            // 这里应该自动触发重试并成功
            val data = "Calc".ask<Result>()
            assertEquals(100, data.result)
        }
    }
}