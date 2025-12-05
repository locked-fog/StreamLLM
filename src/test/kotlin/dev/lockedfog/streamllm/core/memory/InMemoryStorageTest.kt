package dev.lockedfog.streamllm.core.memory

import dev.lockedfog.streamllm.core.ChatContent
import dev.lockedfog.streamllm.core.ChatMessage
import dev.lockedfog.streamllm.core.ChatRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InMemoryStorageTest {

    private lateinit var storage: InMemoryStorage

    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
    }

    @Test
    fun `test storing multimodal message`() = runTest {
        val id = "mm_test"
        val content = ChatContent.Parts(emptyList()) // 空列表作为测试对象
        val msg = ChatMessage(ChatRole.USER, content)

        storage.addMessage(id, msg)

        val retrieved = storage.getMessages(id).first()
        assertIs<ChatContent.Parts>(retrieved.content)
    }
}