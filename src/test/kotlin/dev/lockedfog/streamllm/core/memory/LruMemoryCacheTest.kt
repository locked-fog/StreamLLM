package dev.lockedfog.streamllm.core.memory

import dev.lockedfog.streamllm.core.MemoryManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LruMemoryCacheTest {

    @Test
    fun `test lru eviction order`() {
        val evictedKeys = mutableListOf<String>()

        // 创建容量为 2 的缓存
        val cache = LruMemoryCache<String>(2) { key, _ ->
            evictedKeys.add(key)
        }

        cache["A"] = "Value A"
        cache["B"] = "Value B"

        // 此时缓存: [A, B] (B 是最新的)
        assertEquals(2, cache.size)

        // 访问 A，使其变为最新
        cache["A"]
        // 此时缓存: [B, A]

        // 插入 C，应该导致 B 被驱逐
        cache["C"] = "Value C"

        // 验证
        assertEquals(2, cache.size)
        assertTrue(cache.containsKey("A"))
        assertTrue(cache.containsKey("C"))
        assertFalse(cache.containsKey("B"))

        assertEquals(1, evictedKeys.size)
        assertEquals("B", evictedKeys[0])
    }
}