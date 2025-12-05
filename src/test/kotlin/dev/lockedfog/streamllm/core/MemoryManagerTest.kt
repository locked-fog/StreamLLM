package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.core.memory.MemoryStorage
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryManagerTest {

    private lateinit var storage: MemoryStorage
    private lateinit var memoryManager: MemoryManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeEach
    fun setup() {
        storage = mockk(relaxed = true)
        memoryManager = MemoryManager(storage, maxMemoryCount = 2, persistenceScope = testScope)
    }

    @Test
    fun `test addMessage string helper creates Text content`() = testScope.runTest {
        memoryManager.addMessage(ChatRole.USER, "Hello")

        val history = memoryManager.getCurrentHistory()
        val msg = history[0]

        assertIs<ChatContent.Text>(msg.content)
        assertEquals("Hello", (msg.content as ChatContent.Text).text)
    }
}