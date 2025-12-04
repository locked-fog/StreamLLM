package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.core.memory.MemoryStorage
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryManagerTest {

    private lateinit var storage: MemoryStorage
    private lateinit var memoryManager: MemoryManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeEach
    fun setup() {
        storage = mockk(relaxed = true)
        // 注入 TestScope 以便我们可以用 runCurrent() 控制异步持久化任务
        memoryManager = MemoryManager(storage, maxMemoryCount = 2, persistenceScope = testScope)
    }

    @Test
    fun `test write-through caching`() = testScope.runTest {

        // 1. 调用 addMessage
        memoryManager.addMessage(ChatRole.USER, "Hello")

        // 2. 验证内存缓存立即生效 (不需要等待调度)
        val history = memoryManager.getCurrentHistory()
        assertEquals(1, history.size)
        assertEquals("Hello", history[0].content)

        // 3. 验证 Storage 尚未被调用 (因为是 launch 异步)
        coVerify(exactly = 0) { storage.addMessage(any(), any()) }

        // 4. 执行挂起的协程
        advanceUntilIdle()

        // 5. 验证 Storage 被调用
        coVerify(exactly = 1) { storage.addMessage("default", match { it.content == "Hello" }) }
    }

    @Test
    fun `test preload and switch logic`() = testScope.runTest {
        // 模拟 Storage 中有数据
        coEvery { storage.getMessages("mem_1") } returns listOf(ChatMessage(ChatRole.USER, "Old Msg"))
        coEvery { storage.getSystemPrompt("mem_1") } returns "Sys"

        // 1. 预加载 (Preload)
        memoryManager.preLoad("mem_1")

        // 此时应该正在加载，推进协程
        advanceUntilIdle()

        // 验证 Storage 被读取
        coVerify(exactly = 1) { storage.getMessages("mem_1") }

        // 2. 切换 (Switch)
        // 因为已经预加载，这里应该直接命中缓存，不会再次触发 storage 读取
        memoryManager.switchMemory("mem_1")

        val history = memoryManager.getCurrentHistory(includeSystem = true)
        assertEquals(2, history.size) // System + User
        assertEquals("Sys", history[0].content)

        // 再次验证 calls count 保持为 1
        coVerify(exactly = 1) { storage.getMessages("mem_1") }
    }

    @Test
    fun `test lru eviction triggers persist`() = testScope.runTest {
        // 设置 maxMemoryCount = 2 (在 setup 中)

        // 1. 创建 3 个 Memory，触发驱逐
        // Create A
        memoryManager.switchMemory("A")
        memoryManager.addMessage(ChatRole.USER, "Msg A")

        // Create B
        memoryManager.switchMemory("B")
        memoryManager.addMessage(ChatRole.USER, "Msg B")

        // Create C -> 应该导致 A (最久未使用) 被驱逐
        memoryManager.switchMemory("C")

        // 推进异步任务 (saveFullContext 是在 onEvict 回调里 launch 的)
        advanceUntilIdle()

        // 验证 A 被全量保存
        coVerify { storage.saveFullContext("A", any(), any()) }
        // 验证 B 没被保存 (还在缓存里)
        coVerify(exactly = 0) { storage.saveFullContext("B", any(), any()) }
    }
}