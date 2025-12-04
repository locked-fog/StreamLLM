package dev.lockedfog.streamllm.core.memory

import dev.lockedfog.streamllm.core.ChatMessage
import dev.lockedfog.streamllm.core.ChatRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryStorageTest {

    private lateinit var storage: InMemoryStorage

    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
    }

    @Test
    fun `test basic CRUD operations`() = runTest {
        val id = "test_mem"

        // 1. Add Message
        val msg = ChatMessage(ChatRole.USER, "Hi")
        storage.addMessage(id, msg)

        val history = storage.getMessages(id)
        assertEquals(1, history.size)
        assertEquals("Hi", history[0].content)

        // 2. Set System Prompt
        storage.setSystemPrompt(id, "You are a bot")
        assertEquals("You are a bot", storage.getSystemPrompt(id))

        // 3. Clear
        storage.clearMessages(id)
        assertTrue(storage.getMessages(id).isEmpty())
        // System prompt should remain
        assertEquals("You are a bot", storage.getSystemPrompt(id))

        // 4. Delete
        storage.deleteMemory(id)
        assertNull(storage.getSystemPrompt(id))
    }

    @Test
    fun `test full context save`() = runTest {
        val id = "sync_test"
        val messages = listOf(
            ChatMessage(ChatRole.USER, "A"),
            ChatMessage(ChatRole.ASSISTANT, "B")
        )

        storage.saveFullContext(id, "System", messages)

        assertEquals("System", storage.getSystemPrompt(id))
        assertEquals(2, storage.getMessages(id).size)
        assertEquals("B", storage.getMessages(id).last().content)
    }
}