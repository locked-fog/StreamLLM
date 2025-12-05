package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.dsl.stream
import dev.lockedfog.streamllm.provider.LlmProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StreamScopeTest {

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
    fun `test ask converts string to text content`() = runTest {
        // 捕获发送给 Provider 的消息
        val slot = slot<List<ChatMessage>>()
        coEvery { mockProvider.chat(capture(slot), any()) } returns LlmResponse("OK")

        stream {
            "Hello".ask()
        }

        val messages = slot.captured
        assertEquals(1, messages.size)
        val content = messages[0].content

        // 验证类型为 Text
        assertIs<ChatContent.Text>(content)
        assertEquals("Hello", content.text)
    }

    @Test
    fun `test system prompt injection with content type check`() = runTest {
        val slot = slot<List<ChatMessage>>()
        coEvery { mockProvider.chat(capture(slot), any()) } returns LlmResponse("OK")

        stream {
            "Hi".ask(system = "You are AI")
        }

        val msgs = slot.captured
        assertEquals(2, msgs.size) // System + User

        val sysMsg = msgs[0]
        assertEquals(ChatRole.SYSTEM, sysMsg.role)
        assertIs<ChatContent.Text>(sysMsg.content)
        assertEquals("You are AI", sysMsg.content.text)
    }
}