package dev.lockedfog.streamllm.dsl

import dev.lockedfog.streamllm.core.StreamScope

/**
 * StreamLLM 的 DSL 入口函数。
 *
 * 创建一个 [StreamScope] 并执行给定的挂起代码块。
 * 这是一个挂起函数，必须在协程作用域内调用 (如 `lifecycleScope.launch` 或 `runBlocking`)。
 *
 * 示例：
 * ```kotlin
 * launch {
 * stream {
 * "你好".ask()
 * }
 * }
 * ```
 *
 * @param block 在 [StreamScope] 上下文中执行的挂起代码块。
 */
suspend fun stream(block: suspend StreamScope.() -> Unit) {
    val scope = StreamScope()
    block(scope)
}