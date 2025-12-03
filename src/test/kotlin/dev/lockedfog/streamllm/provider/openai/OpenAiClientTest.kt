package dev.lockedfog.streamllm.provider.openai

import dev.lockedfog.streamllm.core.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.* // [新增]
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.* // [新增]
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json           // [新增]
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiClientTest {

    // [修复] 在这里安装 ContentNegotiation 插件
    private fun createMockClient(handler: MockRequestHandler): OpenAiClient {
        val mockEngine = MockEngine(handler)
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
        }
        return OpenAiClient(
            baseUrl = "https://api.test.com",
            apiKey = "test-key",
            defaultModel = "gpt-test",
            httpClient = httpClient
        )
    }

    @Test
    fun `test chat request success`() = runTest {
        val jsonResponse = """
            {
              "id": "chatcmpl-123",
              "choices": [{
                "message": { "role": "assistant", "content": "Hello World" }
              }],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
            }
        """.trimIndent()

        val client = createMockClient { request ->
            // 验证请求头
            assertEquals("Bearer test-key", request.headers["Authorization"])

            respond(
                content = jsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val response = client.chat(listOf(ChatMessage(ChatRole.USER, "Hi")))

        assertEquals("Hello World", response.content)
        assertEquals(15, response.usage?.totalTokens)
    }

    @Test
    fun `test stream request parsing`() = runTest {
        val sseResponse = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}
            
            data: {"choices":[{"delta":{"content":"lo"}}]}
            
            data: [DONE]
        """.trimIndent()

        val client = createMockClient { _ ->
            respond(
                content = ByteReadChannel(sseResponse),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val responses = client.stream(listOf(ChatMessage(ChatRole.USER, "Hi"))).toList()

        // 注意：流式响应中，content 是分片的，usage 通常为空(除非是最后一个包)
        // 这里的测试只验证能否解析 content
        val fullContent = responses.joinToString("") { it.content }
        assertEquals("Hello", fullContent)
    }

    @Test
    fun `test error handling 401`() = runTest {
        val client = createMockClient { _ ->
            respond(
                content = "Invalid API Key",
                status = HttpStatusCode.Unauthorized
            )
        }

        assertFailsWith<AuthenticationException> {
            client.chat(listOf(ChatMessage(ChatRole.USER, "Hi")))
        }
    }

    @Test
    fun `test request body format`() = runTest {
        val client = createMockClient { request ->
            val body = request.body.toByteReadPacket().readText()
            // 简单验证 JSON 结构
            assertTrue(body.contains(""""model":"gpt-test""""))
            assertTrue(body.contains(""""role":"user""""))

            respond("{}", HttpStatusCode.OK)
        }

        try { client.chat(listOf(ChatMessage(ChatRole.USER, "Hi"))) } catch (_: Exception) {}
    }
}