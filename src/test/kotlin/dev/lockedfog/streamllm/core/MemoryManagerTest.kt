package dev.lockedfog.streamllm.core

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryManagerTest {

    private lateinit var memoryManager: MemoryManager

    @BeforeEach
    fun setup() {
        memoryManager = MemoryManager()
    }

    @Test
    fun `test default memory operations`() {
        memoryManager.addMessage(ChatRole.USER, "Hello")
        memoryManager.addMessage(ChatRole.ASSISTANT, "Hi")

        val history = memoryManager.getCurrentHistory()
        assertEquals(2, history.size)
        assertEquals(ChatRole.USER, history[0].role)
        assertEquals("Hello", history[0].content)
    }

    @Test
    fun `test memory switching`() {
        // 默认记忆体
        memoryManager.addMessage(ChatRole.USER, "In Default")

        // 创建并切换到新记忆体
        memoryManager.createMemory("coding", systemPrompt = "You are a coder")
        memoryManager.switchMemory("coding")
        memoryManager.addMessage(ChatRole.USER, "In Coding")

        // 验证当前是 Coding
        val codingHistory = memoryManager.getCurrentHistory(includeSystem = true)
        assertEquals(2, codingHistory.size) // System + User
        assertEquals("You are a coder", codingHistory[0].content)
        assertEquals("In Coding", codingHistory[1].content)

        // 切回默认
        memoryManager.switchMemory("default")
        val defaultHistory = memoryManager.getCurrentHistory()
        assertEquals(1, defaultHistory.size)
        assertEquals("In Default", defaultHistory[0].content)
    }

    @Test
    fun `test history window slicing`() {
        // 添加 5 条消息
        repeat(5) { i ->
            memoryManager.addMessage(ChatRole.USER, "Msg $i")
        }

        // 获取最近 2 条
        val recent = memoryManager.getCurrentHistory(windowSize = 2)
        assertEquals(2, recent.size)
        assertEquals("Msg 3", recent[0].content)
        assertEquals("Msg 4", recent[1].content)

        // 获取全部 (-1)
        val all = memoryManager.getCurrentHistory(windowSize = -1)
        assertEquals(5, all.size)
    }

    @Test
    fun `test invalid memory switch throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            memoryManager.switchMemory("non-existent")
        }
    }
}