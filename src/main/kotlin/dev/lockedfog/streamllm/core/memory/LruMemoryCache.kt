package dev.lockedfog.streamllm.core.memory

import java.util.LinkedHashMap

const val LOAD_FACTORY: Float = 0.75f
/**
 * 基于 LRU (Least Recently Used) 算法的内存缓存容器。
 *
 * 继承自 [LinkedHashMap]，并开启了 accessOrder 模式。
 * 当缓存容量超过 [maxSize] 时，会自动移除最久未使用的条目，并触发 [onEvict] 回调。
 *
 * 典型用途是作为 [dev.lockedfog.streamllm.core.MemoryManager] 的 L1 缓存。
 *
 * 注意：此类本身是非线程安全的。由于 LRU 机制涉及链表重排，
 * 在多线程环境中使用（如协程中）必须由外部提供互斥机制（如 Mutex）。
 *
 * @param V 缓存值的类型。
 * @property maxSize 最大缓存条目数。
 * @property onEvict 当条目被驱逐时触发的回调函数。参数为 (key, value)。
 */
class LruMemoryCache<V>(
    private val maxSize: Int,
    private val onEvict: (String, V) -> Unit
) : LinkedHashMap<String, V>(
    // initialCapacity: 设置为略大于 maxSize / 0.75 的值以避免扩容
    (maxSize / LOAD_FACTORY).toInt() + 1,
    // loadFactor: 标准负载因子
    LOAD_FACTORY,
    // accessOrder: true 表示按照访问顺序排序 (LRU 的核心)
    // 最近访问的元素会被移到链表尾部，最老的元素在头部
    true
) {

    /**
     * 判断是否移除最老的条目。
     * 此方法会在每次 put() 或 putAll() 后由 JDK 内部调用。
     */
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean {
        if (size > maxSize) {
            eldest?.let {
                // 触发驱逐回调，允许外部执行持久化、日志记录或其他清理逻辑
                onEvict(it.key, it.value)
            }
            // 返回 true 通知 LinkedHashMap 删除该条目
            return true
        }
        return false
    }
}
